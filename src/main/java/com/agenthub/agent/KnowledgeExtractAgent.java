package com.agenthub.agent;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.agenthub.service.DashScopeChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

@Component
public class KnowledgeExtractAgent {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a professional knowledge extraction engine.
            Extract entities, relations, and answer-friendly knowledge notes from the given text and return JSON only.
            {
              "entities": [{"name": "entity name", "type": "entity type", "description": "description"}],
              "relations": [{"head": "head entity", "relation": "relation type", "tail": "tail entity", "confidence": 0.95}],
              "notes": [{"topic": "main topic", "kind": "definition|principle|mechanism|example|limitation", "content": "concise answer-ready note"}]
            }
            Allowed entity types include: Person, Organization, Technology, Product, Concept, Location.
            Allowed relation types include: belongs_to, works_at, developed_by, related_to, part_of, uses, depends_on.
            Extract notes when the text contains a direct definition, underlying principle, mechanism explanation, concrete example, or explicit limitation.
            Do not return any explanation outside the JSON.
            """;

    private final DashScopeChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService workflowExecutor;
    private final int extractMaxConcurrency;

    public KnowledgeExtractAgent(DashScopeChatService chatService,
                                 @Qualifier("workflowExecutor") ExecutorService workflowExecutor,
                                 @Value("${app.execution.extract-max-concurrency:8}") int extractMaxConcurrency) {
        this.chatService = chatService;
        this.workflowExecutor = workflowExecutor;
        this.extractMaxConcurrency = Math.max(1, extractMaxConcurrency);
    }

    public List<ExtractionResult> extract(List<DocumentChunk> chunks) {
        return extract(chunks, (processed, total) -> {
        });
    }

    public List<ExtractionResult> extract(List<DocumentChunk> chunks, BiConsumer<Integer, Integer> progressConsumer) {
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
        int processed = 0;
        for (CompletableFuture<ExtractionResult> future : futures) {
            ExtractionResult result = joinResult(future);
            deduplicate(result, seenEntities, seenRelations);
            results.add(result);
            processed++;
            progressConsumer.accept(processed, chunks.size());
        }
        return results;
    }

    protected ExtractionResult extractFromChunk(DocumentChunk chunk) {
        try {
            String response = chatService.chat(
                    SYSTEM_PROMPT,
                    "Please extract knowledge from the following text:\n\n" + chunk.getContent()
            );

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

            List<ExtractionResult.KnowledgeNote> notes = new ArrayList<>();
            if (root.has("notes")) {
                for (JsonNode noteNode : root.get("notes")) {
                    String content = noteNode.path("content").asText("").trim();
                    if (content.isBlank()) {
                        continue;
                    }
                    notes.add(ExtractionResult.KnowledgeNote.builder()
                            .topic(noteNode.path("topic").asText(sourceId))
                            .kind(noteNode.path("kind").asText("definition"))
                            .content(content)
                            .build());
                }
            }
            notes = enrichNotes(entities, notes, sourceId);

            return ExtractionResult.builder()
                    .entities(entities)
                    .relations(relations)
                    .notes(notes)
                    .sourceChunkId(sourceId)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse extraction response for chunk {}: {}", sourceId, e.getMessage());
            return ExtractionResult.builder()
                    .entities(List.of())
                    .relations(List.of())
                    .notes(List.of())
                    .sourceChunkId(sourceId)
                    .build();
        }
    }

    private List<ExtractionResult.KnowledgeNote> enrichNotes(List<ExtractionResult.Entity> entities,
                                                             List<ExtractionResult.KnowledgeNote> notes,
                                                             String sourceId) {
        List<ExtractionResult.KnowledgeNote> enriched = new ArrayList<>(notes == null ? List.of() : notes);
        String primaryTopic = entities == null || entities.isEmpty()
                ? sourceId
                : entities.stream()
                .max(Comparator.comparingInt(entity -> safeLength(entity.getDescription()) + safeLength(entity.getName())))
                .map(ExtractionResult.Entity::getName)
                .orElse(sourceId);

        boolean hasDefinition = enriched.stream().anyMatch(note -> "definition".equalsIgnoreCase(note.getKind()));
        boolean hasPrinciple = enriched.stream().anyMatch(note -> "principle".equalsIgnoreCase(note.getKind()));
        boolean hasExample = enriched.stream().anyMatch(note -> "example".equalsIgnoreCase(note.getKind()));

        ExtractionResult.Entity primaryEntity = entities == null || entities.isEmpty() ? null : entities.getFirst();
        if (!hasDefinition && primaryEntity != null && primaryEntity.getDescription() != null
                && !primaryEntity.getDescription().isBlank()) {
            enriched.add(ExtractionResult.KnowledgeNote.builder()
                    .topic(primaryEntity.getName())
                    .kind("definition")
                    .content(primaryEntity.getDescription().trim())
                    .build());
            hasDefinition = true;
        }

        if (!hasDefinition && primaryEntity != null && primaryEntity.getDescription() != null
                && !primaryEntity.getDescription().isBlank()) {
            enriched.add(ExtractionResult.KnowledgeNote.builder()
                    .topic(primaryTopic)
                    .kind("definition")
                    .content(primaryTopic + " is characterized by " + primaryEntity.getDescription().trim())
                    .build());
        }

        if (!hasPrinciple && entities != null && entities.size() >= 2) {
            String summary = entities.stream()
                    .limit(3)
                    .map(ExtractionResult.Entity::getName)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            if (!summary.isBlank()) {
                enriched.add(ExtractionResult.KnowledgeNote.builder()
                        .topic(primaryTopic)
                        .kind("principle")
                        .content(primaryTopic + " is organized around " + summary + ".")
                        .build());
            }
        }

        if (!hasExample && entities != null) {
            ExtractionResult.Entity exampleEntity = entities.stream()
                    .filter(entity -> entity.getDescription() != null && entity.getDescription().length() > 8)
                    .findFirst()
                    .orElse(null);
            if (exampleEntity != null) {
                enriched.add(ExtractionResult.KnowledgeNote.builder()
                        .topic(primaryTopic)
                        .kind("example")
                        .content("An example detail is " + exampleEntity.getName() + ": " + exampleEntity.getDescription().trim())
                        .build());
            }
        }

        return deduplicateNotes(enriched);
    }

    private List<ExtractionResult.KnowledgeNote> deduplicateNotes(List<ExtractionResult.KnowledgeNote> notes) {
        Set<String> seen = new HashSet<>();
        return notes.stream()
                .filter(note -> note.getContent() != null && !note.getContent().isBlank())
                .filter(note -> seen.add((note.getTopic() + "::" + note.getKind() + "::" + note.getContent()).toLowerCase()))
                .toList();
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private void deduplicate(ExtractionResult result, Set<String> seenEntities, Set<String> seenRelations) {
        result.setEntities(result.getEntities().stream()
                .filter(entity -> seenEntities.add(entity.getName() + "::" + entity.getType()))
                .toList());
        result.setRelations(result.getRelations().stream()
                .filter(relation -> seenRelations.add(relation.getHead() + "::" + relation.getRelation() + "::" + relation.getTail()))
                .toList());
        result.setNotes(deduplicateNotes(result.getNotes() == null ? List.of() : result.getNotes()));
    }
}
