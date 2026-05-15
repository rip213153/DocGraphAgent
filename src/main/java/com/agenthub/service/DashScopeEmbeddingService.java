package com.agenthub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DashScopeEmbeddingService {

    private static final int MAX_BATCH_SIZE = 10;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public DashScopeEmbeddingService(RestClient.Builder restClientBuilder,
                                     ObjectMapper objectMapper,
                                     @Value("${spring.ai.openai.base-url}") String baseUrl,
                                     @Value("${spring.ai.openai.api-key}") String apiKey,
                                     @Value("${spring.ai.openai.embedding.options.model}") String model,
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

    public List<float[]> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < inputs.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, inputs.size());
            JsonNode response = restClient.post()
                    .uri("/embeddings")
                    .body(Map.of(
                            "model", model,
                            "input", inputs.subList(start, end)
                    ))
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.has("data")) {
                throw new IllegalStateException("Invalid embedding response: " + safeJson(response));
            }

            for (JsonNode item : response.path("data")) {
                JsonNode embeddingNode = item.path("embedding");
                float[] vector = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    vector[i] = (float) embeddingNode.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }

    public float[] embed(String input) {
        List<float[]> vectors = embed(List.of(input));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("Empty embedding response");
        }
        return vectors.getFirst();
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
