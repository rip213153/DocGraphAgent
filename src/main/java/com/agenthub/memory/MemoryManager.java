package com.agenthub.memory;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class MemoryManager {

    public record MemoryLoadResult(MemoryContext context, boolean available) {
    }

    private final ShortTermMemoryService shortTermMemoryService;

    public MemoryManager(ShortTermMemoryService shortTermMemoryService) {
        this.shortTermMemoryService = shortTermMemoryService;
    }

    public MemoryContext loadShortTermContext(String sessionId) {
        return loadShortTermContextResult(sessionId).context();
    }

    public MemoryLoadResult loadShortTermContextResult(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new MemoryLoadResult(MemoryContext.builder().build(), true);
        }
        ShortTermMemoryService.LoadResult result = shortTermMemoryService.loadContext(sessionId);
        return new MemoryLoadResult(
                MemoryContext.builder().shortTermContext(result.promptContext()).build(),
                result.available()
        );
    }

    public void appendUserMessage(String sessionId, String text) {
        append(sessionId, "user", text);
    }

    public void appendAssistantMessage(String sessionId, String text) {
        append(sessionId, "assistant", text);
    }

    private void append(String sessionId, String role, String text) {
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            return;
        }

        shortTermMemoryService.appendMessage(ChatMemoryMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(text)
                .timestamp(Instant.now())
                .metadata(Map.of())
                .build());
    }
}
