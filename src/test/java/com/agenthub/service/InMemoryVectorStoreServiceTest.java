package com.agenthub.service;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.QAResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InMemoryVectorStoreServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private InMemoryVectorStoreService vectorStoreService;

    @BeforeEach
    void setUp() {
        vectorStoreService = new InMemoryVectorStoreService(embeddingModel);
    }

    @Test
    void shouldStoreSearchAndDeleteChunksByDocId() {
        DocumentChunk chunkA = DocumentChunk.builder()
                .chunkId("doc-1#0")
                .docId("doc-1")
                .content("Java agent architecture")
                .docType("text")
                .metadata(Map.of("source", "doc-a.md"))
                .build();
        DocumentChunk chunkB = DocumentChunk.builder()
                .chunkId("doc-2#0")
                .docId("doc-2")
                .content("Redis short-term memory")
                .docType("text")
                .metadata(Map.of("source", "doc-b.md"))
                .build();

        when(embeddingModel.embed(anyList())).thenReturn(List.of(
                new float[]{1.0f, 0.0f},
                new float[]{0.0f, 1.0f}
        ));
        when(embeddingModel.embed("java")).thenReturn(new float[]{1.0f, 0.0f});

        vectorStoreService.addChunks(List.of(chunkA, chunkB));

        List<QAResult.RetrievedContext> contexts = vectorStoreService.search("java", 1);
        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().getContent()).isEqualTo("Java agent architecture");
        assertThat(contexts.getFirst().getSource()).isEqualTo("doc-a.md");

        int deleted = vectorStoreService.deleteByDocId("doc-1");
        assertThat(deleted).isEqualTo(1);
        List<QAResult.RetrievedContext> remaining = vectorStoreService.search("java", 5);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().getContent()).isEqualTo("Redis short-term memory");
        assertThat(vectorStoreService.getStats()).containsEntry("totalVectors", 1);
    }
}
