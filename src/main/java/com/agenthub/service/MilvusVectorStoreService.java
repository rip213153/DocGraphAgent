package com.agenthub.service;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.QAResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.BaseRanker;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.request.ranker.WeightedRanker;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 向量存储实现。
 *
 * 默认使用 Milvus 2.5 的 dense + BM25 hybrid schema。
 */
@Service
@ConditionalOnProperty(name = "app.vector.backend", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStoreService.class);
    private static final String PRIMARY_FIELD = "chunk_id";
    private static final String DOC_ID_FIELD = "doc_id";
    private static final String SOURCE_FIELD = "source";
    private static final String SOURCE_KEY_FIELD = "source_key";
    private static final String TITLE_FIELD = "title";
    private static final String DOC_TYPE_FIELD = "doc_type";
    private static final String CHUNK_INDEX_FIELD = "chunk_index";
    private static final int DEFAULT_VARCHAR_LENGTH = 1024;
    private static final int CONTENT_VARCHAR_LENGTH = 16384;
    private static final Gson GSON = new Gson();

    private final DashScopeEmbeddingService embeddingService;

    @Value("${app.vector.milvus.host}")
    private String milvusHost;

    @Value("${app.vector.milvus.port}")
    private int milvusPort;

    @Value("${app.vector.milvus.collection}")
    private String collectionName;

    @Value("${app.vector.dimension:1536}")
    private int dimension;

    @Value("${app.vector.milvus.hybrid-enabled:true}")
    private boolean hybridEnabled;

    @Value("${app.vector.milvus.text-field:content}")
    private String textField;

    @Value("${app.vector.milvus.dense-field:dense_embedding}")
    private String denseVectorField;

    @Value("${app.vector.milvus.sparse-field:content_sparse}")
    private String sparseVectorField;

    @Value("${app.vector.milvus.analyzer-type:chinese}")
    private String analyzerType;

    @Value("${app.vector.milvus.ranker:rrf}")
    private String rankerType;

    @Value("${app.vector.milvus.rrf-k:60}")
    private int rrfK;

    @Value("${app.vector.milvus.dense-weight:0.65}")
    private float denseWeight;

    @Value("${app.vector.milvus.sparse-weight:1.0}")
    private float sparseWeight;

    @Value("${app.vector.milvus.candidate-multiplier:4}")
    private int candidateMultiplier;

    @Value("${app.vector.milvus.startup.max-attempts:12}")
    private int startupMaxAttempts;

    @Value("${app.vector.milvus.startup.retry-delay-ms:3000}")
    private long startupRetryDelayMs;

    private MilvusClientV2 milvusClient;

    public MilvusVectorStoreService(DashScopeEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void init() {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= Math.max(1, startupMaxAttempts); attempt++) {
            MilvusClientV2 candidate = null;
            try {
                candidate = new MilvusClientV2(
                        ConnectConfig.builder()
                                .uri("http://" + milvusHost + ":" + milvusPort)
                                .build()
                );
                initializeCollection(candidate);
                milvusClient = candidate;
                if (attempt > 1) {
                    log.info("Milvus became ready on startup attempt {}", attempt);
                }
                return;
            } catch (Exception e) {
                lastFailure = e;
                if (candidate != null) {
                    candidate.close();
                }
                if (attempt == startupMaxAttempts) {
                    break;
                }
                log.warn("Milvus not ready on startup attempt {}/{}: {}", attempt, startupMaxAttempts, e.getMessage());
                sleepBeforeRetry();
            }
        }
        throw new IllegalStateException("Milvus did not become ready after " + startupMaxAttempts + " startup attempts", lastFailure);
    }

    private void initializeCollection(MilvusClientV2 client) {
        boolean exists = client.hasCollection(
                HasCollectionReq.builder().collectionName(collectionName).build()
        );

        if (!exists) {
            CreateCollectionReq.CollectionSchema schema = client.createSchema();
            schema.setEnableDynamicField(true);
            schema.addField(AddFieldReq.builder()
                    .fieldName(PRIMARY_FIELD)
                    .dataType(DataType.VarChar)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .maxLength(128)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(DOC_ID_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(128)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(SOURCE_KEY_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(DEFAULT_VARCHAR_LENGTH)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(SOURCE_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(DEFAULT_VARCHAR_LENGTH)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(TITLE_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(512)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(DOC_TYPE_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(128)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(CHUNK_INDEX_FIELD)
                    .dataType(DataType.Int64)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(textField)
                    .dataType(DataType.VarChar)
                    .maxLength(CONTENT_VARCHAR_LENGTH)
                    .enableAnalyzer(true)
                    .enableMatch(true)
                    .analyzerParams(Map.of("type", analyzerType))
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(denseVectorField)
                    .dataType(DataType.FloatVector)
                    .dimension(dimension)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(sparseVectorField)
                    .dataType(DataType.SparseFloatVector)
                    .build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .name("content_bm25_fn")
                    .description("Built-in BM25 sparse vector generation")
                    .functionType(FunctionType.BM25)
                    .inputFieldNames(List.of(textField))
                    .outputFieldNames(List.of(sparseVectorField))
                    .build());

            List<IndexParam> indexParams = List.of(
                    IndexParam.builder()
                            .fieldName(denseVectorField)
                            .indexName(denseVectorField + "_idx")
                            .indexType(IndexParam.IndexType.AUTOINDEX)
                            .metricType(IndexParam.MetricType.COSINE)
                            .build(),
                    IndexParam.builder()
                            .fieldName(sparseVectorField)
                            .indexName(sparseVectorField + "_idx")
                            .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                            .metricType(IndexParam.MetricType.BM25)
                            .build()
            );

            CreateCollectionReq createReq = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .description("AgentKnowledgeHub Java hybrid vector collection")
                    .enableDynamicField(true)
                    .collectionSchema(schema)
                    .indexParams(indexParams)
                    .build();
            client.createCollection(createReq);
        }

        client.loadCollection(
                LoadCollectionReq.builder()
                        .collectionName(collectionName)
                        .build()
        );
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(Math.max(0L, startupRetryDelayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Milvus startup", e);
        }
    }

    @PreDestroy
    public void close() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    @Override
    public void addChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<String> texts = chunks.stream().map(DocumentChunk::getContent).toList();
        List<float[]> embeddings = embeddingService.embed(texts);
        List<JsonObject> rows = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
            JsonObject object = new JsonObject();
            object.addProperty(PRIMARY_FIELD, chunk.getChunkId());
            object.addProperty(DOC_ID_FIELD, chunk.getDocId());
            object.addProperty(textField, chunk.getContent());
            object.addProperty(SOURCE_FIELD, String.valueOf(metadata.getOrDefault(SOURCE_FIELD, "")));
            object.addProperty(SOURCE_KEY_FIELD, String.valueOf(metadata.getOrDefault(SOURCE_KEY_FIELD, "")));
            object.addProperty(TITLE_FIELD, String.valueOf(metadata.getOrDefault(TITLE_FIELD, "")));
            object.addProperty(DOC_TYPE_FIELD, chunk.getDocType());
            object.addProperty(CHUNK_INDEX_FIELD, chunk.getChunkIndex());

            List<Float> vector = new ArrayList<>(embeddings.get(i).length);
            for (float value : embeddings.get(i)) {
                vector.add(value);
            }
            object.add(denseVectorField, GSON.toJsonTree(vector));
            rows.add(object);
        }

        milvusClient.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build());
    }

    @Override
    public List<QAResult.RetrievedContext> search(String query, int topK) {
        if (hybridEnabled) {
            try {
                return hybridSearch(query, topK);
            } catch (Exception e) {
                log.warn("Milvus hybrid search failed, falling back to dense-only search: {}", e.getMessage());
            }
        }
        return denseSearch(query, topK);
    }

    private List<QAResult.RetrievedContext> denseSearch(String query, int topK) {
        float[] queryVec = embeddingService.embed(query);
        SearchResp resp = milvusClient.search(SearchReq.builder()
                .collectionName(collectionName)
                .annsField(denseVectorField)
                .topK(topK)
                .outputFields(outputFields())
                .searchParams(Map.of("metric_type", "COSINE"))
                .data(List.of(new FloatVec(queryVec)))
                .build());
        return toRetrievedContexts(resp, "dense");
    }

    private List<QAResult.RetrievedContext> hybridSearch(String query, int topK) {
        float[] queryVec = embeddingService.embed(query);
        int candidateLimit = Math.max(topK, topK * Math.max(1, candidateMultiplier));

        AnnSearchReq denseRequest = AnnSearchReq.builder()
                .vectorFieldName(denseVectorField)
                .vectors(List.of(new FloatVec(queryVec)))
                .limit(candidateLimit)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        AnnSearchReq sparseRequest = AnnSearchReq.builder()
                .vectorFieldName(sparseVectorField)
                .vectors(List.of(new EmbeddedText(query)))
                .limit(candidateLimit)
                .metricType(IndexParam.MetricType.BM25)
                .build();

        SearchResp resp = milvusClient.hybridSearch(HybridSearchReq.builder()
                .collectionName(collectionName)
                .searchRequests(List.of(denseRequest, sparseRequest))
                .ranker(buildRanker())
                .outFields(outputFields())
                .limit(topK)
                .build());
        return toRetrievedContexts(resp, "dense+bm25");
    }

    private BaseRanker buildRanker() {
        if ("weighted".equalsIgnoreCase(rankerType)) {
            return new WeightedRanker(List.of(denseWeight, sparseWeight));
        }
        return new RRFRanker(Math.max(1, rrfK));
    }

    private List<QAResult.RetrievedContext> toRetrievedContexts(SearchResp resp, String retrievalMode) {
        List<QAResult.RetrievedContext> contexts = new ArrayList<>();
        for (List<SearchResp.SearchResult> batch : resp.getSearchResults()) {
            for (SearchResp.SearchResult result : batch) {
                Map<String, Object> entity = result.getEntity();
                Map<String, Object> metadata = new LinkedHashMap<>(entity);
                metadata.put("retrieval_mode", retrievalMode);
                contexts.add(QAResult.RetrievedContext.builder()
                        .content(String.valueOf(entity.getOrDefault(textField, "")))
                        .source(String.valueOf(entity.getOrDefault(SOURCE_FIELD, "milvus")))
                        .score(result.getScore() == null ? 0.0 : result.getScore())
                        .retrievalType("vector")
                        .metadata(metadata)
                        .build());
            }
        }
        return contexts;
    }

    @Override
    public int deleteByDocId(String docId) {
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter(DOC_ID_FIELD + " == \"" + escape(docId) + "\"")
                .build());
        return 0;
    }

    @Override
    public int deleteByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        String filter = chunkIds.stream()
                .map(id -> "\"" + escape(id) + "\"")
                .reduce((left, right) -> left + ", " + right)
                .map(ids -> PRIMARY_FIELD + " in [" + ids + "]")
                .orElse("");
        if (filter.isBlank()) {
            return 0;
        }
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter(filter)
                .build());
        return chunkIds.size();
    }

    @Override
    public List<DocumentChunk> getChunksByDocId(String docId) {
        QueryResp response = milvusClient.query(QueryReq.builder()
                .collectionName(collectionName)
                .filter(DOC_ID_FIELD + " == \"" + escape(docId) + "\"")
                .outputFields(outputFields())
                .build());

        List<DocumentChunk> chunks = new ArrayList<>();
        for (QueryResp.QueryResult result : response.getQueryResults()) {
            Map<String, Object> row = result.getEntity();
            chunks.add(DocumentChunk.builder()
                    .chunkId(String.valueOf(row.getOrDefault(PRIMARY_FIELD, "")))
                    .docId(String.valueOf(row.getOrDefault(DOC_ID_FIELD, "")))
                    .content(String.valueOf(row.getOrDefault(textField, "")))
                    .docType(String.valueOf(row.getOrDefault(DOC_TYPE_FIELD, "text")))
                    .chunkIndex(((Number) row.getOrDefault(CHUNK_INDEX_FIELD, 0)).intValue())
                    .metadata(Map.of(
                            SOURCE_FIELD, row.getOrDefault(SOURCE_FIELD, ""),
                            SOURCE_KEY_FIELD, row.getOrDefault(SOURCE_KEY_FIELD, ""),
                            TITLE_FIELD, row.getOrDefault(TITLE_FIELD, ""),
                            CHUNK_INDEX_FIELD, row.getOrDefault(CHUNK_INDEX_FIELD, 0),
                            DOC_TYPE_FIELD, row.getOrDefault(DOC_TYPE_FIELD, "text")))
                    .build());
        }
        chunks.sort((left, right) -> Integer.compare(left.getChunkIndex(), right.getChunkIndex()));
        return chunks;
    }

    @Override
    public Map<String, Object> getStats() {
        String serverVersion = "unknown";
        try {
            if (milvusClient != null) {
                serverVersion = milvusClient.getServerVersion();
            }
        } catch (Exception e) {
            log.debug("Unable to read Milvus server version: {}", e.getMessage());
        }
        return Map.of(
                "backend", "milvus",
                "collection", collectionName,
                "status", milvusClient != null && milvusClient.clientIsReady() ? "ready" : "unavailable",
                "serverVersion", serverVersion,
                "hybridEnabled", hybridEnabled,
                "retrievalMode", hybridEnabled ? "dense+bm25" : "dense-only",
                "denseField", denseVectorField,
                "sparseField", sparseVectorField,
                "textField", textField
        );
    }

    private List<String> outputFields() {
        return List.of(
                PRIMARY_FIELD,
                DOC_ID_FIELD,
                textField,
                SOURCE_FIELD,
                SOURCE_KEY_FIELD,
                TITLE_FIELD,
                DOC_TYPE_FIELD,
                CHUNK_INDEX_FIELD
        );
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }
}
