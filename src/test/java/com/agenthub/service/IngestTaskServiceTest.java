package com.agenthub.service;

import com.agenthub.workflow.IngestProgress;
import com.agenthub.workflow.IngestProgressListener;
import com.agenthub.workflow.IngestWorkflowService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestTaskServiceTest {

    @Test
    void shouldPersistProgressAndFinalResultForAsyncTask() throws Exception {
        IngestWorkflowService workflowService = mock(IngestWorkflowService.class);
        InMemoryIngestTaskStore taskStore = new InMemoryIngestTaskStore();
        IngestTaskService taskService = new IngestTaskService(workflowService, taskStore, new DirectExecutorService());

        when(workflowService.ingest(eq("agent.md"), eq("E:/docs/agent.md"), any(IngestProgressListener.class)))
                .thenAnswer(invocation -> {
                    IngestProgressListener listener = invocation.getArgument(2);
                    listener.onProgress(new IngestProgress("PARSED", 4, 0, false, List.of()));
                    listener.onProgress(new IngestProgress("EXTRACTING", 4, 2, true, List.of("GRAPH_UNAVAILABLE")));
                    return Map.of("workflowState", "COMPLETED", "chunks", 4);
                });

        IngestTaskSnapshot queued = taskService.submit("agent.md", "E:/docs/agent.md");
        IngestTaskSnapshot completed = taskService.getTask(queued.taskId());

        assertThat(completed.status()).isEqualTo(IngestTaskStatus.SUCCEEDED);
        assertThat(completed.currentStage()).isEqualTo("COMPLETED");
        assertThat(completed.startedAt()).isNotNull();
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.chunksTotal()).isEqualTo(4);
        assertThat(completed.chunksProcessed()).isEqualTo(2);
        assertThat(completed.degraded()).isTrue();
        assertThat(completed.degradeReasons()).containsExactly("GRAPH_UNAVAILABLE");
        assertThat(completed.result()).containsEntry("workflowState", "COMPLETED");
    }

    @Test
    void shouldPersistFailureReasonWhenAsyncTaskFails() throws Exception {
        IngestWorkflowService workflowService = mock(IngestWorkflowService.class);
        InMemoryIngestTaskStore taskStore = new InMemoryIngestTaskStore();
        IngestTaskService taskService = new IngestTaskService(workflowService, taskStore, new DirectExecutorService());

        when(workflowService.ingest(eq("agent.md"), eq("E:/docs/agent.md"), any(IngestProgressListener.class)))
                .thenThrow(new IllegalStateException("milvus down"));

        IngestTaskSnapshot queued = taskService.submit("agent.md", "E:/docs/agent.md");
        IngestTaskSnapshot failed = taskService.getTask(queued.taskId());

        assertThat(failed.status()).isEqualTo(IngestTaskStatus.FAILED);
        assertThat(failed.failureReason()).contains("milvus down");
        assertThat(failed.completedAt()).isNotNull();
    }

    @Test
    void shouldMarkDanglingProcessingTasksAsFailedOnRecovery() {
        IngestWorkflowService workflowService = mock(IngestWorkflowService.class);
        InMemoryIngestTaskStore taskStore = new InMemoryIngestTaskStore();
        IngestTaskService taskService = new IngestTaskService(workflowService, taskStore, new DirectExecutorService());

        taskStore.save(new IngestTaskSnapshot(
                "ingest-1",
                "agent.md",
                "E:/docs/agent.md",
                IngestTaskStatus.PROCESSING,
                "EXTRACTING",
                Instant.parse("2026-04-26T10:00:00Z"),
                Instant.parse("2026-04-26T10:00:01Z"),
                null,
                null,
                10,
                3,
                false,
                List.of(),
                null
        ));

        taskService.recoverDanglingTasks();

        IngestTaskSnapshot recovered = taskService.getTask("ingest-1");
        assertThat(recovered.status()).isEqualTo(IngestTaskStatus.FAILED);
        assertThat(recovered.failureReason()).isEqualTo("Application restarted before task completion");
        assertThat(recovered.completedAt()).isNotNull();
    }

    private static final class InMemoryIngestTaskStore implements IngestTaskStore {

        private final Map<String, IngestTaskSnapshot> tasks = new LinkedHashMap<>();
        private final List<IngestTaskSnapshot> savedSnapshots = new ArrayList<>();

        @Override
        public Optional<IngestTaskSnapshot> getTask(String taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public void save(IngestTaskSnapshot snapshot) {
            tasks.put(snapshot.taskId(), snapshot);
            savedSnapshots.add(snapshot);
        }

        @Override
        public int markProcessingTasksAsFailed(String failureReason) {
            int updated = 0;
            for (Map.Entry<String, IngestTaskSnapshot> entry : new ArrayList<>(tasks.entrySet())) {
                IngestTaskSnapshot snapshot = entry.getValue();
                if (snapshot.status() == IngestTaskStatus.PROCESSING) {
                    IngestTaskSnapshot failed = snapshot.failed(failureReason, Instant.now());
                    tasks.put(entry.getKey(), failed);
                    savedSnapshots.add(failed);
                    updated++;
                }
            }
            return updated;
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
