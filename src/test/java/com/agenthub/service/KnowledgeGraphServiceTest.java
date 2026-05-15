package com.agenthub.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphServiceTest {

    @Test
    void shouldPreferStableSourceOverHistoricalTempUploadForSameLogicalDocument() {
        List<Map<String, Object>> rows = List.of(
                Map.of(
                        "entity_key", "c_users_rip_appdata_local_temp_uploads123_aqs_md::aqs",
                        "name", "AQS",
                        "source", "C:/Users/rip/AppData/Local/Temp/uploads123/AQS.md",
                        "version", 1
                ),
                Map.of(
                        "entity_key", "aqs_md::aqs",
                        "name", "AQS",
                        "source", "AQS.md",
                        "version", 1
                )
        );

        List<Map<String, Object>> selected = KnowledgeGraphService.selectPreferredEntityMatches(rows, "AQS", 5);

        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst()).containsEntry("entity_key", "aqs_md::aqs");
        assertThat(selected.getFirst()).containsEntry("source", "AQS.md");
    }

    @Test
    void shouldKeepDistinctNonTempSourcesWhenTheyRepresentDifferentDocuments() {
        List<Map<String, Object>> rows = List.of(
                Map.of(
                        "entity_key", "e_docs_aqs_md::aqs",
                        "name", "AQS",
                        "source", "E:/docs/AQS.md",
                        "version", 2
                ),
                Map.of(
                        "entity_key", "aqs_md::aqs",
                        "name", "AQS",
                        "source", "AQS.md",
                        "version", 1
                )
        );

        List<Map<String, Object>> selected = KnowledgeGraphService.selectPreferredEntityMatches(rows, "AQS", 5);

        assertThat(selected).hasSize(2);
        assertThat(selected.get(0)).containsEntry("source", "AQS.md");
        assertThat(selected.get(1)).containsEntry("source", "E:/docs/AQS.md");
    }
}
