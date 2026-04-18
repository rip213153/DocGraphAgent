package com.agenthub.workflow;

import com.agenthub.agent.DocParserAgent;
import com.agenthub.agent.KnowledgeExtractAgent;
import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.agenthub.service.DocumentSnapshotStore;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestWorkflowServiceTest {

    @Mock
    private DocParserAgent docParser;

    @Mock
    private KnowledgeExtractAgent extractor;

    @Mock
    private VectorStoreService vectorStore;

    @Mock
    private KnowledgeGraphService knowledgeGraph;

    @Mock
    private DocumentSnapshotStore snapshotStore;

    private ExecutorService workflowExecutor;

    @BeforeEach
    void setUp() {
        workflowExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        workflowExecutor.shutdownNow();
    }

    @Test
    void shouldExposeStructuredWorkflowStateOnSuccess() throws Exception {
        IngestWorkflowService service = newService(1);
        DocumentChunk chunk = chunk("E:/docs/agent.md");
        ExtractionResult extraction = extraction(chunk.getChunkId());

        when(docParser.parse("E:/docs/agent.md")).thenReturn(List.of(chunk));
        when(extractor.extract(List.of(chunk))).thenReturn(List.of(extraction));

        Map<String, Object> result = service.ingest("agent.md", "E:/docs/agent.md");

        assertThat(result.get("workflowState")).isEqualTo("COMPLETED");
        assertThat(result.get("degraded")).isEqualTo(false);
        assertThat(result.get("degradeReasons")).isEqualTo(List.of());
        assertThat(result.get("entities")).isEqualTo(1);
        assertThat(result.get("relations")).isEqualTo(1);
    }

    @Test
    void shouldDegradeWhenSnapshotStoreUnavailable() throws Exception {
        IngestWorkflowService service = newService(3);
        DocumentChunk chunk = chunk("E:/docs/agent.md");
        ExtractionResult extraction = extraction(chunk.getChunkId());

        when(docParser.parse("E:/docs/agent.md")).thenReturn(List.of(chunk));
        when(extractor.extract(List.of(chunk))).thenReturn(List.of(extraction));
        doThrow(new IllegalStateException("redis down"))
                .when(snapshotStore).saveChunks(anyString(), anyList());

        Map<String, Object> result = service.ingest("agent.md", "E:/docs/agent.md");

        assertThat(result.get("workflowState")).isEqualTo("COMPLETED");
        assertThat(result.get("degraded")).isEqualTo(true);
        assertThat((List<String>) result.get("degradeReasons"))
                .contains(WorkflowDegradeReason.SNAPSHOT_STORE_UNAVAILABLE.name());
        assertThat((Map<String, Integer>) result.get("retryAttempts"))
                .containsEntry("snapshot-store", 2);
    }

    @Test
    void shouldDegradeWhenGraphStoreUnavailable() throws Exception {
        IngestWorkflowService service = newService(2);
        DocumentChunk chunk = chunk("E:/docs/agent.md");
        ExtractionResult extraction = extraction(chunk.getChunkId());

        when(docParser.parse("E:/docs/agent.md")).thenReturn(List.of(chunk));
        when(extractor.extract(List.of(chunk))).thenReturn(List.of(extraction));
        doThrow(new IllegalStateException("neo4j down"))
                .when(knowledgeGraph).upsertEntity(any(), anyInt(), anyString(), anyString());

        Map<String, Object> result = service.ingest("agent.md", "E:/docs/agent.md");

        assertThat(result.get("workflowState")).isEqualTo("COMPLETED");
        assertThat(result.get("degraded")).isEqualTo(true);
        assertThat((List<String>) result.get("degradeReasons"))
                .contains(WorkflowDegradeReason.GRAPH_UNAVAILABLE.name());
    }

    @Test
    void shouldFailWhenVectorStoreWriteFails() throws Exception {
        IngestWorkflowService service = newService(1);
        DocumentChunk chunk = chunk("E:/docs/agent.md");

        when(docParser.parse("E:/docs/agent.md")).thenReturn(List.of(chunk));
        when(extractor.extract(List.of(chunk))).thenReturn(List.of(extraction(chunk.getChunkId())));
        doThrow(new IllegalStateException("milvus down"))
                .when(vectorStore).addChunks(List.of(chunk));

        assertThatThrownBy(() -> service.ingest("agent.md", "E:/docs/agent.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("milvus down");

        verify(knowledgeGraph, never()).upsertEntity(any(), anyInt(), anyString(), anyString());
    }

    private IngestWorkflowService newService(int maxAttempts) {
        return new IngestWorkflowService(
                docParser,
                extractor,
                vectorStore,
                knowledgeGraph,
                snapshotStore,
                new WorkflowStepRunner(maxAttempts, 0),
                workflowExecutor
        );
    }

    private DocumentChunk chunk(String source) {
        return DocumentChunk.builder()
                .chunkId("doc-1#0")
                .docId("doc-1")
                .chunkIndex(0)
                .content("Agent knowledge hub")
                .docType("markdown")
                .metadata(Map.of("source", source))
                .build();
    }

    private ExtractionResult extraction(String chunkId) {
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
                .sourceChunkId(chunkId)
                .build();
    }
}
