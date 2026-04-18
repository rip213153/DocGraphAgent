package com.agenthub.workflow;

import com.agenthub.agent.DocParserAgent;
import com.agenthub.agent.KnowledgeExtractAgent;
import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.agenthub.service.DocumentSnapshotStore;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

@Service
public class IngestWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(IngestWorkflowService.class);

    private final DocParserAgent docParser;
    private final KnowledgeExtractAgent extractor;
    private final VectorStoreService vectorStore;
    private final KnowledgeGraphService knowledgeGraph;
    private final DocumentSnapshotStore snapshotStore;
    private final WorkflowStepRunner stepRunner;
    private final ExecutorService workflowExecutor;

    public IngestWorkflowService(DocParserAgent docParser,
                                 KnowledgeExtractAgent extractor,
                                 VectorStoreService vectorStore,
                                 KnowledgeGraphService knowledgeGraph,
                                 DocumentSnapshotStore snapshotStore,
                                 WorkflowStepRunner stepRunner,
                                 @Qualifier("workflowExecutor") ExecutorService workflowExecutor) {
        this.docParser = docParser;
        this.extractor = extractor;
        this.vectorStore = vectorStore;
        this.knowledgeGraph = knowledgeGraph;
        this.snapshotStore = snapshotStore;
        this.stepRunner = stepRunner;
        this.workflowExecutor = workflowExecutor;
    }

    public Map<String, Object> ingest(String fileName, String absolutePath) throws Exception {
        WorkflowExecutionContext<IngestState> context =
                new WorkflowExecutionContext<>(IngestState.RECEIVED, allowedTransitions());

        try {
            List<DocumentChunk> chunks = stepRunner.supply("doc-parser", context, () -> docParser.parse(absolutePath));
            context.transitionTo(IngestState.PARSED);
            if (chunks == null || chunks.isEmpty()) {
                throw new IllegalStateException("No chunks parsed from document " + absolutePath);
            }

            Map<String, DocumentChunk> chunkMap = new HashMap<>();
            for (DocumentChunk chunk : chunks) {
                chunkMap.put(chunk.getChunkId(), chunk);
            }
            context.transitionTo(IngestState.CHUNKED);

            List<ExtractionResult> extractions = stepRunner.supply("knowledge-extract", context, () -> extractor.extract(chunks));
            context.transitionTo(IngestState.EXTRACTED);

            String docId = chunks.getFirst().getDocId();
            CompletableFuture<Void> vectorFuture = runStageAsync(
                    "vector-store",
                    context,
                    () -> vectorStore.addChunks(chunks)
            );
            CompletableFuture<Void> snapshotFuture = runStageAsync(
                    "snapshot-store",
                    context,
                    () -> snapshotStore.saveChunks(docId, chunks)
            );

            try {
                awaitStage(vectorFuture, "vector-store");
            } catch (Exception e) {
                snapshotFuture.cancel(true);
                throw e;
            }
            context.transitionTo(IngestState.VECTOR_STORED);

            try {
                awaitStage(snapshotFuture, "snapshot-store");
            } catch (Exception e) {
                context.markDegraded(WorkflowDegradeReason.SNAPSHOT_STORE_UNAVAILABLE.name());
                log.warn("Snapshot stage degraded for file {}: {}", absolutePath, e.getMessage());
            }
            context.transitionTo(IngestState.SNAPSHOT_STORED);

            GraphWriteSummary graphSummary;
            try {
                graphSummary = stepRunner.supply("knowledge-graph", context, () -> writeKnowledgeGraph(extractions, chunkMap, absolutePath));
            } catch (Exception e) {
                context.markDegraded(WorkflowDegradeReason.GRAPH_UNAVAILABLE.name());
                graphSummary = new GraphWriteSummary(0, 0);
                log.warn("Knowledge graph stage degraded for file {}: {}", absolutePath, e.getMessage());
            }
            context.transitionTo(IngestState.GRAPH_STORED);

            context.transitionTo(IngestState.COMPLETED);
            context.finish();
            return buildResponse(fileName, chunks.size(), graphSummary, context);
        } catch (Exception e) {
            context.fail(IngestState.FAILED);
            throw e;
        }
    }

    private GraphWriteSummary writeKnowledgeGraph(List<ExtractionResult> extractions,
                                                  Map<String, DocumentChunk> chunkMap,
                                                  String fallbackSource) {
        int entityCount = 0;
        int relationCount = 0;
        for (ExtractionResult extraction : extractions) {
            String source = resolveSource(extraction, chunkMap, fallbackSource);
            String chunkId = extraction.getSourceChunkId();
            for (ExtractionResult.Entity entity : extraction.getEntities()) {
                knowledgeGraph.upsertEntity(entity, 1, source, chunkId);
                entityCount++;
            }
            for (ExtractionResult.Relation relation : extraction.getRelations()) {
                knowledgeGraph.addRelation(relation, source, chunkId, 1);
                relationCount++;
            }
        }
        return new GraphWriteSummary(entityCount, relationCount);
    }

    private CompletableFuture<Void> runStageAsync(String stage,
                                                  WorkflowExecutionContext<IngestState> context,
                                                  WorkflowStepRunner.CheckedRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try {
                stepRunner.run(stage, context, runnable);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, workflowExecutor);
    }

    private void awaitStage(CompletableFuture<Void> stageFuture, String stage) throws Exception {
        try {
            stageFuture.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("Workflow stage failed: " + stage, cause);
        }
    }

    private Map<String, Object> buildResponse(String fileName,
                                              int chunkCount,
                                              GraphWriteSummary graphSummary,
                                              WorkflowExecutionContext<IngestState> context) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", fileName);
        response.put("chunks", chunkCount);
        response.put("entities", graphSummary.entities());
        response.put("relations", graphSummary.relations());
        response.put("status", "success");
        response.put("state", context.currentState().name());
        response.put("workflowState", context.currentState().name());
        response.put("degraded", context.degraded());
        response.put("degradeReasons", context.degradeReasons());
        response.put("retryAttempts", context.retryAttempts());
        return response;
    }

    private String resolveSource(ExtractionResult extraction, Map<String, DocumentChunk> chunkMap, String fallback) {
        DocumentChunk chunk = chunkMap.get(extraction.getSourceChunkId());
        if (chunk == null || chunk.getMetadata() == null) {
            return fallback;
        }
        return String.valueOf(chunk.getMetadata().getOrDefault("source", fallback));
    }

    private Map<IngestState, Set<IngestState>> allowedTransitions() {
        return Map.of(
                IngestState.RECEIVED, Set.of(IngestState.PARSED),
                IngestState.PARSED, Set.of(IngestState.CHUNKED),
                IngestState.CHUNKED, Set.of(IngestState.EXTRACTED),
                IngestState.EXTRACTED, Set.of(IngestState.VECTOR_STORED),
                IngestState.VECTOR_STORED, Set.of(IngestState.SNAPSHOT_STORED),
                IngestState.SNAPSHOT_STORED, Set.of(IngestState.GRAPH_STORED),
                IngestState.GRAPH_STORED, Set.of(IngestState.COMPLETED)
        );
    }

    private record GraphWriteSummary(int entities, int relations) {
    }

    private enum IngestState {
        RECEIVED,
        PARSED,
        CHUNKED,
        EXTRACTED,
        VECTOR_STORED,
        SNAPSHOT_STORED,
        GRAPH_STORED,
        COMPLETED,
        FAILED
    }
}
