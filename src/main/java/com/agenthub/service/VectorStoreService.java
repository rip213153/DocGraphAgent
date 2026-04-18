package com.agenthub.service;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.QAResult;

import java.util.List;
import java.util.Map;

public interface VectorStoreService {
    void addChunks(List<DocumentChunk> chunks);

    List<QAResult.RetrievedContext> search(String query, int topK);

    int deleteByDocId(String docId);

    int deleteByChunkIds(List<String> chunkIds);

    List<DocumentChunk> getChunksByDocId(String docId);

    Map<String, Object> getStats();
}
