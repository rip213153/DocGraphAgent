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
import java.util.LinkedHashSet;
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
        return ingest(fileName, absolutePath, IngestProgressListener.NOOP);
    }

    public Map<String, Object> ingest(String fileName, String absolutePath, IngestProgressListener progressListener) throws Exception {
        WorkflowExecutionContext<IngestState> context =
                new WorkflowExecutionContext<>(IngestState.RECEIVED, allowedTransitions());

        try {
            log.info("Ingest started for {}", fileName);
            emitProgress(progressListener, context, "RECEIVED", null, 0);
            List<DocumentChunk> chunks = stepRunner.supply(
                    "doc-parser",
                    context,
                    () -> docParser.parse(
                            absolutePath,
                            resolveSourceIdentity(fileName, absolutePath),
                            resolveDisplaySource(fileName, absolutePath))
            );
            context.transitionTo(IngestState.PARSED);
            if (chunks == null || chunks.isEmpty()) {
                throw new IllegalStateException("No chunks parsed from document " + absolutePath);
            }
            log.info("Parsed {} chunks for {}", chunks.size(), fileName);
            emitProgress(progressListener, context, "PARSED", chunks.size(), 0);

            Map<String, DocumentChunk> chunkMap = new HashMap<>();
            for (DocumentChunk chunk : chunks) {
                chunkMap.put(chunk.getChunkId(), chunk);
            }
            context.transitionTo(IngestState.CHUNKED);
            emitProgress(progressListener, context, "CHUNKED", chunks.size(), 0);

            List<ExtractionResult> extractions = stepRunner.supply("knowledge-extract", context,
                    () -> extractor.extract(chunks, (processed, total) ->
                            emitProgress(progressListener, context, "EXTRACTING", total, processed)));
            context.transitionTo(IngestState.EXTRACTED);
            log.info("Knowledge extraction finished for {} with {} chunk results", fileName, extractions.size());
            emitProgress(progressListener, context, "EXTRACTED", chunks.size(), chunks.size());

            String docId = chunks.getFirst().getDocId();
            CompletableFuture<Void> vectorFuture = runStageAsync(
                    "vector-store",
                    context,
                    () -> {
                        vectorStore.deleteByDocId(docId);
                        vectorStore.addChunks(chunks);
                    }
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
            log.info("Vector store stage finished for {}", fileName);
            emitProgress(progressListener, context, "VECTOR_STORED", chunks.size(), chunks.size());

            try {
                awaitStage(snapshotFuture, "snapshot-store");
            } catch (Exception e) {
                context.markDegraded(WorkflowDegradeReason.SNAPSHOT_STORE_UNAVAILABLE.name());
                log.warn("Snapshot stage degraded for file {}: {}", absolutePath, e.getMessage());
            }
            context.transitionTo(IngestState.SNAPSHOT_STORED);
            emitProgress(progressListener, context, "SNAPSHOT_STORED", chunks.size(), chunks.size());

            GraphWriteSummary graphSummary;
            try {
                graphSummary = stepRunner.supply(
                        "knowledge-graph",
                        context,
                        () -> writeKnowledgeGraph(extractions, chunkMap, resolveDisplaySource(fileName, absolutePath)));
            } catch (Exception e) {
                context.markDegraded(WorkflowDegradeReason.GRAPH_UNAVAILABLE.name());
                graphSummary = new GraphWriteSummary(0, 0);
                log.warn("Knowledge graph stage degraded for file {}: {}", absolutePath, e.getMessage());
            }
            context.transitionTo(IngestState.GRAPH_STORED);
            emitProgress(progressListener, context, "GRAPH_STORED", chunks.size(), chunks.size());

            context.transitionTo(IngestState.COMPLETED);
            context.finish();
            log.info("Ingest completed for {}", fileName);
            emitProgress(progressListener, context, "COMPLETED", chunks.size(), chunks.size());
            return buildResponse(fileName, chunks.size(), graphSummary, context);
        } catch (Exception e) {
            context.fail(IngestState.FAILED);
            emitProgress(progressListener, context, "FAILED", null, null);
            log.error("Ingest failed for {}: {}", fileName, e.getMessage(), e);
            throw e;
        }
    }

    private void emitProgress(IngestProgressListener progressListener,
                              WorkflowExecutionContext<IngestState> context,
                              String currentStage,
                              Integer chunksTotal,
                              Integer chunksProcessed) {
        progressListener.onProgress(new IngestProgress(
                currentStage,
                chunksTotal,
                chunksProcessed,
                context.degraded(),
                context.degradeReasons()
        ));
    }

    private GraphWriteSummary writeKnowledgeGraph(List<ExtractionResult> extractions,
                                                  Map<String, DocumentChunk> chunkMap,
                                                  String fallbackSource) {
        int entityCount = 0;
        int relationCount = 0;
        deleteExistingSources(chunkMap, fallbackSource);
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
            if (extraction.getNotes() != null) {
                for (ExtractionResult.KnowledgeNote note : extraction.getNotes()) {
                    knowledgeGraph.upsertKnowledgeNote(note, source, chunkId, 1);
                }
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

    private void deleteExistingSources(Map<String, DocumentChunk> chunkMap, String fallbackSource) {
        Set<String> sources = new LinkedHashSet<>();
        for (DocumentChunk chunk : chunkMap.values()) {
            if (chunk.getMetadata() == null) {
                sources.add(fallbackSource);
            } else {
                sources.add(String.valueOf(chunk.getMetadata().getOrDefault("source", fallbackSource)));
            }
        }
        for (String source : sources) {
            knowledgeGraph.deleteBySource(source);
        }
    }

    private String resolveSourceIdentity(String fileName, String absolutePath) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName.trim();
        }
        return absolutePath;
    }

    private String resolveDisplaySource(String fileName, String absolutePath) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName.trim();
        }
        return absolutePath;
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
