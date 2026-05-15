package com.agenthub.service;

import java.util.Optional;

public interface IngestTaskStore {

    Optional<IngestTaskSnapshot> getTask(String taskId);

    void save(IngestTaskSnapshot snapshot);

    int markProcessingTasksAsFailed(String failureReason);
}
