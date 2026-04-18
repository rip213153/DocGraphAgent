package com.agenthub.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphServiceTest {

    @Test
    void shouldBuildStableEntityKeysPerSource() {
        String first = KnowledgeGraphService.buildEntityKey("Redis", "E:/docs/a.md");
        String second = KnowledgeGraphService.buildEntityKey("Redis", "E:/docs/b.md");
        String third = KnowledgeGraphService.buildEntityKey("Redis", "E:/docs/a.md");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isEqualTo(third);
    }

    @Test
    void shouldNormalizeEntityKeyComponents() {
        String key = KnowledgeGraphService.buildEntityKey("Agent Knowledge Hub", "E:/Docs/My File.md");

        assertThat(key).contains("e_docs_my_file_md");
        assertThat(key).contains("agent_knowledge_hub");
    }
}
