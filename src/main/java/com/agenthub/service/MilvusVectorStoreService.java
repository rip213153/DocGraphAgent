package com.agenthub.service;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.QAResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Milvus 向量存储实现，作为 Java 版的主向量后端。
 */
@Service
@ConditionalOnProperty(name = "app.vector.backend", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    private static final String PRIMARY_FIELD = "chunk_id";
    private static final String VECTOR_FIELD = "embedding";
    private static final Gson GSON = new Gson();

    private final EmbeddingModel embeddingModel;

    @Value("${app.vector.milvus.host}")
    private String milvusHost;

    @Value("${app.vector.milvus.port}")
    private int milvusPort;

    @Value("${app.vector.milvus.collection}")
    private String collectionName;

    @Value("${app.vector.dimension:1536}")
    private int dimension;

    private MilvusClientV2 milvusClient;

    public MilvusVectorStoreService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void init() {
        milvusClient = new MilvusClientV2(
                ConnectConfig.builder()
                        .uri("http://" + milvusHost + ":" + milvusPort)
                        .build()
        );

        boolean exists = milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(collectionName).build()
        );

        if (!exists) {
            CreateCollectionReq createReq = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .description("AgentKnowledgeHub Java vector collection")
                    .dimension(dimension)
                    .primaryFieldName(PRIMARY_FIELD)
                    .idType(DataType.VarChar)
                    .maxLength(128)
                    .vectorFieldName(VECTOR_FIELD)
                    .metricType("COSINE")
                    .autoID(false)
                    .enableDynamicField(true)
                    .indexParam(IndexParam.builder()
                            .fieldName(VECTOR_FIELD)
                            .indexName(VECTOR_FIELD + "_idx")
                            .indexType(IndexParam.IndexType.AUTOINDEX)
                            .metricType(IndexParam.MetricType.COSINE)
                            .build())
                    .build();
            milvusClient.createCollection(createReq);
        }

        milvusClient.loadCollection(
                LoadCollectionReq.builder()
                        .collectionName(collectionName)
                        .build()
        );
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
        List<float[]> embeddings = embeddingModel.embed(texts);
        List<JsonObject> rows = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
            JsonObject object = new JsonObject();
            object.addProperty(PRIMARY_FIELD, chunk.getChunkId());
            object.addProperty("doc_id", chunk.getDocId());
            object.addProperty("content", chunk.getContent());
            object.addProperty("source", String.valueOf(metadata.getOrDefault("source", "")));
            object.addProperty("doc_type", chunk.getDocType());
            object.addProperty("chunk_index", chunk.getChunkIndex());

            List<Float> vector = new ArrayList<>(embeddings.get(i).length);
            for (float value : embeddings.get(i)) {
                vector.add(value);
            }
            object.add(VECTOR_FIELD, GSON.toJsonTree(vector));
            rows.add(object);
        }

        milvusClient.insert(InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build());
    }

    @Override
    public List<QAResult.RetrievedContext> search(String query, int topK) {
        float[] queryVec = embeddingModel.embed(query);
        SearchResp resp = milvusClient.search(SearchReq.builder()
                .collectionName(collectionName)
                .annsField(VECTOR_FIELD)
                .topK(topK)
                .outputFields(List.of("content", "source", "doc_id", "doc_type", "chunk_index"))
                .searchParams(Map.of("metric_type", "COSINE"))
                .data(List.of(new FloatVec(queryVec)))
                .build());

        List<QAResult.RetrievedContext> contexts = new ArrayList<>();
        for (List<SearchResp.SearchResult> batch : resp.getSearchResults()) {
            for (SearchResp.SearchResult result : batch) {
                Map<String, Object> entity = result.getEntity();
                contexts.add(QAResult.RetrievedContext.builder()
                        .content(String.valueOf(entity.getOrDefault("content", "")))
                        .source(String.valueOf(entity.getOrDefault("source", "milvus")))
                        .score(result.getScore() == null ? 0.0 : result.getScore())
                        .retrievalType("vector")
                        .metadata(entity)
                        .build());
            }
        }
        return contexts;
    }

    @Override
    public int deleteByDocId(String docId) {
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter("doc_id == \"" + escape(docId) + "\"")
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
                .map(ids -> "chunk_id in [" + ids + "]")
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
                .filter("doc_id == \"" + escape(docId) + "\"")
                .outputFields(List.of(PRIMARY_FIELD, "doc_id", "content", "source", "doc_type", "chunk_index"))
                .build());

        List<DocumentChunk> chunks = new ArrayList<>();
        for (QueryResp.QueryResult result : response.getQueryResults()) {
            Map<String, Object> row = result.getEntity();
            chunks.add(DocumentChunk.builder()
                    .chunkId(String.valueOf(row.getOrDefault(PRIMARY_FIELD, "")))
                    .docId(String.valueOf(row.getOrDefault("doc_id", "")))
                    .content(String.valueOf(row.getOrDefault("content", "")))
                    .docType(String.valueOf(row.getOrDefault("doc_type", "text")))
                    .chunkIndex(((Number) row.getOrDefault("chunk_index", 0)).intValue())
                    .metadata(Map.of(
                            "source", row.getOrDefault("source", ""),
                            "chunk_index", row.getOrDefault("chunk_index", 0),
                            "doc_type", row.getOrDefault("doc_type", "text")))
                    .build());
        }
        chunks.sort((left, right) -> Integer.compare(left.getChunkIndex(), right.getChunkIndex()));
        return chunks;
    }

    @Override
    public Map<String, Object> getStats() {
        return Map.of(
                "backend", "milvus",
                "collection", collectionName,
                "status", milvusClient != null && milvusClient.clientIsReady() ? "ready" : "unavailable"
        );
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }
}
