package com.agenthub.agent;

import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.agenthub.service.DashScopeChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeExtractAgentTest {

    private ExecutorService workflowExecutor;
    private DashScopeChatService chatService;

    @BeforeEach
    void setUp() {
        workflowExecutor = Executors.newVirtualThreadPerTaskExecutor();
        chatService = Mockito.mock(DashScopeChatService.class);
    }

    @AfterEach
    void tearDown() {
        workflowExecutor.shutdownNow();
    }

    @Test
    void shouldPreserveChunkOrderWhenParallelExtractionCompletesOutOfOrder() {
        StubKnowledgeExtractAgent agent = new StubKnowledgeExtractAgent(
                chatService,
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

    @Test
    void shouldGenerateHeuristicNotesWhenModelDoesNotReturnNotes() {
        Mockito.when(chatService.chat(Mockito.anyString(), Mockito.anyString())).thenReturn("""
                {
                  "entities": [
                    {"name": "AQS", "type": "Technology", "description": "A synchronization framework centered on state and queue coordination."},
                    {"name": "state", "type": "Concept", "description": "Represents synchronization state."},
                    {"name": "同步队列", "type": "Concept", "description": "Stores waiting threads."}
                  ],
                  "relations": []
                }
                """);
        KnowledgeExtractAgent agent = new KnowledgeExtractAgent(chatService, workflowExecutor, 1);

        ExtractionResult result = agent.extract(List.of(chunk(0, "AQS的三大核心属性，state，等待线程的同步队列"))).getFirst();

        assertThat(result.getNotes()).isNotEmpty();
        assertThat(result.getNotes()).anyMatch(note -> "definition".equalsIgnoreCase(note.getKind()));
        assertThat(result.getNotes()).anyMatch(note -> "principle".equalsIgnoreCase(note.getKind()));
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

        private StubKnowledgeExtractAgent(DashScopeChatService chatService,
                                          ExecutorService workflowExecutor,
                                          int extractMaxConcurrency,
                                          Map<String, Long> delays) {
            super(chatService, workflowExecutor, extractMaxConcurrency);
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
