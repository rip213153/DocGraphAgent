package com.agenthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashScopeChatServiceTest {

    @Test
    void shouldReturnTextualChatContent() {
        RestClient.Builder builder = mock(RestClient.Builder.class, RETURNS_SELF);
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec request = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec requestBody = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("choices")
                .addObject()
                .putObject("message")
                .put("content", "answer from dashscope");

        when(builder.build()).thenReturn(restClient);
        when(restClient.post()).thenReturn(request);
        when(request.uri("/chat/completions")).thenReturn(requestBody);
        when(requestBody.body(any(Map.class))).thenReturn(requestBody);
        when(requestBody.retrieve()).thenReturn(response);
        when(response.body(com.fasterxml.jackson.databind.JsonNode.class)).thenReturn(payload);

        DashScopeChatService service = new DashScopeChatService(
                builder,
                objectMapper,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "test-key",
                "qwen-plus",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );

        String answer = service.chat("system", "user");

        assertThat(answer).isEqualTo("answer from dashscope");
    }
}
