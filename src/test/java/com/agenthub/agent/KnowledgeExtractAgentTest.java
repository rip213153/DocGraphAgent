package com.agenthub.agent;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeExtractAgentTest {

    private ExecutorService workflowExecutor;
    private ChatClient.Builder chatClientBuilder;

    @BeforeEach
    void setUp() {
        workflowExecutor = Executors.newVirtualThreadPerTaskExecutor();
        chatClientBuilder = Mockito.mock(ChatClient.Builder.class);
        Mockito.when(chatClientBuilder.build()).thenReturn(Mockito.mock(ChatClient.class));
    }

    @AfterEach
    void tearDown() {
        workflowExecutor.shutdownNow();
    }

    @Test
    void shouldPreserveChunkOrderWhenParallelExtractionCompletesOutOfOrder() {
        StubKnowledgeExtractAgent agent = new StubKnowledgeExtractAgent(
                chatClientBuilder,
                workflowExecutor,
                2,
                Map.of("doc-1#0", 120L, "doc-1#1", 20L, "doc-1#2", 60L)
        );
        List<DocumentChunk> chunks = List.of(
                chunk(0, "chunk-0"),
                chunk(1, "chunk-1"),
                chunk(2, "chunk-2")
        );

        List<ExtractionResult> results = agent.extract(chunks);

        assertThat(results).extracting(ExtractionResult::getSourceChunkId)
                .containsExactly("doc-1#0", "doc-1#1", "doc-1#2");
    }

    private DocumentChunk chunk(int chunkIndex, String content) {
        return DocumentChunk.builder()
                .chunkId("doc-1#" + chunkIndex)
                .docId("doc-1")
                .chunkIndex(chunkIndex)
                .content(content)
                .docType("markdown")
                .metadata(Map.of("source", "E:/docs/agent.md"))
                .build();
    }

    private static final class StubKnowledgeExtractAgent extends KnowledgeExtractAgent {

        private final Map<String, Long> delays;

        private StubKnowledgeExtractAgent(ChatClient.Builder chatClientBuilder,
                                          ExecutorService workflowExecutor,
                                          int extractMaxConcurrency,
                                          Map<String, Long> delays) {
            super(chatClientBuilder, workflowExecutor, extractMaxConcurrency);
            this.delays = delays;
        }

        @Override
        protected ExtractionResult extractFromChunk(DocumentChunk chunk) {
            sleep(delays.getOrDefault(chunk.getChunkId(), 0L));
            return ExtractionResult.builder()
                    .entities(List.of(ExtractionResult.Entity.builder()
                            .name(chunk.getChunkId())
                            .type("Concept")
                            .description(chunk.getContent())
                            .build()))
                    .relations(List.of())
                    .sourceChunkId(chunk.getChunkId())
                    .build();
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted in test extractor", e);
            }
        }
    }
}
