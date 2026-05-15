package com.agenthub.agent;

import com.agenthub.model.DocumentChunk;
import com.agenthub.util.DocumentIdentity;
import org.apache.tika.Tika;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档解析 Agent (Java版)
 *
 * 使用 Apache Tika 实现多格式文档解析，
 * 支持 PDF / Word / Excel / 图片等格式。
 */
@Component
public class DocParserAgent {

    private static final int CHUNK_SIZE = 512;
    private static final int CHUNK_OVERLAP = 64;
    private static final int MARKDOWN_CHUNK_SIZE = 320;

    private final Tika tika = new Tika();
    @SuppressWarnings("unused")
    private final ChatClient chatClient;

    public DocParserAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<DocumentChunk> parse(String filePath) throws IOException {
        return parse(filePath, filePath, filePath);
    }

    public List<DocumentChunk> parse(String filePath, String sourceIdentity, String displaySource) throws IOException {
        File file = new File(filePath);
        String resolvedDisplaySource = DocumentIdentity.resolveDisplaySource(displaySource, filePath);
        String resolvedSourceIdentity = sourceIdentity == null || sourceIdentity.isBlank()
                ? resolvedDisplaySource
                : sourceIdentity.trim();
        String docId = DocumentIdentity.computeDocId(resolvedSourceIdentity);
        String docType = detectType(file);
        String sourceKey = DocumentIdentity.computeSourceKey(resolvedSourceIdentity);
        String title = DocumentIdentity.extractTitle(resolvedDisplaySource);

        String rawText = extractText(file);
        return chunkText(rawText, docId, docType, resolvedDisplaySource, sourceKey, title);
    }

    public List<DocumentChunk> parseBatch(List<String> filePaths) throws IOException {
        List<DocumentChunk> allChunks = new ArrayList<>();
        for (String path : filePaths) {
            allChunks.addAll(parse(path));
        }
        return allChunks;
    }

    private String extractText(File file) throws IOException {
        try {
            return tika.parseToString(file);
        } catch (Exception e) {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        }
    }

    private String detectType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".pdf")) {
            return "pdf";
        }
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image";
        }
        if (name.endsWith(".csv") || name.endsWith(".xlsx")) {
            return "table";
        }
        if (name.endsWith(".md")) {
            return "markdown";
        }
        return "text";
    }

    private List<DocumentChunk> chunkText(String text,
                                          String docId,
                                          String docType,
                                          String source,
                                          String sourceKey,
                                          String title) {
        if ("markdown".equals(docType)) {
            return chunkMarkdownText(text, docId, docType, source, sourceKey, title);
        }
        return chunkBySlidingWindow(text, docId, docType, source, sourceKey, title, CHUNK_SIZE);
    }

    private List<DocumentChunk> chunkMarkdownText(String text,
                                                  String docId,
                                                  String docType,
                                                  String source,
                                                  String sourceKey,
                                                  String title) {
        List<DocumentChunk> chunks = new ArrayList<>();
        List<String> sections = splitMarkdownSections(text);
        StringBuilder current = new StringBuilder();

        for (String section : sections) {
            String normalized = section == null ? "" : section.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && isMarkdownHeadingBlock(normalized)) {
                appendChunk(chunks, current.toString(), docId, docType, source, sourceKey, title);
                current.setLength(0);
            }
            if (current.length() > 0 && current.length() + 2 + normalized.length() > MARKDOWN_CHUNK_SIZE) {
                appendChunk(chunks, current.toString(), docId, docType, source, sourceKey, title);
                current.setLength(0);
            }
            if (normalized.length() > MARKDOWN_CHUNK_SIZE) {
                if (current.length() > 0) {
                    appendChunk(chunks, current.toString(), docId, docType, source, sourceKey, title);
                    current.setLength(0);
                }
                chunks.addAll(chunkBySlidingWindow(normalized, docId, docType, source, sourceKey, title, MARKDOWN_CHUNK_SIZE));
                continue;
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(normalized);
        }

        if (current.length() > 0) {
            appendChunk(chunks, current.toString(), docId, docType, source, sourceKey, title);
        }
        return chunks;
    }

    private List<String> splitMarkdownSections(String text) {
        List<String> sections = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sections;
        }

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            boolean boundary = trimmed.isEmpty() || isMarkdownHeading(trimmed);
            if (boundary && current.length() > 0) {
                sections.add(current.toString().trim());
                current.setLength(0);
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            sections.add(current.toString().trim());
        }
        return sections;
    }

    private boolean isMarkdownHeading(String line) {
        return line.startsWith("#") || line.startsWith("##") || line.startsWith("###");
    }

    private boolean isMarkdownHeadingBlock(String block) {
        int newlineIndex = block.indexOf('\n');
        String firstLine = newlineIndex >= 0 ? block.substring(0, newlineIndex).trim() : block.trim();
        return isMarkdownHeading(firstLine);
    }

    private List<DocumentChunk> chunkBySlidingWindow(String text,
                                                     String docId,
                                                     String docType,
                                                     String source,
                                                     String sourceKey,
                                                     String title,
                                                     int chunkSize) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String content = text.substring(start, end).trim();
            if (!content.isEmpty()) {
                appendChunk(chunks, content, docId, docType, source, sourceKey, title);
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }

    private void appendChunk(List<DocumentChunk> chunks,
                             String content,
                             String docId,
                             String docType,
                             String source,
                             String sourceKey,
                             String title) {
        int chunkIndex = chunks.size();
        chunks.add(DocumentChunk.builder()
                .chunkId(docId + "#chunk-" + chunkIndex)
                .docId(docId)
                .chunkIndex(chunkIndex)
                .content(content)
                .docType(docType)
                .metadata(Map.of(
                        "source", source,
                        "source_key", sourceKey,
                        "title", title,
                        "doc_type", docType,
                        "chunk_index", chunkIndex))
                .build());
    }
}
