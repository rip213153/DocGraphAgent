package com.agenthub.agent;

import com.agenthub.model.DocumentChangeEvent;
import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.agenthub.service.DocumentSnapshotStore;
import com.agenthub.service.EventProcessingStore;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.RetryableEventException;
import com.agenthub.service.VectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeUpdateAgentTest {

    @Mock
    private DocParserAgent docParser;

    @Mock
    private KnowledgeExtractAgent extractor;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DocumentSnapshotStore snapshotStore;

    @Mock
    private EventProcessingStore eventProcessingStore;

    private SimpleMeterRegistry meterRegistry;
    private ExecutorService workflowExecutor;
    private KnowledgeUpdateAgent knowledgeUpdateAgent;

    @BeforeEach
    void setUp() {
        workflowExecutor = Executors.newVirtualThreadPerTaskExecutor();
        meterRegistry = new SimpleMeterRegistry();
        knowledgeUpdateAgent = new KnowledgeUpdateAgent(
                docParser,
                extractor,
                vectorStoreService,
                knowledgeGraphService,
                snapshotStore,
                eventProcessingStore,
                meterRegistry,
                objectMapper,
                workflowExecutor
        );
    }

    @AfterEach
    void tearDown() {
        workflowExecutor.shutdownNow();
    }

    @Test
    void shouldCreateKnowledgeWithInitialVersion() throws Exception {
        String filePath = "E:/docs/agent.md";
        DocumentChunk chunk = chunk(filePath);
        ExtractionResult extractionResult = extractionResult(chunk.getChunkId());

        when(docParser.parse(filePath)).thenReturn(List.of(chunk));
        when(extractor.extract(List.of(chunk))).thenReturn(List.of(extractionResult));

        knowledgeUpdateAgent.handleCreate(filePath);

        verify(vectorStoreService).addChunks(List.of(chunk));
        verify(knowledgeGraphService).upsertEntity(extractionResult.getEntities().getFirst(), 1, filePath, chunk.getChunkId());
        verify(knowledgeGraphService).addRelation(extractionResult.getRelations().getFirst(), filePath, chunk.getChunkId(), 1);
    }

    @Test
    void shouldRebuildDocumentOnModifyAndBumpVersion() throws Exception {
        String filePath = "E:/docs/agent.md";
        DocumentChunk chunk = chunk(filePath);
        ExtractionResult extractionResult = extractionResult(chunk.getChunkId());

        when(snapshotStore.getChunks(anyString())).thenReturn(List.of());
        when(docParser.parse(filePath)).thenReturn(List.of(chunk));
        when(extractor.extract(List.of(chunk))).thenReturn(List.of(extractionResult));

        knowledgeUpdateAgent.handleModify(filePath);

        verify(vectorStoreService).deleteByChunkIds(List.of(chunk.getChunkId()));
        verify(knowledgeGraphService).deleteBySourceAndChunkIds(filePath, List.of(chunk.getChunkId()));
        verify(vectorStoreService).addChunks(List.of(chunk));
        verify(snapshotStore).saveChunks(anyString(), eq(List.of(chunk)));
        verify(knowledgeGraphService).upsertEntity(extractionResult.getEntities().getFirst(), 2, filePath, chunk.getChunkId());
    }

    @Test
    void shouldOnlyRebuildChangedChunksOnModify() throws Exception {
        String filePath = "E:/docs/agent.md";
        DocumentChunk oldChunk = chunk(filePath, 0, "Agent knowledge hub");
        DocumentChunk oldChangedChunk = chunk(filePath, 1, "Old chunk content");
        DocumentChunk unchangedChunk = chunk(filePath, 0, "Agent knowledge hub");
        DocumentChunk changedChunk = chunk(filePath, 1, "Updated chunk content");
        ExtractionResult changedExtraction = extractionResult(changedChunk.getChunkId());

        when(snapshotStore.getChunks(anyString())).thenReturn(List.of(oldChunk, oldChangedChunk));
        when(docParser.parse(filePath)).thenReturn(List.of(unchangedChunk, changedChunk));
        when(extractor.extract(List.of(changedChunk))).thenReturn(List.of(changedExtraction));

        knowledgeUpdateAgent.handleModify(filePath);

        verify(vectorStoreService).deleteByChunkIds(List.of(changedChunk.getChunkId()));
        verify(knowledgeGraphService).deleteBySourceAndChunkIds(filePath, List.of(changedChunk.getChunkId()));
        verify(vectorStoreService).addChunks(List.of(changedChunk));
        verify(snapshotStore).saveChunks(anyString(), eq(List.of(unchangedChunk, changedChunk)));
    }

    @Test
    void shouldDeleteRemovedAndChangedChunksBeforeSelectiveRebuild() throws Exception {
        String filePath = "E:/docs/agent.md";
        DocumentChunk oldChangedChunk = chunk(filePath, 0, "Old chunk content");
        DocumentChunk removedChunk = chunk(filePath, 1, "Removed chunk content");
        DocumentChunk changedChunk = chunk(filePath, 0, "Updated chunk content");
        ExtractionResult changedExtraction = extractionResult(changedChunk.getChunkId());

        when(snapshotStore.getChunks(anyString())).thenReturn(List.of(oldChangedChunk, removedChunk));
        when(docParser.parse(filePath)).thenReturn(List.of(changedChunk));
        when(extractor.extract(List.of(changedChunk))).thenReturn(List.of(changedExtraction));

        knowledgeUpdateAgent.handleModify(filePath);

        verify(vectorStoreService).deleteByChunkIds(List.of(removedChunk.getChunkId(), changedChunk.getChunkId()));
        verify(knowledgeGraphService).deleteBySourceAndChunkIds(filePath, List.of(removedChunk.getChunkId(), changedChunk.getChunkId()));
        verify(vectorStoreService).addChunks(List.of(changedChunk));
    }

    @Test
    void shouldDeleteSnapshotWhenModifiedDocumentBecomesEmpty() throws Exception {
        String filePath = "E:/docs/agent.md";
        DocumentChunk oldChunk = chunk(filePath, 0, "Old chunk content");

        when(snapshotStore.getChunks(anyString())).thenReturn(List.of(oldChunk));
        when(docParser.parse(filePath)).thenReturn(List.of());

        knowledgeUpdateAgent.handleModify(filePath);

        verify(vectorStoreService).deleteByChunkIds(List.of(oldChunk.getChunkId()));
        verify(knowledgeGraphService).deleteBySourceAndChunkIds(filePath, List.of(oldChunk.getChunkId()));
        verify(snapshotStore).delete(anyString());
        verify(vectorStoreService, never()).addChunks(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldSkipDuplicateEventByEventId() throws Exception {
        String filePath = "E:/docs/agent.md";
        DocumentChangeEvent event = DocumentChangeEvent.builder()
                .eventId("evt-1")
                .filePath(filePath)
                .changeType("deleted")
                .timestamp(Instant.parse("2026-04-18T10:00:00Z"))
                .sourceType("kafka")
                .build();

        knowledgeUpdateAgent.processChangeEvent(event);
        knowledgeUpdateAgent.processChangeEvent(event);

        verify(vectorStoreService, times(1)).deleteByDocId(anyString());
        verify(knowledgeGraphService, times(1)).deleteBySource(filePath);
    }

    @Test
    void shouldSkipOlderVersionEventWhenNewerVersionAlreadyProcessed() throws Exception {
        String filePath = "E:/docs/agent.md";
        DocumentChangeEvent newer = DocumentChangeEvent.builder()
                .eventId("evt-new")
                .filePath(filePath)
                .changeType("modified")
                .changeVersion(5L)
                .timestamp(Instant.parse("2026-04-18T11:00:00Z"))
                .sourceType("kafka")
                .build();
        DocumentChangeEvent stale = DocumentChangeEvent.builder()
                .eventId("evt-old")
                .filePath(filePath)
                .changeType("modified")
                .changeVersion(4L)
                .timestamp(Instant.parse("2026-04-18T10:00:00Z"))
                .sourceType("kafka")
                .build();
        DocumentChunk chunk = chunk(filePath);

        when(snapshotStore.getChunks(anyString())).thenReturn(List.of());
        when(docParser.parse(filePath)).thenReturn(List.of(chunk));
        when(extractor.extract(List.of(chunk))).thenReturn(List.of(extractionResult(chunk.getChunkId())));
        when(eventProcessingStore.getEvent("evt-new")).thenReturn(Optional.empty());
        when(eventProcessingStore.getEvent("evt-old")).thenReturn(Optional.empty());
        when(eventProcessingStore.getLastProcessedVersion(filePath)).thenReturn(Optional.empty());
        when(eventProcessingStore.getLastProcessedTimestamp(filePath)).thenReturn(Optional.empty());

        knowledgeUpdateAgent.processChangeEvent(newer);
        knowledgeUpdateAgent.processChangeEvent(stale);

        verify(vectorStoreService, times(1)).addChunks(List.of(chunk));
    }

    @Test
    void shouldRethrowRetryableExceptionWhenKafkaMessageProcessingFails() throws Exception {
        when(objectMapper.readTree("bad-json")).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> knowledgeUpdateAgent.handleCDCEvent("bad-json"))
                .isInstanceOf(RetryableEventException.class)
                .hasMessageContaining("CDC event processing failed");

        verify(vectorStoreService, never()).deleteByDocId(anyString());
    }

    @Test
    void shouldDeleteVectorAndGraphDataByFilePath() {
        String filePath = "E:/docs/agent.md";

        knowledgeUpdateAgent.handleDelete(filePath);

        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorStoreService).deleteByDocId(docIdCaptor.capture());
        verify(knowledgeGraphService).deleteBySource(filePath);
        assertThat(docIdCaptor.getValue()).hasSize(16);
    }

    @Test
    void shouldRecordProcessedAndDeletedMetricsWhenDeleteEventHandled() throws Exception {
        DocumentChangeEvent event = DocumentChangeEvent.builder()
                .eventId("evt-delete-1")
                .filePath("E:/docs/agent.md")
                .changeType("deleted")
                .timestamp(Instant.parse("2026-04-18T10:00:00Z"))
                .sourceType("kafka")
                .build();

        knowledgeUpdateAgent.processChangeEvent(event);

        assertThat(meterRegistry.get("agenthub.kafka.events.processed")
                .tag("changeType", "deleted")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agenthub.update.deleted")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordSkippedMetricWhenDuplicateEventIsIgnored() throws Exception {
        DocumentChangeEvent event = DocumentChangeEvent.builder()
                .eventId("evt-dup-1")
                .filePath("E:/docs/agent.md")
                .changeType("deleted")
                .timestamp(Instant.parse("2026-04-18T10:00:00Z"))
                .sourceType("kafka")
                .build();

        knowledgeUpdateAgent.processChangeEvent(event);
        knowledgeUpdateAgent.processChangeEvent(event);

        assertThat(meterRegistry.get("agenthub.kafka.events.skipped")
                .tag("reason", "duplicate")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordFailureAndDurationMetricsWhenEventParsingFails() throws Exception {
        when(objectMapper.readTree("bad-json")).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> knowledgeUpdateAgent.handleCDCEvent("bad-json"))
                .isInstanceOf(RetryableEventException.class)
                .hasMessageContaining("CDC event processing failed");

        assertThat(meterRegistry.get("agenthub.kafka.events.failed")
                .tag("retryable", "true")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("agenthub.update.event.duration")
                .timer().count()).isEqualTo(1L);
    }

    private DocumentChunk chunk(String source) {
        return chunk(source, 0, "Agent knowledge hub");
    }

    private DocumentChunk chunk(String source, int chunkIndex, String content) {
        return DocumentChunk.builder()
                .chunkId("doc-1#" + chunkIndex)
                .docId("doc-1")
                .chunkIndex(chunkIndex)
                .content(content)
                .docType("markdown")
                .metadata(Map.of("source", source))
                .build();
    }

    private ExtractionResult extractionResult(String sourceChunkId) {
        return ExtractionResult.builder()
                .entities(List.of(ExtractionResult.Entity.builder()
                        .name("Redis")
                        .type("Technology")
                        .description("Short-term memory")
                        .build()))
                .relations(List.of(ExtractionResult.Relation.builder()
                        .head("Redis")
                        .relation("used_by")
                        .tail("AgentKnowledgeHub")
                        .confidence(0.9)
                        .build()))
                .sourceChunkId(sourceChunkId)
                .build();
    }
}
