package com.agenthub.workflow;

import com.agenthub.agent.QAAgent;
import com.agenthub.memory.MemoryContext;
import com.agenthub.memory.MemoryManager;
import com.agenthub.model.QAResult;
import com.agenthub.model.QaAskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QaWorkflowServiceTest {

    @Mock
    private QAAgent qaAgent;

    @Mock
    private MemoryManager memoryManager;

    private QaWorkflowService qaWorkflowService;

    @BeforeEach
    void setUp() {
        qaWorkflowService = new QaWorkflowService(qaAgent, memoryManager, new WorkflowStepRunner(1, 0));
    }

    @Test
    void shouldLoadMemoryBeforePersistingCurrentTurn() {
        QaAskRequest request = new QaAskRequest("session-1", null, "What changed?");
        MemoryContext memoryContext = MemoryContext.builder()
                .shortTermContext("user: previous question")
                .build();
        QAResult qaResult = QAResult.builder()
                .question(request.getQuestion())
                .answer("Here is the answer")
                .contexts(List.of())
                .reasoningSteps(List.of("ANSWER_GENERATED"))
                .build();

        when(memoryManager.loadShortTermContextResult("session-1"))
                .thenReturn(new MemoryManager.MemoryLoadResult(memoryContext, true));
        when(qaAgent.answer(request.getQuestion(), memoryContext)).thenReturn(qaResult);

        QAResult result = qaWorkflowService.ask(request);

        InOrder inOrder = inOrder(memoryManager, qaAgent);
        inOrder.verify(memoryManager).loadShortTermContextResult("session-1");
        inOrder.verify(qaAgent).answer(request.getQuestion(), memoryContext);
        inOrder.verify(memoryManager).appendUserMessage("session-1", request.getQuestion());
        inOrder.verify(memoryManager).appendAssistantMessage("session-1", qaResult.getAnswer());

        assertThat(result.getWorkflowState()).isEqualTo("COMPLETED");
        assertThat(result.isDegraded()).isFalse();
        assertThat(result.getReasoningSteps()).contains("COMPLETED: workflow finished");
    }

    @Test
    void shouldMarkResultDegradedWhenMemoryUnavailable() {
        QaAskRequest request = new QaAskRequest("session-1", null, "What changed?");
        QAResult qaResult = QAResult.builder()
                .question(request.getQuestion())
                .answer("Here is the answer")
                .contexts(List.of())
                .reasoningSteps(List.of("ANSWER_GENERATED"))
                .build();

        when(memoryManager.loadShortTermContextResult("session-1"))
                .thenReturn(new MemoryManager.MemoryLoadResult(MemoryContext.builder().build(), false));
        when(qaAgent.answer(eq(request.getQuestion()), any(MemoryContext.class))).thenReturn(qaResult);

        QAResult result = qaWorkflowService.ask(request);

        assertThat(result.isDegraded()).isTrue();
        assertThat(result.getDegradeReasons()).contains(WorkflowDegradeReason.MEMORY_UNAVAILABLE.name());
        assertThat(result.getWorkflowState()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldSkipMemoryPersistenceWhenSessionIdMissing() {
        QaAskRequest request = new QaAskRequest(null, null, "What changed?");
        QAResult qaResult = QAResult.builder()
                .question(request.getQuestion())
                .answer("Here is the answer")
                .contexts(List.of())
                .reasoningSteps(List.of())
                .build();

        when(qaAgent.answer(eq(request.getQuestion()), any(MemoryContext.class))).thenReturn(qaResult);

        qaWorkflowService.ask(request);

        verifyNoInteractions(memoryManager);
    }
}
