package com.agenthub.model;

import java.time.Instant;

public class DocumentChangeEvent {

    private String eventId;
    private String filePath;
    private String changeType;
    private Long documentVersion;
    private Long changeVersion;
    private Instant timestamp = Instant.now();
    private String sourceType;

    public DocumentChangeEvent() {
    }

    public DocumentChangeEvent(String eventId, String filePath, String changeType,
                               Long documentVersion, Long changeVersion,
                               Instant timestamp, String sourceType) {
        this.eventId = eventId;
        this.filePath = filePath;
        this.changeType = changeType;
        this.documentVersion = documentVersion;
        this.changeVersion = changeVersion;
        this.timestamp = timestamp == null ? Instant.now() : timestamp;
        this.sourceType = sourceType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }

    public Long getChangeVersion() {
        return changeVersion;
    }

    public void setChangeVersion(Long changeVersion) {
        this.changeVersion = changeVersion;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long effectiveVersion() {
        return changeVersion != null ? changeVersion : documentVersion;
    }

    public static final class Builder {
        private String eventId;
        private String filePath;
        private String changeType;
        private Long documentVersion;
        private Long changeVersion;
        private Instant timestamp = Instant.now();
        private String sourceType;

        private Builder() {
        }

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder changeType(String changeType) {
            this.changeType = changeType;
            return this;
        }

        public Builder documentVersion(Long documentVersion) {
            this.documentVersion = documentVersion;
            return this;
        }

        public Builder changeVersion(Long changeVersion) {
            this.changeVersion = changeVersion;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public DocumentChangeEvent build() {
            return new DocumentChangeEvent(eventId, filePath, changeType, documentVersion, changeVersion, timestamp, sourceType);
        }
    }
}
