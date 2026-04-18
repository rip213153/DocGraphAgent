package com.agenthub.service;

import com.agenthub.agent.KnowledgeUpdateAgent;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class EventReplayService {

    private final EventProcessingStore eventProcessingStore;
    private final KnowledgeUpdateAgent knowledgeUpdateAgent;

    public EventReplayService(EventProcessingStore eventProcessingStore, KnowledgeUpdateAgent knowledgeUpdateAgent) {
        this.eventProcessingStore = eventProcessingStore;
        this.knowledgeUpdateAgent = knowledgeUpdateAgent;
    }

    public EventProcessingStore.EventState getEventState(String eventId) {
        return eventProcessingStore.getEvent(eventId)
                .orElseThrow(() -> new IllegalArgumentException("event not found: " + eventId));
    }

    public Map<String, Object> replay(String eventId) {
        EventProcessingStore.EventState state = getEventState(eventId);
        if (state.payload() == null || state.payload().isBlank()) {
            throw new IllegalStateException("event payload is unavailable for replay");
        }
        knowledgeUpdateAgent.replayEventPayload(state.payload());
        return Map.of(
                "eventId", eventId,
                "status", "replayed",
                "changeType", state.changeType(),
                "version", state.version()
        );
    }
}
