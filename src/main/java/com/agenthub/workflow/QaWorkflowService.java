package com.agenthub.workflow;

import com.agenthub.agent.QAAgent;
import com.agenthub.memory.MemoryContext;
import com.agenthub.memory.MemoryManager;
import com.agenthub.model.QAResult;
import com.agenthub.model.QaAskRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QaWorkflowService {

    private final QAAgent qaAgent;
    private final MemoryManager memoryManager;
    private final WorkflowStepRunner stepRunner;

    public QaWorkflowService(QAAgent qaAgent,
                             MemoryManager memoryManager,
                             WorkflowStepRunner stepRunner) {
        this.qaAgent = qaAgent;
        this.memoryManager = memoryManager;
        this.stepRunner = stepRunner;
    }

    public QAResult ask(QaAskRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        WorkflowExecutionContext<QaState> context =
                new WorkflowExecutionContext<>(QaState.QUESTION_RECEIVED, allowedTransitions());
        List<String> workflowReasoning = new ArrayList<>();
        workflowReasoning.add("QUESTION_RECEIVED: question accepted");

        String sessionId = blankToNull(request.getSessionId());
        MemoryContext memoryContext = MemoryContext.builder().build();

        try {
            if (sessionId != null) {
                try {
                    MemoryManager.MemoryLoadResult loadResult = stepRunner.supply(
                            "memory-load",
                            context,
                            () -> memoryManager.loadShortTermContextResult(sessionId)
                    );
                    memoryContext = loadResult.context();
                    if (!loadResult.available()) {
                        context.markDegraded(WorkflowDegradeReason.MEMORY_UNAVAILABLE.name());
                        workflowReasoning.add("MEMORY_LOADED: degraded to empty memory context");
                    } else {
                        workflowReasoning.add("MEMORY_LOADED: short-term memory loaded");
                    }
                } catch (Exception e) {
                    context.markDegraded(WorkflowDegradeReason.MEMORY_UNAVAILABLE.name());
                    workflowReasoning.add("MEMORY_LOADED: degraded because memory loading failed");
                }
            } else {
                workflowReasoning.add("MEMORY_LOADED: no session id, skip memory loading");
            }
            context.transitionTo(QaState.MEMORY_LOADED);

            MemoryContext finalMemoryContext = memoryContext;
            QAResult result = stepRunner.supply(
                    "qa-answer",
                    context,
                    () -> qaAgent.answer(request.getQuestion(), finalMemoryContext)
            );
            context.transitionTo(QaState.RETRIEVAL_COMPLETED);
            workflowReasoning.add("RETRIEVAL_COMPLETED: retrieval and reranking finished");

            context.transitionTo(QaState.ANSWER_GENERATED);
            workflowReasoning.add("ANSWER_GENERATED: answer generated");

            if (sessionId != null) {
                memoryManager.appendUserMessage(sessionId, request.getQuestion());
                memoryManager.appendAssistantMessage(sessionId, result.getAnswer());
            }

            context.transitionTo(QaState.COMPLETED);
            context.finish();
            workflowReasoning.add("COMPLETED: workflow finished");

            mergeWorkflowMetadata(result, context, workflowReasoning);
            return result;
        } catch (Exception ex) {
            context.fail(QaState.FAILED);
            workflowReasoning.add("FAILED: " + ex.getClass().getSimpleName());
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("QA workflow failed", ex);
        }
    }

    private void mergeWorkflowMetadata(QAResult result,
                                       WorkflowExecutionContext<QaState> context,
                                       List<String> workflowReasoning) {
        Set<String> degradeReasons = new LinkedHashSet<>(context.degradeReasons());
        if (result.getDegradeReasons() != null) {
            degradeReasons.addAll(result.getDegradeReasons());
        }

        List<String> mergedReasoning = new ArrayList<>(workflowReasoning);
        if (result.getReasoningSteps() != null) {
            mergedReasoning.addAll(result.getReasoningSteps());
        }

        result.setDegraded(context.degraded() || result.isDegraded());
        result.setDegradeReasons(List.copyOf(degradeReasons));
        result.setWorkflowState(context.currentState().name());
        result.setReasoningSteps(mergedReasoning);
    }

    private Map<QaState, Set<QaState>> allowedTransitions() {
        return Map.of(
                QaState.QUESTION_RECEIVED, Set.of(QaState.MEMORY_LOADED),
                QaState.MEMORY_LOADED, Set.of(QaState.RETRIEVAL_COMPLETED),
                QaState.RETRIEVAL_COMPLETED, Set.of(QaState.ANSWER_GENERATED),
                QaState.ANSWER_GENERATED, Set.of(QaState.COMPLETED)
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private enum QaState {
        QUESTION_RECEIVED,
        MEMORY_LOADED,
        RETRIEVAL_COMPLETED,
        ANSWER_GENERATED,
        COMPLETED,
        FAILED
    }
}
