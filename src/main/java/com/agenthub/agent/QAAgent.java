package com.agenthub.agent;

import com.agenthub.memory.MemoryContext;
import com.agenthub.model.QAResult;
import com.agenthub.service.DashScopeChatService;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import com.agenthub.workflow.WorkflowDegradeReason;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QAAgent {

    private static final Pattern QUESTION_TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9_]{2,24}");
    private static final int MAX_GRAPH_CONTEXTS = 10;

    private static final String ANSWER_PROMPT = """
            You are an enterprise knowledge QA assistant.
            Answer strictly from the provided context and do not invent facts outside it.
            When the context is partial but still useful, provide the best grounded summary you can instead of refusing too early.
            Distinguish between "cannot answer at all" and "can answer partially from the current document fragments".
            Prefer this response structure:
            1. Direct conclusion in plain language.
            2. Key supporting evidence from the context.
            3. Missing information or uncertainty, only if needed.
            If the context truly does not support any meaningful answer, clearly say the information is insufficient.
            Keep technical terms in their original language when possible.
            """;

    private enum QuestionMode {
        RELATIONSHIP,
        DESCRIPTIVE
    }

    private final DashScopeChatService chatService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final MeterRegistry meterRegistry;
    private final int topK;

    public QAAgent(DashScopeChatService chatService,
                   VectorStoreService vectorStoreService,
                   KnowledgeGraphService knowledgeGraphService,
                   MeterRegistry meterRegistry,
                   @Value("${app.vector.top-k-default:5}") int topK) {
        this.chatService = chatService;
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
            meterRegistry.counter("agenthub.qa.vector.results", "questionMode", questionMode.name())
                    .increment(vectorResults.size());
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
            meterRegistry.counter("agenthub.qa.graph.results", "questionMode", questionMode.name())
                    .increment(graphResults.size());
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

        List<QAResult.RetrievedContext> merged = hybridRerank(
                question, vectorResults, graphResults, questionMode, reasoning);
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
                .intent(questionMode.name().toLowerCase(Locale.ROOT))
                .degraded(!degradeReasons.isEmpty())
                .degradeReasons(List.copyOf(degradeReasons))
                .contexts(topContexts)
                .reasoningSteps(reasoning)
                .build();
    }

    private List<QAResult.RetrievedContext> hybridRerank(
            String question,
            List<QAResult.RetrievedContext> vector,
            List<QAResult.RetrievedContext> graph,
            QuestionMode mode,
            List<String> reasoning) {
        List<QAResult.RetrievedContext> rankedVector = new ArrayList<>();
        List<QAResult.RetrievedContext> rankedGraph = new ArrayList<>();
        double vectorWeight = mode == QuestionMode.RELATIONSHIP ? 0.80 : 1.20;
        double graphWeight = mode == QuestionMode.RELATIONSHIP ? 1.40 : 0.90;
        reasoning.add("WEIGHTS: vector=" + vectorWeight + ", graph=" + graphWeight);
        Set<String> questionTokens = extractQuestionTokens(question);
        String normalizedQuestion = normalizeText(question);

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
            adjusted *= sourceTitleBoost(context, normalizedQuestion, questionTokens);
            context.setScore(adjusted);
            rankedVector.add(context);
        });
        graph.forEach(context -> {
            double adjusted = context.getScore() * graphWeight;
            if (mode == QuestionMode.DESCRIPTIVE && !highConfidenceGraph) {
                adjusted *= 0.95;
            }
            adjusted *= sourceTitleBoost(context, normalizedQuestion, questionTokens);
            context.setScore(adjusted);
            rankedGraph.add(context);
        });

        rankedVector.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));
        rankedGraph.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));

        List<QAResult.RetrievedContext> trimmedGraph = rankedGraph;
        if (trimmedGraph.size() > MAX_GRAPH_CONTEXTS) {
            reasoning.add("GRAPH_TRIMMED: " + trimmedGraph.size() + "->" + MAX_GRAPH_CONTEXTS);
            trimmedGraph = new ArrayList<>(trimmedGraph.subList(0, MAX_GRAPH_CONTEXTS));
        }
        if (!vector.isEmpty() && !graph.isEmpty()) {
            reasoning.add("CONTEXT_MIXED: vector+graph");
        }

        List<QAResult.RetrievedContext> all = new ArrayList<>(rankedVector.size() + trimmedGraph.size());
        all.addAll(rankedVector);
        all.addAll(trimmedGraph);

        int beforeCollapse = all.size();
        all = collapseDuplicateContexts(all);
        if (all.size() < beforeCollapse) {
            reasoning.add("DUPLICATE_CONTEXTS_COLLAPSED: " + beforeCollapse + "->" + all.size());
        }
        all.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));
        return all;
    }

    private String buildContextText(MemoryContext memoryContext,
                                    List<QAResult.RetrievedContext> contexts,
                                    List<String> reasoning) {
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

    private void appendContexts(StringBuilder builder,
                                String header,
                                List<QAResult.RetrievedContext> contexts,
                                String retrievalType) {
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
        String userPrompt = """
                Context:
                %s
                
                Question: %s
                
                Requirements:
                - First answer the question directly using the available evidence.
                - If the material supports only a partial answer, give that partial answer and explicitly say it is based on the current fragments.
                - Do not default to "information is insufficient" if the context supports a reasonable summary.
                - Only say the information is insufficient when the context does not support a meaningful answer at all.
                - When citing evidence, mention the specific concepts or sources that support the conclusion.
                """.formatted(contextText, question);
        return chatService.chat(ANSWER_PROMPT, userPrompt);
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
        String normalized = question.toLowerCase(Locale.ROOT);
        if (normalized.contains("关系")
                || normalized.contains("关联")
                || normalized.contains("属于")
                || normalized.contains("依赖")
                || normalized.contains("合作")
                || normalized.contains("哪个公司")
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

    private boolean hasSourceConflict(List<QAResult.RetrievedContext> vector,
                                      List<QAResult.RetrievedContext> graph) {
        if (vector.isEmpty() || graph.isEmpty()) {
            return false;
        }
        Map<String, Object> vectorMetadata = vector.getFirst().getMetadata() == null
                ? Map.of() : vector.getFirst().getMetadata();
        Map<String, Object> graphMetadata = graph.getFirst().getMetadata() == null
                ? Map.of() : graph.getFirst().getMetadata();
        String vectorSource = String.valueOf(vectorMetadata.getOrDefault(
                "source_key",
                vectorMetadata.getOrDefault("source", vector.getFirst().getSource())));
        String graphSource = String.valueOf(graphMetadata.getOrDefault(
                "source_key",
                graphMetadata.getOrDefault("source", graph.getFirst().getSource())));
        return !vectorSource.equalsIgnoreCase(graphSource);
    }

    private double sourceTitleBoost(QAResult.RetrievedContext context,
                                    String normalizedQuestion,
                                    Set<String> questionTokens) {
        String sourceTitle = extractSourceTitle(context);
        if (sourceTitle.isBlank()) {
            return 1.0;
        }

        String normalizedTitle = normalizeText(sourceTitle);
        if (normalizedTitle.isBlank()) {
            return 1.0;
        }
        if (normalizedQuestion.contains(normalizedTitle) || normalizedTitle.contains(normalizedQuestion)) {
            return 1.35;
        }

        int overlap = countTokenOverlap(questionTokens, extractQuestionTokens(sourceTitle));
        if (overlap >= 2) {
            return 1.22;
        }
        if (overlap == 1) {
            return 1.10;
        }
        return 1.0;
    }

    private List<QAResult.RetrievedContext> collapseDuplicateContexts(List<QAResult.RetrievedContext> contexts) {
        Map<String, QAResult.RetrievedContext> bestByKey = new LinkedHashMap<>();
        for (QAResult.RetrievedContext context : contexts) {
            String key = buildContextDedupKey(context);
            QAResult.RetrievedContext existing = bestByKey.get(key);
            if (existing == null || context.getScore() > existing.getScore()) {
                bestByKey.put(key, context);
            }
        }
        return new ArrayList<>(bestByKey.values());
    }

    private String buildContextDedupKey(QAResult.RetrievedContext context) {
        String sourceKey = extractSourceKey(context);
        String title = extractSourceTitle(context);
        String content = context.getContent() == null ? "" : context.getContent();
        String normalizedContent = normalizeText(content);
        if (normalizedContent.length() > 160) {
            normalizedContent = normalizedContent.substring(0, 160);
        }
        String identity = sourceKey.isBlank() ? normalizeText(title) : normalizeText(sourceKey);
        return identity + "::" + normalizedContent;
    }

    private String extractSourceTitle(QAResult.RetrievedContext context) {
        if (context.getMetadata() != null) {
            String title = String.valueOf(context.getMetadata().getOrDefault("title", ""));
            if (!title.isBlank()) {
                return title;
            }
        }
        String source = context.getMetadata() == null
                ? context.getSource()
                : String.valueOf(context.getMetadata().getOrDefault("source", context.getSource()));
        if (source == null || source.isBlank()) {
            return "";
        }
        String normalized = source.replace('\\', '/');
        String fileName = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        int extensionIndex = fileName.lastIndexOf('.');
        return extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
    }

    private String extractSourceKey(QAResult.RetrievedContext context) {
        if (context.getMetadata() == null) {
            return "";
        }
        return String.valueOf(context.getMetadata().getOrDefault("source_key", ""));
    }

    private Set<String> extractQuestionTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = QUESTION_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(normalizeText(matcher.group()));
        }
        return tokens;
    }

    private int countTokenOverlap(Set<String> left, Set<String> right) {
        int overlap = 0;
        for (String token : left) {
            if (right.contains(token)) {
                overlap++;
            }
        }
        return overlap;
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replace("？", "")
                .replace("?", "")
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }
}
