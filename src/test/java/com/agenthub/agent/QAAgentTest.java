package com.agenthub.agent;

import com.agenthub.memory.MemoryContext;
import com.agenthub.model.QAResult;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QAAgentTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private QAAgent qaAgent;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("answer");
        qaAgent = new QAAgent(chatClientBuilder, vectorStoreService, knowledgeGraphService, new SimpleMeterRegistry(), 5);
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

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).prompt(promptCaptor.capture());
        String promptString = String.valueOf(promptCaptor.getValue());
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

    private QAResult.RetrievedContext context(String retrievalType, double score, String content, String source) {
        return QAResult.RetrievedContext.builder()
                .retrievalType(retrievalType)
                .score(score)
                .content(content)
                .source(retrievalType)
                .metadata(Map.of("source", source))
                .build();
    }
}
