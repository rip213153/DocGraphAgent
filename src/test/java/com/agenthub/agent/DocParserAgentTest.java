package com.agenthub.agent;

import com.agenthub.model.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocParserAgentTest {

    @Test
    void shouldNotLoopForeverWhenDocumentIsShorterThanChunkSize() throws Exception {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        DocParserAgent docParserAgent = new DocParserAgent(chatClientBuilder);
        Path tempFile = Files.createTempFile("agenthub-doc-parser", ".txt");
        Files.writeString(tempFile, "short content for parser");

        List<DocumentChunk> chunks = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> docParserAgent.parse(tempFile.toString()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getContent()).contains("short content");
        assertThat(chunks.getFirst().getMetadata()).containsEntry("source", tempFile.toString());
    }
}
