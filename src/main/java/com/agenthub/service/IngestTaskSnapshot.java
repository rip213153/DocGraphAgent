package com.agenthub.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record IngestTaskSnapshot(
        String taskId,
        String fileName,
        String filePath,
        IngestTaskStatus status,
        String currentStage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String failureReason,
        Integer chunksTotal,
        Integer chunksProcessed,
        boolean degraded,
        List<String> degradeReasons,
        Map<String, Object> result
) {

    public IngestTaskSnapshot {
        degradeReasons = degradeReasons == null ? List.of() : List.copyOf(degradeReasons);
        result = result == null ? null : Map.copyOf(result);
    }

    public IngestTaskSnapshot withStatus(IngestTaskStatus nextStatus, String nextStage, Instant nextStartedAt) {
        return new IngestTaskSnapshot(
                taskId,
                fileName,
                filePath,
                nextStatus,
                nextStage,
                createdAt,
                nextStartedAt,
                completedAt,
                failureReason,
                chunksTotal,
                chunksProcessed,
                degraded,
                degradeReasons,
                result
        );
    }

    public IngestTaskSnapshot withProgress(String nextStage,
                                           Integer nextChunksTotal,
                                           Integer nextChunksProcessed,
                                           boolean nextDegraded,
                                           List<String> nextDegradeReasons) {
        return new IngestTaskSnapshot(
                taskId,
                fileName,
                filePath,
                status,
                nextStage,
                createdAt,
                startedAt,
                completedAt,
                failureReason,
                nextChunksTotal,
                nextChunksProcessed,
                nextDegraded,
                nextDegradeReasons,
                result
        );
    }

    public IngestTaskSnapshot succeeded(Map<String, Object> nextResult, Instant nextCompletedAt) {
        return new IngestTaskSnapshot(
                taskId,
                fileName,
                filePath,
                IngestTaskStatus.SUCCEEDED,
                "COMPLETED",
                createdAt,
                startedAt,
                nextCompletedAt,
                null,
                chunksTotal,
                chunksProcessed,
                degraded,
                degradeReasons,
                nextResult
        );
    }

    public IngestTaskSnapshot failed(String nextFailureReason, Instant nextCompletedAt) {
        return new IngestTaskSnapshot(
                taskId,
                fileName,
                filePath,
                IngestTaskStatus.FAILED,
                currentStage == null ? "FAILED" : currentStage,
                createdAt,
                startedAt,
                nextCompletedAt,
                nextFailureReason,
                chunksTotal,
                chunksProcessed,
                degraded,
                degradeReasons,
                result
        );
    }

    public Map<String, Object> toResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", taskId);
        response.put("fileName", fileName);
        response.put("status", status.name());
        response.put("currentStage", currentStage);
        response.put("createdAt", createdAt);
        response.put("startedAt", startedAt);
        response.put("completedAt", completedAt);
        response.put("failureReason", failureReason);
        response.put("chunksTotal", chunksTotal);
        response.put("chunksProcessed", chunksProcessed);
        response.put("degraded", degraded);
        response.put("degradeReasons", degradeReasons);
        response.put("result", result);
        return response;
    }
}
