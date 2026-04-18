package com.agenthub.memory;

import java.util.List;

public interface ShortTermMemoryService {

    record LoadResult(String promptContext, boolean available) {
    }

    void appendMessage(ChatMemoryMessage message);

    List<ChatMemoryMessage> getRecentMessages(String sessionId);

    String buildPromptContext(String sessionId);

    default LoadResult loadContext(String sessionId) {
        return new LoadResult(buildPromptContext(sessionId), true);
    }
}
