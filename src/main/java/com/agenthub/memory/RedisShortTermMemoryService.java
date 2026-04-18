package com.agenthub.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class RedisShortTermMemoryService implements ShortTermMemoryService {

    private static final String MSG_KEY = "agent:session:%s:messages";
    private static final Logger log = LoggerFactory.getLogger(RedisShortTermMemoryService.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisShortTermMemoryService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${app.memory.short-term.max-messages:20}")
    private int maxMessages;

    @Value("${app.memory.short-term.read-window:10}")
    private int readWindow;

    @Value("${app.memory.short-term.ttl-hours:48}")
    private int ttlHours;

    @Value("${app.memory.short-term.max-context-chars:2000}")
    private int maxContextChars;

    @Value("${app.memory.short-term.max-message-chars:300}")
    private int maxMessageChars;

    @Override
    public void appendMessage(ChatMemoryMessage message) {
        String key = MSG_KEY.formatted(message.getSessionId());
        try {
            String json = objectMapper.writeValueAsString(message);
            stringRedisTemplate.opsForList().rightPush(key, json);
            stringRedisTemplate.opsForList().trim(key, -maxMessages, -1);
            stringRedisTemplate.expire(key, ttlHours, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat memory message", e);
        } catch (Exception e) {
            log.warn("Short-term memory append skipped: {}", e.getMessage());
        }
    }

    @Override
    public List<ChatMemoryMessage> getRecentMessages(String sessionId) {
        try {
            return loadMessages(sessionId);
        } catch (Exception e) {
            log.warn("Short-term memory read skipped: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String buildPromptContext(String sessionId) {
        return loadContext(sessionId).promptContext();
    }

    @Override
    public LoadResult loadContext(String sessionId) {
        List<ChatMemoryMessage> messages;
        try {
            messages = loadMessages(sessionId);
        } catch (Exception e) {
            log.warn("Short-term memory read skipped: {}", e.getMessage());
            return new LoadResult(MemoryContext.EMPTY_CONTEXT, false);
        }
        if (messages.isEmpty()) {
            return new LoadResult(MemoryContext.EMPTY_CONTEXT, true);
        }

        StringBuilder builder = new StringBuilder();
        String lastFingerprint = null;
        for (ChatMemoryMessage message : messages) {
            String normalizedContent = normalizeMessageContent(message.getContent());
            if (normalizedContent.isBlank()) {
                continue;
            }
            String fingerprint = message.getRole() + "::" + normalizedContent;
            if (fingerprint.equals(lastFingerprint)) {
                continue;
            }
            String line = message.getRole()
                    + ": "
                    + normalizedContent
                    + "\n";
            if (builder.length() + line.length() > maxContextChars && builder.length() > 0) {
                break;
            }
            if (line.length() > maxContextChars && builder.length() == 0) {
                builder.append(line, 0, Math.min(line.length(), maxContextChars));
                break;
            }
            builder.append(line);
            lastFingerprint = fingerprint;
        }
        return new LoadResult(builder.toString().trim(), true);
    }

    private String normalizeMessageContent(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() > maxMessageChars) {
            return normalized.substring(0, maxMessageChars) + "...";
        }
        return normalized;
    }

    private List<ChatMemoryMessage> loadMessages(String sessionId) {
        String key = MSG_KEY.formatted(sessionId);
        List<ChatMemoryMessage> messages = new ArrayList<>();
        List<String> raw = stringRedisTemplate.opsForList().range(key, -readWindow, -1);
        if (raw == null) {
            return messages;
        }
        for (String line : raw) {
            try {
                messages.add(objectMapper.readValue(line, ChatMemoryMessage.class));
            } catch (JsonProcessingException ignored) {
                log.debug("Skip malformed chat memory record");
            }
        }
        return messages;
    }
}
