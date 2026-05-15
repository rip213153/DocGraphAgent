package com.agenthub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class DashScopeChatService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public DashScopeChatService(RestClient.Builder restClientBuilder,
                                ObjectMapper objectMapper,
                                @Value("${spring.ai.openai.base-url}") String baseUrl,
                                @Value("${spring.ai.openai.api-key}") String apiKey,
                                @Value("${spring.ai.openai.chat.options.model}") String model,
                                @Value("${app.llm.connect-timeout:PT20S}") Duration connectTimeout,
                                @Value("${app.llm.read-timeout:PT120S}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());

        this.restClient = restClientBuilder
                .baseUrl(normalizeBaseUrl(baseUrl))
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
    }

    public String chat(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0
        );

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Empty response from chat completion API");
        }

        JsonNode contentNode = response.path("choices").path(0).path("message").path("content");
        String content = extractContent(contentNode);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Missing content in chat completion response: " + safeJson(response));
        }
        return content;
    }

    private String extractContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : contentNode) {
                String text = item.path("text").asText("");
                if (!text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        return contentNode.toString();
    }

    private String safeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return String.valueOf(node);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("spring.ai.openai.base-url must not be blank");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
