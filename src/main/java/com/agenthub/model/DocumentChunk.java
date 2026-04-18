package com.agenthub.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public class DocumentChunk {

    private String chunkId;
    private String docId;
    private int chunkIndex;
    private String content;
    private String docType;
    private Map<String, Object> metadata;
    private float[] embedding;

    public DocumentChunk() {
    }

    public DocumentChunk(String chunkId, String docId, int chunkIndex, String content,
                         String docType, Map<String, Object> metadata, float[] embedding) {
        this.chunkId = chunkId;
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.docType = docType;
        this.metadata = metadata;
        this.embedding = embedding;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public String contentHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(content).hashCode());
        }
    }

    public String fingerprint() {
        return docId + "::" + chunkIndex + "::" + contentHash();
    }

    public static final class Builder {
        private String chunkId;
        private String docId;
        private int chunkIndex;
        private String content;
        private String docType;
        private Map<String, Object> metadata;
        private float[] embedding;

        private Builder() {
        }

        public Builder chunkId(String chunkId) {
            this.chunkId = chunkId;
            return this;
        }

        public Builder docId(String docId) {
            this.docId = docId;
            return this;
        }

        public Builder chunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder docType(String docType) {
            this.docType = docType;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder embedding(float[] embedding) {
            this.embedding = embedding;
            return this;
        }

        public DocumentChunk build() {
            return new DocumentChunk(chunkId, docId, chunkIndex, content, docType, metadata, embedding);
        }
    }
}
