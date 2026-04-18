package com.agenthub.memory;

import java.time.Instant;
import java.util.Map;

public class ChatMemoryMessage {

    private String sessionId;
    private String role;
    private String content;
    private Instant timestamp;
    private Map<String, Object> metadata = Map.of();

    public ChatMemoryMessage() {
    }

    public ChatMemoryMessage(String sessionId, String role, String content,
                             Instant timestamp, Map<String, Object> metadata) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.metadata = metadata == null ? Map.of() : metadata;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? Map.of() : metadata;
    }

    public static final class Builder {
        private String sessionId;
        private String role;
        private String content;
        private Instant timestamp;
        private Map<String, Object> metadata = Map.of();

        private Builder() {
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ChatMemoryMessage build() {
            return new ChatMemoryMessage(sessionId, role, content, timestamp, metadata);
        }
    }
}
