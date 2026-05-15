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

    @Test
    void shouldUseStableLogicalIdentityForRepeatedUploads() throws Exception {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        DocParserAgent docParserAgent = new DocParserAgent(chatClientBuilder);
        Path firstTempFile = Files.createTempFile("agenthub-doc-parser", ".md");
        Path secondTempFile = Files.createTempFile("agenthub-doc-parser", ".md");
        Files.writeString(firstTempFile, "# AQS\ncontent");
        Files.writeString(secondTempFile, "# AQS\ncontent");

        List<DocumentChunk> first = docParserAgent.parse(firstTempFile.toString(), "AQS.md", "AQS.md");
        List<DocumentChunk> second = docParserAgent.parse(secondTempFile.toString(), "AQS.md", "AQS.md");

        assertThat(first).isNotEmpty();
        assertThat(second).isNotEmpty();
        assertThat(first.getFirst().getDocId()).isEqualTo(second.getFirst().getDocId());
        assertThat(first.getFirst().getMetadata()).containsEntry("source", "AQS.md");
        assertThat(first.getFirst().getMetadata()).containsEntry("source_key", "aqs.md");
        assertThat(first.getFirst().getMetadata()).containsEntry("title", "AQS");
    }

    @Test
    void shouldSplitMarkdownByHeadingsAndParagraphsBeforeSlidingWindowFallback() throws Exception {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        DocParserAgent docParserAgent = new DocParserAgent(chatClientBuilder);
        Path tempFile = Files.createTempFile("agenthub-doc-parser", ".md");
        Files.writeString(tempFile, """
                # AQS
                AQS 是一个同步器框架。

                ## 核心组成
                state 表示同步状态。
                同步队列负责管理竞争线程。

                ## 双向链表原因
                线程阻塞时节点放在双向链表中，便于取消节点后修复前驱和后继关系。
                """);

        List<DocumentChunk> chunks = docParserAgent.parse(tempFile.toString());

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks.get(0).getContent()).contains("# AQS");
        assertThat(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("## 核心组成"))).isTrue();
        assertThat(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("## 双向链表原因"))).isTrue();
    }
}
