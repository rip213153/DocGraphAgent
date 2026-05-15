package com.agenthub.service;

import com.agenthub.workflow.IngestProgress;
import com.agenthub.workflow.IngestProgressListener;
import com.agenthub.workflow.IngestWorkflowService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
public class IngestTaskService {

    private static final Logger log = LoggerFactory.getLogger(IngestTaskService.class);

    private final IngestWorkflowService ingestWorkflowService;
    private final ExecutorService workflowExecutor;
    private final IngestTaskStore ingestTaskStore;

    public IngestTaskService(IngestWorkflowService ingestWorkflowService,
                             IngestTaskStore ingestTaskStore,
                             @Qualifier("workflowExecutor") ExecutorService workflowExecutor) {
        this.ingestWorkflowService = ingestWorkflowService;
        this.ingestTaskStore = ingestTaskStore;
        this.workflowExecutor = workflowExecutor;
    }

    @PostConstruct
    void recoverDanglingTasks() {
        int updated = ingestTaskStore.markProcessingTasksAsFailed("Application restarted before task completion");
        if (updated > 0) {
            log.warn("Recovered {} dangling ingest tasks from a previous process", updated);
        }
    }

    public IngestTaskSnapshot submit(String fileName, String absolutePath) {
        String taskId = "ingest-" + UUID.randomUUID();
        Instant createdAt = Instant.now();
        IngestTaskSnapshot queued = new IngestTaskSnapshot(
                taskId,
                fileName,
                absolutePath,
                IngestTaskStatus.QUEUED,
                "QUEUED",
                createdAt,
                null,
                null,
                null,
                0,
                0,
                false,
                java.util.List.of(),
                null
        );
        ingestTaskStore.save(queued);

        CompletableFuture.runAsync(() -> processTask(taskId, fileName, absolutePath), workflowExecutor);
        return queued;
    }

    public IngestTaskSnapshot getTask(String taskId) {
        return ingestTaskStore.getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("ingest task not found: " + taskId));
    }

    private void processTask(String taskId, String fileName, String absolutePath) {
        IngestTaskSnapshot queued = getTask(taskId);
        IngestTaskSnapshot processing = queued.withStatus(IngestTaskStatus.PROCESSING, "PROCESSING", Instant.now());
        ingestTaskStore.save(processing);
        log.info("Async ingest task {} started for {}", taskId, fileName);

        try {
            Map<String, Object> result = ingestWorkflowService.ingest(fileName, absolutePath, progress ->
                    updateProgress(taskId, progress));
            IngestTaskSnapshot succeeded = getTask(taskId).succeeded(result, Instant.now());
            ingestTaskStore.save(succeeded);
            log.info("Async ingest task {} completed for {}", taskId, fileName);
        } catch (Exception e) {
            IngestTaskSnapshot failed = getTask(taskId).failed(e.getMessage(), Instant.now());
            ingestTaskStore.save(failed);
            log.error("Async ingest task {} failed for {}: {}", taskId, fileName, e.getMessage(), e);
        } finally {
            cleanupTempFile(absolutePath);
        }
    }

    private void updateProgress(String taskId, IngestProgress progress) {
        IngestTaskSnapshot current = getTask(taskId);
        IngestTaskSnapshot updated = current.withProgress(
                progress.currentStage(),
                progress.chunksTotal(),
                progress.chunksProcessed(),
                progress.degraded(),
                progress.degradeReasons()
        );
        ingestTaskStore.save(updated);
    }

    private void cleanupTempFile(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return;
        }
        try {
            Path filePath = Path.of(absolutePath);
            Files.deleteIfExists(filePath);
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException e) {
            log.warn("Temporary upload cleanup skipped for {}: {}", absolutePath, e.getMessage());
        }
    }

}
