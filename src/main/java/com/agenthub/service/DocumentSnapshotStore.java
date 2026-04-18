package com.agenthub.service;

import com.agenthub.model.DocumentChunk;

import java.util.List;

public interface DocumentSnapshotStore {
    void saveChunks(String docId, List<DocumentChunk> chunks);

    List<DocumentChunk> getChunks(String docId);

    void delete(String docId);
}
