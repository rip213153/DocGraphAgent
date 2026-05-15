package com.agenthub.agent;

import com.agenthub.memory.MemoryContext;
import com.agenthub.model.QAResult;
import com.agenthub.service.DashScopeChatService;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import com.agenthub.workflow.WorkflowDegradeReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QAAgentTest {

    @Mock
    private DashScopeChatService chatService;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private SimpleMeterRegistry meterRegistry;
    private QAAgent qaAgent;

    @BeforeEach
    void setUp() {
        when(chatService.chat(anyString(), anyString())).thenReturn("answer");
        meterRegistry = new SimpleMeterRegistry();
        qaAgent = new QAAgent(chatService, vectorStoreService, knowledgeGraphService, meterRegistry, 5);
    }

    @Test
    void shouldPreferGraphResultsForRelationshipQuestions() {
        String question = "What relationship exists between A and B?";
        when(vectorStoreService.search(question, 5)).thenReturn(List.of(
                context("vector", 0.9, "vector context", "doc-vector")
        ));
        when(knowledgeGraphService.searchByQuestion(question)).thenReturn(List.of(
                context("graph", 0.8, "graph context", "doc-graph")
        ));

        QAResult result = qaAgent.answer(question, MemoryContext.builder().build());

        assertThat(result.getContexts()).isNotEmpty();
        assertThat(result.getContexts().getFirst().getRetrievalType()).isEqualTo("graph");
        assertThat(result.getReasoningSteps()).anyMatch(step -> step.contains("QUESTION_MODE: RELATIONSHIP"));
        assertThat(result.getReasoningSteps()).anyMatch(step -> step.contains("SOURCE_CONFLICT"));
    }

    @Test
    void shouldSplitMemoryVectorAndGraphSectionsInPrompt() {
        when(vectorStoreService.search("What changed?", 5)).thenReturn(List.of(
                context("vector", 0.9, "vector context", "doc-vector")
        ));
        when(knowledgeGraphService.searchByQuestion("What changed?")).thenReturn(List.of(
                context("graph", 0.8, "graph context", "doc-graph")
        ));

        qaAgent.answer("What changed?", MemoryContext.builder().shortTermContext("user: previous").build());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService).chat(anyString(), promptCaptor.capture());
        String promptString = promptCaptor.getValue();
        assertThat(promptString).contains("[MEMORY]");
        assertThat(promptString).contains("[VECTOR_CONTEXTS]");
        assertThat(promptString).contains("[GRAPH_CONTEXTS]");
        assertThat(promptString).contains("[CONFLICT_HINTS]");
    }

    @Test
    void shouldPreferVectorResultsForDescriptiveQuestions() {
        String question = "Describe the update flow";
        when(vectorStoreService.search(question, 5)).thenReturn(List.of(
                context("vector", 0.85, "vector context", "same-source")
        ));
        when(knowledgeGraphService.searchByQuestion(question)).thenReturn(List.of(
                context("graph", 0.75, "graph context", "same-source")
        ));

        QAResult result = qaAgent.answer(question, MemoryContext.builder().build());

        assertThat(result.getContexts().getFirst().getRetrievalType()).isEqualTo("vector");
        assertThat(result.getReasoningSteps()).anyMatch(step -> step.contains("QUESTION_MODE: DESCRIPTIVE"));
    }

    @Test
    void shouldDetectChineseRelationshipQuestions() {
        String question = "\u8C01\u4E0E\u8C01\u5B58\u5728\u4F9D\u8D56\u5173\u7CFB\uFF1F";
        when(vectorStoreService.search(question, 5)).thenReturn(List.of(
                context("vector", 0.82, "vector context", "doc-vector")
        ));
        when(knowledgeGraphService.searchByQuestion(question)).thenReturn(List.of(
                context("graph", 0.78, "graph context", "doc-graph")
        ));

        QAResult result = qaAgent.answer(question, MemoryContext.builder().build());

        assertThat(result.getContexts()).isNotEmpty();
        assertThat(result.getContexts().getFirst().getRetrievalType()).isEqualTo("graph");
        assertThat(result.getReasoningSteps()).anyMatch(step -> step.contains("QUESTION_MODE: RELATIONSHIP"));
    }

    @Test
    void shouldPreferSourceTitleThatMatchesQuestion() {
        String question = "索引设计原则？";
        when(vectorStoreService.search(question, 5)).thenReturn(List.of(
                context("vector", 0.84, "来自旧文档的内容", "C:/tmp/最左匹配原则和索引失效.md"),
                context("vector", 0.80, "来自新文档的内容", "C:/tmp/索引设计原则.md")
        ));
        when(knowledgeGraphService.searchByQuestion(question)).thenReturn(List.of());

        QAResult result = qaAgent.answer(question, MemoryContext.builder().build());

        assertThat(result.getContexts()).isNotEmpty();
        assertThat(result.getContexts().getFirst().getMetadata()).containsEntry("source", "C:/tmp/索引设计原则.md");
    }

    @Test
    void shouldCollapseDuplicateContextsFromRepeatedUploads() {
        String question = "AQS是什么？";
        when(vectorStoreService.search(question, 5)).thenReturn(List.of(
                context("vector", 0.82, "AQS的三大核心属性，state，同步队列。", "C:/tmp/uploads1/AQS.md"),
                context("vector", 0.81, "AQS的三大核心属性，state，同步队列。", "C:/tmp/uploads2/AQS.md")
        ));
        when(knowledgeGraphService.searchByQuestion(question)).thenReturn(List.of());

        QAResult result = qaAgent.answer(question, MemoryContext.builder().build());

        assertThat(result.getContexts()).hasSize(1);
        assertThat(result.getReasoningSteps()).anyMatch(step -> step.contains("DUPLICATE_CONTEXTS_COLLAPSED"));
    }

    @Test
    void shouldExposeStructuredDegradeReasonWhenGraphFallbackFails() {
        String question = "Describe the update flow";
        when(vectorStoreService.search(question, 5)).thenReturn(List.of(
                context("vector", 0.85, "vector context", "same-source")
        ));
        when(knowledgeGraphService.searchByQuestion(question)).thenThrow(new IllegalStateException("neo4j down"));

        QAResult result = qaAgent.answer(question, MemoryContext.builder().build());

        assertThat(result.isDegraded()).isTrue();
        assertThat(result.getDegradeReasons()).contains(WorkflowDegradeReason.GRAPH_UNAVAILABLE.name());
    }

    @Test
    void shouldRecordMetricsForSuccessfulAnswerFlow() {
        String question = "Describe the update flow";
        when(vectorStoreService.search(question, 5)).thenReturn(List.of(
                context("vector", 0.90, "vector context 1", "doc-a"),
                context("vector", 0.88, "vector context 2", "doc-b")
        ));
        when(knowledgeGraphService.searchByQuestion(question)).thenReturn(List.of(
                context("graph", 0.77, "graph context", "doc-c")
        ));

        QAResult result = qaAgent.answer(question, MemoryContext.builder().build());

        assertThat(meterRegistry.get("agenthub.qa.vector.results")
                .tag("questionMode", "DESCRIPTIVE")
                .counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("agenthub.qa.graph.results")
                .tag("questionMode", "DESCRIPTIVE")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agenthub.qa.top.contexts")
                .counter().count()).isEqualTo(result.getContexts().size());
    }

    @Test
    void shouldRecordDegradedMetricWhenVectorRetrievalFails() {
        String question = "What changed?";
        when(vectorStoreService.search(question, 5)).thenThrow(new IllegalStateException("milvus down"));
        when(knowledgeGraphService.searchByQuestion(question)).thenReturn(List.of(
                context("graph", 0.80, "graph context", "doc-graph")
        ));

        QAResult result = qaAgent.answer(question, MemoryContext.builder().build());

        assertThat(result.isDegraded()).isTrue();
        assertThat(result.getDegradeReasons()).contains(WorkflowDegradeReason.VECTOR_UNAVAILABLE.name());
        assertThat(meterRegistry.get("agenthub.qa.degraded")
                .tag("path", "vector")
                .counter().count()).isEqualTo(1.0);
    }

    private QAResult.RetrievedContext context(String retrievalType, double score, String content, String source) {
        String normalizedSource = source == null ? "" : source.replace('\\', '/');
        int slashIndex = normalizedSource.lastIndexOf('/');
        String fileName = slashIndex >= 0 ? normalizedSource.substring(slashIndex + 1) : normalizedSource;
        int extensionIndex = fileName.lastIndexOf('.');
        String title = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        return QAResult.RetrievedContext.builder()
                .retrievalType(retrievalType)
                .score(score)
                .content(content)
                .source(retrievalType)
                .metadata(Map.of(
                        "source", source,
                        "title", title))
                .build();
    }
}
