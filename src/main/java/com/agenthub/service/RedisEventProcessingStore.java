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
import java.util.concurrent.TimeUnit;

@Service
public class RedisEventProcessingStore implements EventProcessingStore {

    private static final Logger log = LoggerFactory.getLogger(RedisEventProcessingStore.class);
    private static final String EVENT_KEY = "agent:event:%s";
    private static final String FILE_TS_KEY = "agent:file:%s:last-ts";
    private static final String FILE_VERSION_KEY = "agent:file:%s:last-version";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.event-ttl-hours:168}")
    private int eventTtlHours;

    public RedisEventProcessingStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<EventState> getEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        try {
            String payload = stringRedisTemplate.opsForValue().get(EVENT_KEY.formatted(eventId));
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(payload, EventState.class));
        } catch (Exception e) {
            log.warn("Event state read skipped for {}: {}", eventId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Instant> getLastProcessedTimestamp(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(FILE_TS_KEY.formatted(Integer.toHexString(filePath.hashCode())));
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(Instant.parse(value));
        } catch (Exception e) {
            log.warn("Last processed timestamp read skipped for {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Long> getLastProcessedVersion(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(FILE_VERSION_KEY.formatted(Integer.toHexString(filePath.hashCode())));
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(Long.parseLong(value));
        } catch (Exception e) {
            log.warn("Last processed version read skipped for {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void markReceived(String eventId, String filePath, String changeType, Long version, Instant timestamp, String payload) {
        writeState(new EventState(eventId, filePath, changeType, version, timestamp, ProcessingStatus.RECEIVED, null, null, payload));
    }

    @Override
    public void markProcessing(String eventId, String filePath, String changeType, Long version, Instant timestamp, String payload) {
        writeState(new EventState(eventId, filePath, changeType, version, timestamp, ProcessingStatus.PROCESSING, null, null, payload));
    }

    @Override
    public void markSucceeded(String eventId, String filePath, String changeType, Long version, Instant timestamp, String payload) {
        writeState(new EventState(eventId, filePath, changeType, version, timestamp, ProcessingStatus.SUCCEEDED, Instant.now(), null, payload));
        if (filePath != null && !filePath.isBlank()) {
            try {
                stringRedisTemplate.opsForValue().set(
                        FILE_TS_KEY.formatted(Integer.toHexString(filePath.hashCode())),
                        timestamp.toString(),
                        eventTtlHours,
                        TimeUnit.HOURS
                );
                if (version != null) {
                    stringRedisTemplate.opsForValue().set(
                            FILE_VERSION_KEY.formatted(Integer.toHexString(filePath.hashCode())),
                            String.valueOf(version),
                            eventTtlHours,
                            TimeUnit.HOURS
                    );
                }
            } catch (Exception e) {
                log.warn("Persist latest file timestamp skipped for {}: {}", filePath, e.getMessage());
            }
        }
    }

    @Override
    public void markFailed(String eventId, String filePath, String changeType, Long version, Instant timestamp, String failureReason, String payload) {
        writeState(new EventState(eventId, filePath, changeType, version, timestamp, ProcessingStatus.FAILED, Instant.now(), failureReason, payload));
    }

    private void writeState(EventState state) {
        if (state.eventId() == null || state.eventId().isBlank()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(state);
            stringRedisTemplate.opsForValue().set(EVENT_KEY.formatted(state.eventId()), payload, eventTtlHours, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event state", e);
        } catch (Exception e) {
            log.warn("Persist event state skipped for {}: {}", state.eventId(), e.getMessage());
        }
    }
}
