package com.agenthub.service;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.QAResult;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储实现，用于轻量运行和回退场景。
 */
@Service
@ConditionalOnProperty(name = "app.vector.backend", havingValue = "in-memory")
public class InMemoryVectorStoreService implements VectorStoreService {

    private final EmbeddingModel embeddingModel;
    private final Map<String, StoredVector> store = new ConcurrentHashMap<>();

    public InMemoryVectorStoreService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void addChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<String> texts = chunks.stream().map(DocumentChunk::getContent).toList();
        List<float[]> embeddings = embeddingModel.embed(texts);

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            float[] vector = embeddings.get(i);
            Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
            store.put(chunk.getChunkId(), new StoredVector(
                    chunk.getChunkId(),
                    chunk.getDocId(),
                    chunk.getContent(),
                    metadata,
                    vector
            ));
        }
    }

    @Override
    public List<QAResult.RetrievedContext> search(String query, int topK) {
        float[] queryVec = embeddingModel.embed(query);

        return store.values().stream()
                .map(sv -> Map.entry(sv, cosineSimilarity(queryVec, sv.vector)))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(entry -> QAResult.RetrievedContext.builder()
                        .content(entry.getKey().content)
                        .source(String.valueOf(entry.getKey().metadata.getOrDefault("source", "vector_store")))
                        .score(entry.getValue())
                        .retrievalType("vector")
                        .metadata(entry.getKey().metadata)
                        .build())
                .toList();
    }

    @Override
    public int deleteByDocId(String docId) {
        List<String> toDelete = store.entrySet().stream()
                .filter(e -> e.getValue().docId.equals(docId))
                .map(Map.Entry::getKey)
                .toList();
        toDelete.forEach(store::remove);
        return toDelete.size();
    }

    @Override
    public int deleteByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (String chunkId : chunkIds) {
            if (store.remove(chunkId) != null) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public List<DocumentChunk> getChunksByDocId(String docId) {
        return store.values().stream()
                .filter(e -> e.docId.equals(docId))
                .sorted((left, right) -> Integer.compare(
                        ((Number) left.metadata.getOrDefault("chunk_index", 0)).intValue(),
                        ((Number) right.metadata.getOrDefault("chunk_index", 0)).intValue()))
                .map(stored -> DocumentChunk.builder()
                        .chunkId(stored.chunkId)
                        .docId(stored.docId)
                        .chunkIndex(((Number) stored.metadata.getOrDefault("chunk_index", 0)).intValue())
                        .content(stored.content)
                        .docType(String.valueOf(stored.metadata.getOrDefault("doc_type", "text")))
                        .metadata(stored.metadata)
                        .build())
                .toList();
    }

    @Override
    public Map<String, Object> getStats() {
        return Map.of("backend", "in-memory", "totalVectors", store.size());
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
    }

    private record StoredVector(String chunkId, String docId, String content,
                                Map<String, Object> metadata, float[] vector) {
    }
}
