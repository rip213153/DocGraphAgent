package com.agenthub.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisShortTermMemoryServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    private RedisShortTermMemoryService memoryService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        memoryService = new RedisShortTermMemoryService(stringRedisTemplate, objectMapper);
        ReflectionTestUtils.setField(memoryService, "readWindow", 10);
        ReflectionTestUtils.setField(memoryService, "maxMessages", 20);
        ReflectionTestUtils.setField(memoryService, "ttlHours", 48);
        ReflectionTestUtils.setField(memoryService, "maxContextChars", 120);
        ReflectionTestUtils.setField(memoryService, "maxMessageChars", 30);
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
    }

    @Test
    void shouldDeduplicateConsecutiveMessagesAndTrimPromptContext() throws Exception {
        String key = "agent:session:s-1:messages";
        ChatMemoryMessage first = message("s-1", "user", "  repeatable   content ");
        ChatMemoryMessage duplicate = message("s-1", "user", "repeatable content");
        ChatMemoryMessage second = message("s-1", "assistant",
                "This answer is intentionally very long so it should be truncated before entering the prompt context.");

        when(listOperations.range(key, -10, -1)).thenReturn(List.of(
                objectMapper.writeValueAsString(first),
                objectMapper.writeValueAsString(duplicate),
                objectMapper.writeValueAsString(second)
        ));

        String context = memoryService.buildPromptContext("s-1");

        assertThat(context).contains("user: repeatable content");
        assertThat(context).contains("assistant: This answer is intentionally");
        assertThat(context).contains("...");
        assertThat(context.lines().count()).isEqualTo(2);
        assertThat(context.length()).isLessThanOrEqualTo(120);
    }

    @Test
    void shouldReturnEmptyContextWhenRedisReadFails() {
        when(listOperations.range("agent:session:s-2:messages", -10, -1)).thenThrow(new RuntimeException("redis down"));

        String context = memoryService.buildPromptContext("s-2");

        assertThat(context).isEqualTo(MemoryContext.EMPTY_CONTEXT);
    }

    private ChatMemoryMessage message(String sessionId, String role, String content) {
        return ChatMemoryMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .timestamp(Instant.parse("2026-04-18T12:00:00Z"))
                .build();
    }
}
