package com.agenthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashScopeEmbeddingServiceTest {

    @Test
    void shouldSplitEmbeddingRequestsIntoBatchesOfTen() throws Exception {
        RestClient.Builder builder = mock(RestClient.Builder.class, RETURNS_SELF);
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec request = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec requestBody = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
        ObjectMapper objectMapper = new ObjectMapper();
        var firstPayload = objectMapper.readTree("""
                {
                  "data": [
                    {"embedding": [0.1, 0.2]},
                    {"embedding": [1.1, 1.2]},
                    {"embedding": [2.1, 2.2]},
                    {"embedding": [3.1, 3.2]},
                    {"embedding": [4.1, 4.2]},
                    {"embedding": [5.1, 5.2]},
                    {"embedding": [6.1, 6.2]},
                    {"embedding": [7.1, 7.2]},
                    {"embedding": [8.1, 8.2]},
                    {"embedding": [9.1, 9.2]}
                  ]
                }
                """);
        var secondPayload = objectMapper.readTree("""
                {
                  "data": [
                    {"embedding": [10.1, 10.2]}
                  ]
                }
                """);

        when(builder.build()).thenReturn(restClient);
        when(restClient.post()).thenReturn(request);
        when(request.uri("/embeddings")).thenReturn(requestBody);
        when(requestBody.body(any(Map.class))).thenReturn(requestBody);
        when(requestBody.retrieve()).thenReturn(response);
        when(response.body(com.fasterxml.jackson.databind.JsonNode.class)).thenReturn(firstPayload, secondPayload);

        DashScopeEmbeddingService service = new DashScopeEmbeddingService(
                builder,
                objectMapper,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "test-key",
                "text-embedding-v3",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );

        List<String> inputs = IntStream.range(0, 11)
                .mapToObj(index -> "chunk-" + index)
                .toList();

        List<float[]> vectors = service.embed(inputs);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(requestBody, times(2)).body(payloadCaptor.capture());
        List<Map<String, Object>> payloads = payloadCaptor.getAllValues();

        assertThat(vectors).hasSize(11);
        assertThat(vectors.getFirst()).containsExactly(0.1f, 0.2f);
        assertThat(vectors.getLast()).containsExactly(10.1f, 10.2f);
        assertThat(payloads.getFirst()).containsEntry("model", "text-embedding-v3");
        assertThat(payloads.getFirst().get("input")).isEqualTo(inputs.subList(0, 10));
        assertThat(payloads.getLast().get("input")).isEqualTo(inputs.subList(10, 11));
    }
}
