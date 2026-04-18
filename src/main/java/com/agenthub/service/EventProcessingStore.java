package com.agenthub.service;

import java.time.Instant;
import java.util.Optional;

public interface EventProcessingStore {

    enum ProcessingStatus {
        RECEIVED,
        PROCESSING,
        SUCCEEDED,
        FAILED
    }

    record EventState(
            String eventId,
            String filePath,
            String changeType,
            Long version,
            Instant timestamp,
            ProcessingStatus status,
            Instant processedAt,
            String failureReason,
            String payload
    ) {
    }

    Optional<EventState> getEvent(String eventId);

    Optional<Instant> getLastProcessedTimestamp(String filePath);

    Optional<Long> getLastProcessedVersion(String filePath);

    void markReceived(String eventId, String filePath, String changeType, Long version, Instant timestamp, String payload);

    void markProcessing(String eventId, String filePath, String changeType, Long version, Instant timestamp, String payload);

    void markSucceeded(String eventId, String filePath, String changeType, Long version, Instant timestamp, String payload);

    void markFailed(String eventId, String filePath, String changeType, Long version, Instant timestamp, String failureReason, String payload);
}
