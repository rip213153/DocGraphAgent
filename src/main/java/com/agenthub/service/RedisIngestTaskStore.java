package com.agenthub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RedisIngestTaskStore implements IngestTaskStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIngestTaskStore.class);
    private static final String TASK_KEY = "agent:ingest-task:%s";
    private static final String TASK_PATTERN = "agent:ingest-task:*";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.upload.task-ttl-hours:168}")
    private int taskTtlHours;

    public RedisIngestTaskStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<IngestTaskSnapshot> getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        try {
            String payload = stringRedisTemplate.opsForValue().get(key(taskId));
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(payload, IngestTaskSnapshot.class));
        } catch (Exception e) {
            log.warn("Ingest task read skipped for {}: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(IngestTaskSnapshot snapshot) {
        if (snapshot == null || snapshot.taskId() == null || snapshot.taskId().isBlank()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(snapshot);
            stringRedisTemplate.opsForValue().set(key(snapshot.taskId()), payload, taskTtlHours, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ingest task snapshot", e);
        } catch (Exception e) {
            log.warn("Persist ingest task skipped for {}: {}", snapshot.taskId(), e.getMessage());
        }
    }

    @Override
    public int markProcessingTasksAsFailed(String failureReason) {
        try {
            Set<String> keys = stringRedisTemplate.keys(TASK_PATTERN);
            if (keys == null || keys.isEmpty()) {
                return 0;
            }

            int updated = 0;
            for (String redisKey : keys) {
                String payload = stringRedisTemplate.opsForValue().get(redisKey);
                if (payload == null || payload.isBlank()) {
                    continue;
                }
                IngestTaskSnapshot snapshot = objectMapper.readValue(payload, IngestTaskSnapshot.class);
                if (snapshot.status() != IngestTaskStatus.PROCESSING) {
                    continue;
                }
                save(snapshot.failed(failureReason, Instant.now()));
                updated++;
            }
            return updated;
        } catch (Exception e) {
            log.warn("Failed to recover processing ingest tasks: {}", e.getMessage());
            return 0;
        }
    }

    private String key(String taskId) {
        return TASK_KEY.formatted(taskId);
    }
}
