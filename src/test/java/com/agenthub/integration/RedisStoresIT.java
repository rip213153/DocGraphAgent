package com.agenthub.integration;

import com.agenthub.service.EventProcessingStore;
import com.agenthub.service.IngestTaskSnapshot;
import com.agenthub.service.IngestTaskStatus;
import com.agenthub.service.RedisEventProcessingStore;
import com.agenthub.service.RedisIngestTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisStoresIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ObjectMapper objectMapper;

    private RedisIngestTaskStore ingestTaskStore;
    private RedisEventProcessingStore eventProcessingStore;

    @BeforeAll
    static void beforeAll() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterAll
    static void afterAll() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        var keys = redisTemplate.keys("agent:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        ingestTaskStore = new RedisIngestTaskStore(redisTemplate, objectMapper);
        eventProcessingStore = new RedisEventProcessingStore(redisTemplate, objectMapper);
        ReflectionTestUtils.setField(ingestTaskStore, "taskTtlHours", 24);
        ReflectionTestUtils.setField(eventProcessingStore, "eventTtlHours", 24);
    }

    @Test
    void shouldPersistAndRecoverIngestTaskSnapshots() {
        IngestTaskSnapshot processing = new IngestTaskSnapshot(
                "ingest-it-1",
                "agent.md",
                "E:/tmp/agent.md",
                IngestTaskStatus.PROCESSING,
                "EXTRACTING",
                Instant.parse("2026-05-11T10:00:00Z"),
                Instant.parse("2026-05-11T10:00:10Z"),
                null,
                null,
                4,
                1,
                false,
                List.of(),
                null
        );
        IngestTaskSnapshot queued = new IngestTaskSnapshot(
                "ingest-it-2",
                "agent-2.md",
                "E:/tmp/agent-2.md",
                IngestTaskStatus.QUEUED,
                "QUEUED",
                Instant.parse("2026-05-11T10:01:00Z"),
                null,
                null,
                null,
                0,
                0,
                false,
                List.of(),
                null
        );

        ingestTaskStore.save(processing);
        ingestTaskStore.save(queued);

        int recovered = ingestTaskStore.markProcessingTasksAsFailed("Application restarted");

        assertThat(recovered).isEqualTo(1);
        IngestTaskSnapshot recoveredProcessing = ingestTaskStore.getTask("ingest-it-1").orElseThrow();
        assertThat(recoveredProcessing.status()).isEqualTo(IngestTaskStatus.FAILED);
        assertThat(recoveredProcessing.failureReason()).isEqualTo("Application restarted");
        assertThat(recoveredProcessing.completedAt()).isNotNull();

        IngestTaskSnapshot queuedAfterRecover = ingestTaskStore.getTask("ingest-it-2").orElseThrow();
        assertThat(queuedAfterRecover.status()).isEqualTo(IngestTaskStatus.QUEUED);
    }

    @Test
    void shouldPersistEventStateAndLatestFileProgress() {
        String eventId = "evt-it-1";
        String filePath = "E:/docs/agent.md";
        Instant timestamp = Instant.parse("2026-05-11T11:00:00Z");
        String payload = "{\"event_id\":\"evt-it-1\",\"file_path\":\"E:/docs/agent.md\"}";

        eventProcessingStore.markReceived(eventId, filePath, "modified", 7L, timestamp, payload);
        EventProcessingStore.EventState received = eventProcessingStore.getEvent(eventId).orElseThrow();
        assertThat(received.status()).isEqualTo(EventProcessingStore.ProcessingStatus.RECEIVED);

        eventProcessingStore.markSucceeded(eventId, filePath, "modified", 7L, timestamp, payload);
        EventProcessingStore.EventState succeeded = eventProcessingStore.getEvent(eventId).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(EventProcessingStore.ProcessingStatus.SUCCEEDED);
        assertThat(succeeded.processedAt()).isNotNull();
        assertThat(succeeded.payload()).isEqualTo(payload);

        assertThat(eventProcessingStore.getLastProcessedTimestamp(filePath)).contains(timestamp);
        assertThat(eventProcessingStore.getLastProcessedVersion(filePath)).contains(7L);
    }

    @Test
    void shouldPersistFailureReasonForFailedEvent() {
        String eventId = "evt-it-failed";
        String filePath = "E:/docs/failed.md";
        Instant timestamp = Instant.parse("2026-05-11T12:00:00Z");

        eventProcessingStore.markFailed(
                eventId,
                filePath,
                "deleted",
                3L,
                timestamp,
                "neo4j unavailable",
                Map.of("reason", "neo4j unavailable").toString()
        );

        EventProcessingStore.EventState failed = eventProcessingStore.getEvent(eventId).orElseThrow();
        assertThat(failed.status()).isEqualTo(EventProcessingStore.ProcessingStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("neo4j unavailable");
        assertThat(failed.processedAt()).isNotNull();
    }
}
