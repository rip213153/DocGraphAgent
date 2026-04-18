package com.agenthub.agent;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

@Component
public class KnowledgeExtractAgent {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是一个专业的知识抽取引擎。
            请从给定文本中抽取实体和关系，并仅返回 JSON：
            {
              "entities": [{"name": "实体名", "type": "类型", "description": "描述"}],
              "relations": [{"head": "头实体", "relation": "关系", "tail": "尾实体", "confidence": 0.95}]
            }
            实体类型可包括：Person, Organization, Technology, Product, Concept, Location。
            关系类型可包括：belongs_to, works_at, developed_by, related_to, part_of, uses, depends_on。
            不要返回 JSON 之外的说明。
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService workflowExecutor;
    private final int extractMaxConcurrency;

    public KnowledgeExtractAgent(ChatClient.Builder chatClientBuilder,
                                 @Qualifier("workflowExecutor") ExecutorService workflowExecutor,
                                 @Value("${app.execution.extract-max-concurrency:8}") int extractMaxConcurrency) {
        this.chatClient = chatClientBuilder.build();
        this.workflowExecutor = workflowExecutor;
        this.extractMaxConcurrency = Math.max(1, extractMaxConcurrency);
    }

    public List<ExtractionResult> extract(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        Semaphore semaphore = new Semaphore(extractMaxConcurrency);
        List<CompletableFuture<ExtractionResult>> futures = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> extractWithPermit(chunk, semaphore),
                    workflowExecutor
            ));
        }

        List<ExtractionResult> results = new ArrayList<>(chunks.size());
        Set<String> seenEntities = new HashSet<>();
        Set<String> seenRelations = new HashSet<>();
        for (CompletableFuture<ExtractionResult> future : futures) {
            ExtractionResult result = joinResult(future);
            deduplicate(result, seenEntities, seenRelations);
            results.add(result);
        }
        return results;
    }

    protected ExtractionResult extractFromChunk(DocumentChunk chunk) {
        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage("请从以下文本中抽取知识：\n\n" + chunk.getContent())
            ))).call().content();

            return parseResponse(response, chunk.getChunkId());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract knowledge from chunk " + chunk.getChunkId(), e);
        }
    }

    private ExtractionResult extractWithPermit(DocumentChunk chunk, Semaphore semaphore) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            return extractFromChunk(chunk);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while extracting chunk " + chunk.getChunkId(), e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private ExtractionResult joinResult(CompletableFuture<ExtractionResult> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Knowledge extraction task failed", cause);
        }
    }

    private ExtractionResult parseResponse(String raw, String sourceId) {
        try {
            String cleaned = raw == null ? "" : raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
                cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
            }

            JsonNode root = objectMapper.readTree(cleaned);

            List<ExtractionResult.Entity> entities = new ArrayList<>();
            if (root.has("entities")) {
                for (JsonNode entityNode : root.get("entities")) {
                    entities.add(ExtractionResult.Entity.builder()
                            .name(entityNode.path("name").asText())
                            .type(entityNode.path("type").asText("Concept"))
                            .description(entityNode.path("description").asText(""))
                            .build());
                }
            }

            List<ExtractionResult.Relation> relations = new ArrayList<>();
            if (root.has("relations")) {
                for (JsonNode relationNode : root.get("relations")) {
                    relations.add(ExtractionResult.Relation.builder()
                            .head(relationNode.path("head").asText())
                            .relation(relationNode.path("relation").asText("related_to"))
                            .tail(relationNode.path("tail").asText())
                            .confidence(relationNode.path("confidence").asDouble(0.5))
                            .build());
                }
            }

            return ExtractionResult.builder()
                    .entities(entities)
                    .relations(relations)
                    .sourceChunkId(sourceId)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse extraction response for chunk {}: {}", sourceId, e.getMessage());
            return ExtractionResult.builder()
                    .entities(List.of())
                    .relations(List.of())
                    .sourceChunkId(sourceId)
                    .build();
        }
    }

    private void deduplicate(ExtractionResult result, Set<String> seenEntities, Set<String> seenRelations) {
        result.setEntities(result.getEntities().stream()
                .filter(entity -> seenEntities.add(entity.getName() + "::" + entity.getType()))
                .toList());
        result.setRelations(result.getRelations().stream()
                .filter(relation -> seenRelations.add(relation.getHead() + "::" + relation.getRelation() + "::" + relation.getTail()))
                .toList());
    }
}
