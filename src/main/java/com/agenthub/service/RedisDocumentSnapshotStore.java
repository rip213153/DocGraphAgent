package com.agenthub.service;

import com.agenthub.model.DocumentChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class RedisDocumentSnapshotStore implements DocumentSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(RedisDocumentSnapshotStore.class);
    private static final String SNAPSHOT_KEY = "agent:doc:%s:chunks";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.update.snapshot-ttl-hours:168}")
    private int snapshotTtlHours;

    public RedisDocumentSnapshotStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }
    //
    @Override
    public void saveChunks(String docId, List<DocumentChunk> chunks) {
        String key = key(docId);
        try {
            String payload = objectMapper.writeValueAsString(chunks);
            stringRedisTemplate.opsForValue().set(key, payload, snapshotTtlHours, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize document chunks snapshot", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist document chunks snapshot for " + docId, e);
        }
    }

    @Override
    public List<DocumentChunk> getChunks(String docId) {
        try {
            String payload = stringRedisTemplate.opsForValue().get(key(docId));
            if (payload == null || payload.isBlank()) {
                return List.of();
            }
            return objectMapper.readerForListOf(DocumentChunk.class).readValue(payload);
        } catch (Exception e) {
            log.warn("Snapshot read skipped for {}: {}", docId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void delete(String docId) {
        try {
            stringRedisTemplate.delete(key(docId));
        } catch (Exception e) {
            log.warn("Snapshot delete skipped for {}: {}", docId, e.getMessage());
        }
    }

    private String key(String docId) {
        return SNAPSHOT_KEY.formatted(docId);
    }
}
