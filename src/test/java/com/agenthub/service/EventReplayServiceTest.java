package com.agenthub.service;

import com.agenthub.agent.KnowledgeUpdateAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventReplayServiceTest {

    @Mock
    private EventProcessingStore eventProcessingStore;

    @Mock
    private KnowledgeUpdateAgent knowledgeUpdateAgent;

    private EventReplayService eventReplayService;

    @BeforeEach
    void setUp() {
        eventReplayService = new EventReplayService(eventProcessingStore, knowledgeUpdateAgent);
    }

    @Test
    void shouldReturnEventStateWhenEventExists() {
        EventProcessingStore.EventState state = eventState("evt-1", "{\"event_id\":\"evt-1\"}");
        when(eventProcessingStore.getEvent("evt-1")).thenReturn(Optional.of(state));

        EventProcessingStore.EventState result = eventReplayService.getEventState("evt-1");

        assertThat(result.eventId()).isEqualTo("evt-1");
        assertThat(result.status()).isEqualTo(EventProcessingStore.ProcessingStatus.SUCCEEDED);
    }

    @Test
    void shouldThrowWhenEventDoesNotExist() {
        when(eventProcessingStore.getEvent("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventReplayService.getEventState("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event not found");
    }

    @Test
    void shouldReplayEventPayloadWhenPayloadExists() {
        EventProcessingStore.EventState state = eventState("evt-2", "{\"event_id\":\"evt-2\",\"file_path\":\"E:/docs/agent.md\"}");
        when(eventProcessingStore.getEvent("evt-2")).thenReturn(Optional.of(state));

        Map<String, Object> result = eventReplayService.replay("evt-2");

        assertThat(result).containsEntry("eventId", "evt-2");
        assertThat(result).containsEntry("status", "replayed");
        assertThat(result).containsEntry("changeType", "modified");
        assertThat(result).containsEntry("version", 3L);
        verify(knowledgeUpdateAgent).replayEventPayload(state.payload());
    }

    @Test
    void shouldRejectReplayWhenPayloadMissing() {
        EventProcessingStore.EventState state = eventState("evt-3", "");
        when(eventProcessingStore.getEvent("evt-3")).thenReturn(Optional.of(state));

        assertThatThrownBy(() -> eventReplayService.replay("evt-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload is unavailable");
    }

    private EventProcessingStore.EventState eventState(String eventId, String payload) {
        return new EventProcessingStore.EventState(
                eventId,
                "E:/docs/agent.md",
                "modified",
                3L,
                Instant.parse("2026-04-26T10:00:00Z"),
                EventProcessingStore.ProcessingStatus.SUCCEEDED,
                Instant.parse("2026-04-26T10:00:05Z"),
                null,
                payload
        );
    }
}
