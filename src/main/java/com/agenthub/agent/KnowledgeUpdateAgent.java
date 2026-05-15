package com.agenthub.agent;

import com.agenthub.model.DocumentChangeEvent;
import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.agenthub.service.DocumentSnapshotStore;
import com.agenthub.service.EventProcessingStore;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.NonRetryableEventException;
import com.agenthub.service.RetryableEventException;
import com.agenthub.service.VectorStoreService;
import com.agenthub.util.DocumentIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class KnowledgeUpdateAgent {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUpdateAgent.class);

    private final DocParserAgent docParser;
    private final KnowledgeExtractAgent extractor;
    private final VectorStoreService vectorStore;
    private final KnowledgeGraphService knowledgeGraph;
    private final DocumentSnapshotStore snapshotStore;
    private final EventProcessingStore eventProcessingStore;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final ExecutorService workflowExecutor;
    private final Map<String, Long> documentVersions = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastProcessedTimestamps = new ConcurrentHashMap<>();
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    public KnowledgeUpdateAgent(DocParserAgent docParser,
                                KnowledgeExtractAgent extractor,
                                VectorStoreService vectorStore,
                                KnowledgeGraphService knowledgeGraph,
                                DocumentSnapshotStore snapshotStore,
                                EventProcessingStore eventProcessingStore,
                                MeterRegistry meterRegistry,
                                ObjectMapper objectMapper,
                                @Qualifier("workflowExecutor") ExecutorService workflowExecutor) {
        this.docParser = docParser;
        this.extractor = extractor;
        this.vectorStore = vectorStore;
        this.knowledgeGraph = knowledgeGraph;
        this.snapshotStore = snapshotStore;
        this.eventProcessingStore = eventProcessingStore;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.workflowExecutor = workflowExecutor;
    }

    @KafkaListener(topics = "${app.kafka.topic:doc-changes}", groupId = "agenthub-java")
    public void handleCDCEvent(String message) {
        processEventMessage(message);
    }

    public void replayEventPayload(String message) {
        processEventMessage(message);
    }

    private void processEventMessage(String message) {
        long startNanos = System.nanoTime();
        DocumentChangeEvent event = null;
        try {
            event = parseEventMessage(message);
            processChangeEvent(event, message);
        } catch (NonRetryableEventException e) {
            markFailed(event, e, message);
            meterRegistry.counter("agenthub.kafka.events.failed", "retryable", "false").increment();
            log.warn("CDC event permanently failed eventId={} filePath={} reason={}",
                    event == null ? "unknown" : event.getEventId(),
                    event == null ? "unknown" : event.getFilePath(),
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            markFailed(event, e, message);
            meterRegistry.counter("agenthub.kafka.events.failed", "retryable", "true").increment();
            log.error("CDC event processing failed eventId={} filePath={}",
                    event == null ? "unknown" : event.getEventId(),
                    event == null ? "unknown" : event.getFilePath(), e);
            throw new RetryableEventException("CDC event processing failed", e);
        } finally {
            meterRegistry.timer("agenthub.update.event.duration")
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }

    private DocumentChangeEvent parseEventMessage(String message) throws Exception {
        JsonNode payload = objectMapper.readTree(message);
        String filePath = payload.path("file_path").asText();
        if (filePath == null || filePath.isBlank()) {
            throw new NonRetryableEventException("file_path is required");
        }
        return DocumentChangeEvent.builder()
                .eventId(payload.path("event_id").asText(buildFallbackEventId(filePath)))
                .filePath(filePath)
                .changeType(payload.path("change_type").asText("modified"))
                .documentVersion(payload.path("document_version").isMissingNode() ? null : payload.path("document_version").asLong())
                .changeVersion(payload.path("change_version").isMissingNode() ? null : payload.path("change_version").asLong())
                .timestamp(Instant.ofEpochMilli(payload.path("timestamp").asLong(System.currentTimeMillis())))
                .sourceType(payload.path("source_type").asText("kafka"))
                .build();
    }

    void processChangeEvent(DocumentChangeEvent event) throws Exception {
        processChangeEvent(event, null);
    }

    void processChangeEvent(DocumentChangeEvent event, String rawPayload) throws Exception {
        if (event == null || event.getFilePath() == null || event.getFilePath().isBlank()) {
            throw new NonRetryableEventException("missing file path");
        }
        if (shouldSkipEvent(event)) {
            return;
        }

        markReceived(event, rawPayload);
        markProcessing(event, rawPayload);

        switch (event.getChangeType()) {
            case "created" -> handleCreate(event.getFilePath(), event.effectiveVersion());
            case "modified" -> handleModify(event.getFilePath(), event.effectiveVersion());
            case "deleted" -> handleDelete(event.getFilePath(), event.effectiveVersion());
            default -> throw new NonRetryableEventException("unsupported CDC change type: " + event.getChangeType());
        }

        processedEventIds.add(event.getEventId());
        lastProcessedTimestamps.put(event.getFilePath(), safeTimestamp(event));
        if (event.effectiveVersion() != null) {
            documentVersions.put(event.getFilePath(), event.effectiveVersion());
        }
        meterRegistry.counter("agenthub.kafka.events.processed", "changeType", event.getChangeType()).increment();
        markSucceeded(event, rawPayload);
    }

    public void handleCreate(String filePath) throws Exception {
        handleCreate(filePath, null);
    }

    public void handleCreate(String filePath, Long explicitVersion) throws Exception {
        List<DocumentChunk> chunks = docParser.parse(filePath);
        vectorStore.addChunks(chunks);
        long version = resolveAppliedVersion(filePath, explicitVersion, true);
        upsertKnowledge(chunks, filePath, version);
        persistSnapshot(DocumentIdentity.computeDocId(filePath), chunks);
        meterRegistry.counter("agenthub.update.vector.added").increment(chunks.size());
        log.info("Knowledge create completed filePath={} version={} chunks={}", filePath, version, chunks.size());
    }

    public void handleModify(String filePath) throws Exception {
        handleModify(filePath, null);
    }

    public void handleModify(String filePath, Long explicitVersion) throws Exception {
        String docId = DocumentIdentity.computeDocId(filePath);
        List<DocumentChunk> previousChunks = loadSnapshotOrFallback(docId);
        List<DocumentChunk> currentChunks = docParser.parse(filePath);
        ChunkDiff diff = ChunkDiff.of(previousChunks, currentChunks);
        long version = resolveAppliedVersion(filePath, explicitVersion, false);
        List<String> staleChunkIds = diff.staleChunkIds();

        int deletedVectors = 0;
        if (!staleChunkIds.isEmpty()) {
            CompletableFuture<Integer> vectorDeleteFuture = CompletableFuture.supplyAsync(
                    () -> vectorStore.deleteByChunkIds(staleChunkIds),
                    workflowExecutor
            );
            CompletableFuture<Void> graphDeleteFuture = CompletableFuture.runAsync(
                    () -> knowledgeGraph.deleteBySourceAndChunkIds(filePath, staleChunkIds),
                    workflowExecutor
            );

            deletedVectors = awaitResult(vectorDeleteFuture, "vector-delete");
            awaitCompletion(graphDeleteFuture, "graph-delete");
        }

        if (!diff.changedChunks().isEmpty()) {
            vectorStore.addChunks(diff.changedChunks());
        }
        upsertKnowledge(diff.changedChunks(), filePath, version);
        persistSnapshot(docId, currentChunks);

        meterRegistry.counter("agenthub.update.chunk.changed").increment(diff.changedChunks().size());
        meterRegistry.counter("agenthub.update.chunk.removed").increment(diff.removedChunkIds().size());
        meterRegistry.counter("agenthub.update.vector.deleted").increment(deletedVectors);

        log.info(
                "Knowledge modify completed filePath={} version={} unchanged={} changed={} removed={} vectorDeletes={}",
                filePath, version, diff.unchangedCount(), diff.changedChunks().size(), diff.removedChunkIds().size(), deletedVectors
        );
    }

    public void handleDelete(String filePath) {
        handleDelete(filePath, null);
    }

    public void handleDelete(String filePath, Long explicitVersion) {
        String docId = DocumentIdentity.computeDocId(filePath);
        vectorStore.deleteByDocId(docId);
        knowledgeGraph.deleteBySource(filePath);
        deleteSnapshot(docId);
        documentVersions.put(filePath, resolveAppliedVersion(filePath, explicitVersion, false));
        meterRegistry.counter("agenthub.update.deleted").increment();
    }

    private Map<String, DocumentChunk> indexByChunkId(List<DocumentChunk> chunks) {
        Map<String, DocumentChunk> chunkMap = new HashMap<>();
        for (DocumentChunk chunk : chunks) {
            chunkMap.put(chunk.getChunkId(), chunk);
        }
        return chunkMap;
    }

    private String resolveSource(ExtractionResult extraction, Map<String, DocumentChunk> chunkMap, String fallback) {
        DocumentChunk chunk = chunkMap.get(extraction.getSourceChunkId());
        if (chunk == null || chunk.getMetadata() == null) {
            return fallback;
        }
        return String.valueOf(chunk.getMetadata().getOrDefault("source", fallback));
    }

    private String buildFallbackEventId(String filePath) {
        return DocumentIdentity.computeDocId(filePath) + "-" + System.currentTimeMillis();
    }

    private boolean shouldSkipEvent(DocumentChangeEvent event) {
        if (event.getEventId() != null && processedEventIds.contains(event.getEventId())) {
            meterRegistry.counter("agenthub.kafka.events.skipped", "reason", "duplicate").increment();
            log.info("Skip duplicate CDC event {}", event.getEventId());
            return true;
        }
        if (eventProcessingStore != null) {
            if (event.getEventId() != null && eventProcessingStore.getEvent(event.getEventId())
                    .map(state -> state.status() == EventProcessingStore.ProcessingStatus.SUCCEEDED)
                    .orElse(false)) {
                meterRegistry.counter("agenthub.kafka.events.skipped", "reason", "already_succeeded").increment();
                log.info("Skip already succeeded event {}", event.getEventId());
                return true;
            }
            Long persistedVersion = eventProcessingStore.getLastProcessedVersion(event.getFilePath()).orElse(null);
            if (isStaleByVersion(event.getChangeType(), persistedVersion, event.effectiveVersion())) {
                meterRegistry.counter("agenthub.kafka.events.skipped", "reason", "stale_version").increment();
                log.info("Skip stale persisted version event {} for file {} version={}",
                        event.getEventId(), event.getFilePath(), event.effectiveVersion());
                return true;
            }
            Instant persistedTimestamp = eventProcessingStore.getLastProcessedTimestamp(event.getFilePath()).orElse(null);
            if (event.effectiveVersion() == null && persistedTimestamp != null && safeTimestamp(event).isBefore(persistedTimestamp)) {
                meterRegistry.counter("agenthub.kafka.events.skipped", "reason", "stale_timestamp").increment();
                log.info("Skip stale persisted CDC event {} for file {}", event.getEventId(), event.getFilePath());
                return true;
            }
        }
        if (isStaleByVersion(event.getChangeType(), documentVersions.get(event.getFilePath()), event.effectiveVersion())) {
            meterRegistry.counter("agenthub.kafka.events.skipped", "reason", "stale_version").increment();
            log.info("Skip stale in-memory version event {} for file {} version={}",
                    event.getEventId(), event.getFilePath(), event.effectiveVersion());
            return true;
        }
        Instant lastTimestamp = lastProcessedTimestamps.get(event.getFilePath());
        if (event.effectiveVersion() == null && lastTimestamp != null && safeTimestamp(event).isBefore(lastTimestamp)) {
            meterRegistry.counter("agenthub.kafka.events.skipped", "reason", "stale_timestamp").increment();
            log.info("Skip stale CDC event {} for file {}", event.getEventId(), event.getFilePath());
            return true;
        }
        return false;
    }

    private Instant safeTimestamp(DocumentChangeEvent event) {
        return event == null || event.getTimestamp() == null ? Instant.EPOCH : event.getTimestamp();
    }

    private void upsertKnowledge(List<DocumentChunk> chunks, String filePath, long version) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<ExtractionResult> extractions = extractor.extract(chunks);
        int graphVersion = Math.toIntExact(version);
        int relationCount = 0;
        Map<String, DocumentChunk> chunkMap = indexByChunkId(chunks);
        for (ExtractionResult ext : extractions) {
            String source = resolveSource(ext, chunkMap, filePath);
            String chunkId = ext.getSourceChunkId();
            for (ExtractionResult.Entity entity : ext.getEntities()) {
                knowledgeGraph.upsertEntity(entity, graphVersion, source, chunkId);
            }
            for (ExtractionResult.Relation relation : ext.getRelations()) {
                knowledgeGraph.addRelation(relation, source, chunkId, graphVersion);
                relationCount++;
            }
        }
        meterRegistry.counter("agenthub.update.graph.added").increment(relationCount);
    }

    private List<DocumentChunk> loadSnapshotOrFallback(String docId) {
        if (snapshotStore != null) {
            List<DocumentChunk> chunks = snapshotStore.getChunks(docId);
            if (!chunks.isEmpty()) {
                return chunks;
            }
        }
        return vectorStore.getChunksByDocId(docId);
    }

    private void persistSnapshot(String docId, List<DocumentChunk> chunks) {
        if (snapshotStore == null || docId == null || docId.isBlank()) {
            return;
        }
        if (chunks == null || chunks.isEmpty()) {
            snapshotStore.delete(docId);
            return;
        }
        snapshotStore.saveChunks(docId, chunks);
    }

    private void deleteSnapshot(String docId) {
        if (snapshotStore != null) {
            snapshotStore.delete(docId);
        }
    }

    private void markReceived(DocumentChangeEvent event, String rawPayload) {
        if (eventProcessingStore != null && event.getEventId() != null) {
            eventProcessingStore.markReceived(
                    event.getEventId(), event.getFilePath(), event.getChangeType(),
                    event.effectiveVersion(), safeTimestamp(event), rawPayload);
        }
    }

    private void markProcessing(DocumentChangeEvent event, String rawPayload) {
        if (eventProcessingStore != null && event.getEventId() != null) {
            eventProcessingStore.markProcessing(
                    event.getEventId(), event.getFilePath(), event.getChangeType(),
                    event.effectiveVersion(), safeTimestamp(event), rawPayload);
        }
    }

    private void markSucceeded(DocumentChangeEvent event, String rawPayload) {
        if (eventProcessingStore != null && event.getEventId() != null) {
            eventProcessingStore.markSucceeded(
                    event.getEventId(), event.getFilePath(), event.getChangeType(),
                    event.effectiveVersion(), safeTimestamp(event), rawPayload);
        }
    }

    private void markFailed(DocumentChangeEvent event, Exception error, String rawPayload) {
        if (eventProcessingStore != null && event != null && event.getEventId() != null) {
            eventProcessingStore.markFailed(
                    event.getEventId(), event.getFilePath(), event.getChangeType(),
                    event.effectiveVersion(), safeTimestamp(event), error.getMessage(), rawPayload);
        }
    }

    private long resolveAppliedVersion(String filePath, Long explicitVersion, boolean createEvent) {
        return documentVersions.compute(filePath, (key, current) -> {
            if (explicitVersion != null) {
                return explicitVersion;
            }
            if (current == null) {
                return createEvent ? 1L : 2L;
            }
            return current + 1L;
        });
    }

    private boolean isStaleByVersion(String changeType, Long lastVersion, Long currentVersion) {
        if (lastVersion == null || currentVersion == null) {
            return false;
        }
        if ("deleted".equals(changeType)) {
            return currentVersion < lastVersion;
        }
        return currentVersion <= lastVersion;
    }

    private <T> T awaitResult(CompletableFuture<T> future, String stage) {
        try {
            return future.join();
        } catch (CompletionException e) {
            throw toIllegalState(stage, e);
        }
    }

    private void awaitCompletion(CompletableFuture<Void> future, String stage) {
        try {
            future.join();
        } catch (CompletionException e) {
            throw toIllegalState(stage, e);
        }
    }

    private IllegalStateException toIllegalState(String stage, CompletionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return new IllegalStateException("Async stage failed: " + stage, runtimeException);
        }
        return new IllegalStateException("Async stage failed: " + stage, cause);
    }

    static final class ChunkDiff {
        private final List<DocumentChunk> changedChunks;
        private final List<String> removedChunkIds;
        private final int unchangedCount;

        private ChunkDiff(List<DocumentChunk> changedChunks, List<String> removedChunkIds, int unchangedCount) {
            this.changedChunks = changedChunks;
            this.removedChunkIds = removedChunkIds;
            this.unchangedCount = unchangedCount;
        }

        static ChunkDiff of(List<DocumentChunk> previous, List<DocumentChunk> current) {
            Map<String, DocumentChunk> oldByChunkId = new LinkedHashMap<>();
            for (DocumentChunk chunk : previous) {
                oldByChunkId.put(chunk.getChunkId(), chunk);
            }
            List<DocumentChunk> changedChunks = new ArrayList<>();
            int unchangedCount = 0;
            for (DocumentChunk chunk : current) {
                DocumentChunk old = oldByChunkId.remove(chunk.getChunkId());
                if (old != null && old.contentHash().equals(chunk.contentHash())) {
                    unchangedCount++;
                    continue;
                }
                changedChunks.add(chunk);
            }
            return new ChunkDiff(changedChunks, new ArrayList<>(oldByChunkId.keySet()), unchangedCount);
        }

        List<DocumentChunk> changedChunks() {
            return changedChunks;
        }

        List<String> removedChunkIds() {
            return removedChunkIds;
        }

        List<String> staleChunkIds() {
            List<String> staleChunkIds = new ArrayList<>(removedChunkIds);
            for (DocumentChunk chunk : changedChunks) {
                staleChunkIds.add(chunk.getChunkId());
            }
            return staleChunkIds;
        }

        int unchangedCount() {
            return unchangedCount;
        }
    }
}
