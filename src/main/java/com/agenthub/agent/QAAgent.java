package com.agenthub.agent;

import com.agenthub.memory.MemoryContext;
import com.agenthub.model.QAResult;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import com.agenthub.workflow.WorkflowDegradeReason;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class QAAgent {

    private static final String ANSWER_PROMPT = """
            你是一名企业知识问答助手。
            请严格基于给定上下文回答问题，并尽量引用来源。
            如果上下文不足以支撑明确结论，请直接说明信息不足，不要编造。
            """;

    private enum QuestionMode {
        RELATIONSHIP,
        DESCRIPTIVE
    }

    private final ChatClient chatClient;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final MeterRegistry meterRegistry;
    private final int topK;

    public QAAgent(ChatClient.Builder chatClientBuilder,
                   VectorStoreService vectorStoreService,
                   KnowledgeGraphService knowledgeGraphService,
                   MeterRegistry meterRegistry,
                   @Value("${app.vector.top-k-default:5}") int topK) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStoreService = vectorStoreService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.meterRegistry = meterRegistry;
        this.topK = topK;
    }

    public QAResult answer(String question) {
        return answer(question, MemoryContext.builder().build());
    }

    public QAResult answer(String question, MemoryContext memoryContext) {
        List<String> reasoning = new ArrayList<>();
        List<String> degradeReasons = new ArrayList<>();
        boolean vectorFailed = false;
        boolean graphFailed = false;
        QuestionMode questionMode = detectQuestionMode(question);
        reasoning.add("QUESTION_MODE: " + questionMode.name());

        if (memoryContext != null && memoryContext.hasShortTermContext()) {
            reasoning.add("MEMORY_LOADED: short-term memory loaded");
        } else {
            reasoning.add("MEMORY_LOADED: no short-term memory available");
        }

        List<QAResult.RetrievedContext> vectorResults = List.of();
        try {
            vectorResults = vectorStoreService.search(question, topK);
            reasoning.add("VECTOR_RETRIEVED: " + vectorResults.size());
            meterRegistry.counter("agenthub.qa.vector.results", "questionMode", questionMode.name()).increment(vectorResults.size());
        } catch (Exception e) {
            vectorFailed = true;
            degradeReasons.add(WorkflowDegradeReason.VECTOR_UNAVAILABLE.name());
            reasoning.add("VECTOR_RETRIEVED: degraded because " + e.getClass().getSimpleName());
            meterRegistry.counter("agenthub.qa.degraded", "path", "vector").increment();
        }

        List<QAResult.RetrievedContext> graphResults = List.of();
        try {
            graphResults = knowledgeGraphService.searchByQuestion(question);
            reasoning.add("GRAPH_RETRIEVED: " + graphResults.size());
            meterRegistry.counter("agenthub.qa.graph.results", "questionMode", questionMode.name()).increment(graphResults.size());
        } catch (Exception e) {
            graphFailed = true;
            degradeReasons.add(WorkflowDegradeReason.GRAPH_UNAVAILABLE.name());
            reasoning.add("GRAPH_RETRIEVED: degraded because " + e.getClass().getSimpleName());
            meterRegistry.counter("agenthub.qa.degraded", "path", "graph").increment();
        }

        if (vectorFailed && graphResults.isEmpty()) {
            throw new IllegalStateException("Vector retrieval failed and no graph fallback is available");
        }
        if (graphFailed && vectorResults.isEmpty()) {
            throw new IllegalStateException("Graph retrieval failed and no vector fallback is available");
        }

        List<QAResult.RetrievedContext> merged = hybridRerank(vectorResults, graphResults, questionMode, reasoning);
        reasoning.add("RERANKED: " + merged.size());
        List<QAResult.RetrievedContext> topContexts = merged.subList(0, Math.min(8, merged.size()));
        meterRegistry.counter("agenthub.qa.top.contexts").increment(topContexts.size());

        String contextText = buildContextText(memoryContext, topContexts, reasoning);
        String answerText = generateAnswer(question, contextText);
        reasoning.add("ANSWER_GENERATED");

        return QAResult.builder()
                .question(question)
                .answer(answerText)
                .confidence(calcConfidence(topContexts))
                .intent(questionMode.name().toLowerCase())
                .degraded(!degradeReasons.isEmpty())
                .degradeReasons(List.copyOf(degradeReasons))
                .contexts(topContexts)
                .reasoningSteps(reasoning)
                .build();
    }

    private List<QAResult.RetrievedContext> hybridRerank(
            List<QAResult.RetrievedContext> vector,
            List<QAResult.RetrievedContext> graph,
            QuestionMode mode,
            List<String> reasoning) {
        List<QAResult.RetrievedContext> all = new ArrayList<>();
        double vectorWeight = mode == QuestionMode.RELATIONSHIP ? 0.80 : 1.20;
        double graphWeight = mode == QuestionMode.RELATIONSHIP ? 1.40 : 0.90;
        reasoning.add("WEIGHTS: vector=" + vectorWeight + ", graph=" + graphWeight);

        boolean highConfidenceGraph = hasHighConfidenceEntity(graph);
        if (mode == QuestionMode.RELATIONSHIP && graph.size() < 2 && !vector.isEmpty()) {
            reasoning.add("GRAPH_EVIDENCE_LOW: vector contexts retained as supplement");
        }
        if (mode == QuestionMode.DESCRIPTIVE && highConfidenceGraph) {
            reasoning.add("GRAPH_BACKGROUND_RETAINED: high-confidence entity matched");
        }
        if (hasSourceConflict(vector, graph)) {
            reasoning.add("SOURCE_CONFLICT: vector and graph point to different leading sources");
        }

        vector.forEach(context -> {
            double adjusted = context.getScore() * vectorWeight;
            if (mode == QuestionMode.RELATIONSHIP && graph.size() < 2) {
                adjusted *= 1.05;
            }
            context.setScore(adjusted);
            all.add(context);
        });
        graph.forEach(context -> {
            double adjusted = context.getScore() * graphWeight;
            if (mode == QuestionMode.DESCRIPTIVE && !highConfidenceGraph) {
                adjusted *= 0.95;
            }
            context.setScore(adjusted);
            all.add(context);
        });
        if (!vector.isEmpty() && !graph.isEmpty()) {
            reasoning.add("CONTEXT_MIXED: vector+graph");
        }
        all.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));
        return all;
    }

    private String buildContextText(MemoryContext memoryContext, List<QAResult.RetrievedContext> contexts, List<String> reasoning) {
        StringBuilder builder = new StringBuilder();
        if (memoryContext != null && memoryContext.hasShortTermContext()) {
            builder.append("[MEMORY]\n")
                    .append(memoryContext.getShortTermContext())
                    .append("\n\n");
        }
        appendContexts(builder, "VECTOR_CONTEXTS", contexts, "vector");
        appendContexts(builder, "GRAPH_CONTEXTS", contexts, "graph");
        if (reasoning.stream().anyMatch(step -> step.startsWith("SOURCE_CONFLICT:"))) {
            builder.append("[CONFLICT_HINTS]\n")
                    .append("Vector and graph contexts disagree on the leading source. Prefer conservative answers and cite evidence.\n\n");
        }
        return builder.toString();
    }

    private void appendContexts(StringBuilder builder, String header, List<QAResult.RetrievedContext> contexts, String retrievalType) {
        List<QAResult.RetrievedContext> filtered = contexts.stream()
                .filter(context -> retrievalType.equals(context.getRetrievalType()))
                .toList();
        if (filtered.isEmpty()) {
            return;
        }
        builder.append("[").append(header).append("]\n");
        for (int i = 0; i < filtered.size(); i++) {
            QAResult.RetrievedContext context = filtered.get(i);
            builder.append(String.format("[Source %d: %s | Score: %.2f]%n%s%n%n",
                    i + 1, context.getSource(), context.getScore(), context.getContent()));
        }
    }

    private String generateAnswer(String question, String contextText) {
        return chatClient.prompt(new Prompt(List.of(
                new SystemMessage(ANSWER_PROMPT),
                new UserMessage("上下文：\n" + contextText + "\n\n问题: " + question)
        ))).call().content();
    }

    private double calcConfidence(List<QAResult.RetrievedContext> contexts) {
        if (contexts.isEmpty()) {
            return 0.0;
        }
        return Math.min(contexts.stream()
                .mapToDouble(QAResult.RetrievedContext::getScore)
                .average()
                .orElse(0.0), 1.0);
    }

    private QuestionMode detectQuestionMode(String question) {
        if (question == null || question.isBlank()) {
            return QuestionMode.DESCRIPTIVE;
        }
        String normalized = question.toLowerCase();
        if (normalized.contains("关系")
                || normalized.contains("关联")
                || normalized.contains("属于")
                || normalized.contains("依赖")
                || normalized.contains("合作")
                || normalized.contains("哪家公司")
                || normalized.contains("relationship")
                || normalized.contains("depends on")) {
            return QuestionMode.RELATIONSHIP;
        }
        return QuestionMode.DESCRIPTIVE;
    }

    private boolean hasHighConfidenceEntity(List<QAResult.RetrievedContext> graphContexts) {
        return graphContexts.stream().anyMatch(context ->
                "graph".equals(context.getRetrievalType()) && context.getScore() >= 0.8);
    }

    private boolean hasSourceConflict(List<QAResult.RetrievedContext> vector, List<QAResult.RetrievedContext> graph) {
        if (vector.isEmpty() || graph.isEmpty()) {
            return false;
        }
        Map<String, Object> vectorMetadata = vector.getFirst().getMetadata() == null ? java.util.Map.of() : vector.getFirst().getMetadata();
        Map<String, Object> graphMetadata = graph.getFirst().getMetadata() == null ? java.util.Map.of() : graph.getFirst().getMetadata();
        String vectorSource = String.valueOf(vectorMetadata.getOrDefault("source", vector.getFirst().getSource()));
        String graphSource = String.valueOf(graphMetadata.getOrDefault("source", graph.getFirst().getSource()));
        return !vectorSource.equalsIgnoreCase(graphSource);
    }
}
