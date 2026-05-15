package com.agenthub.controller;

import com.agenthub.model.QAResult;
import com.agenthub.service.EventProcessingStore;
import com.agenthub.service.EventReplayService;
import com.agenthub.service.IngestTaskService;
import com.agenthub.service.IngestTaskSnapshot;
import com.agenthub.service.IngestTaskStatus;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import com.agenthub.workflow.QaWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock
    private IngestTaskService ingestTaskService;

    @Mock
    private QaWorkflowService qaWorkflowService;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private EventReplayService eventReplayService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(
                ingestTaskService,
                qaWorkflowService,
                vectorStoreService,
                knowledgeGraphService,
                eventReplayService
        ))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void shouldAcceptUploadAndReturnTaskId() throws Exception {
        IngestTaskSnapshot snapshot = new IngestTaskSnapshot(
                "ingest-123",
                "agent.md",
                "C:/temp/uploads/agent.md",
                IngestTaskStatus.QUEUED,
                "QUEUED",
                Instant.parse("2026-04-26T10:00:00Z"),
                null,
                null,
                null,
                0,
                0,
                false,
                List.of(),
                null
        );
        when(ingestTaskService.submit(eq("agent.md"), org.mockito.ArgumentMatchers.anyString())).thenReturn(snapshot);

        mockMvc.perform(multipart("/api/ingest/upload")
                        .file(new MockMultipartFile("file", "agent.md", "text/markdown", "# test".getBytes())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("ingest-123"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.currentStage").value("QUEUED"));

        verify(ingestTaskService).submit(eq("agent.md"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldReturnStructuredTaskStatus() throws Exception {
        IngestTaskSnapshot snapshot = new IngestTaskSnapshot(
                "ingest-456",
                "agent.md",
                "C:/temp/uploads/agent.md",
                IngestTaskStatus.PROCESSING,
                "EXTRACTING",
                Instant.parse("2026-04-26T10:00:00Z"),
                Instant.parse("2026-04-26T10:00:01Z"),
                null,
                null,
                6,
                2,
                true,
                List.of("GRAPH_UNAVAILABLE"),
                null
        );
        when(ingestTaskService.getTask("ingest-456")).thenReturn(snapshot);

        mockMvc.perform(get("/api/ingest/tasks/ingest-456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("ingest-456"))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.currentStage").value("EXTRACTING"))
                .andExpect(jsonPath("$.chunksTotal").value(6))
                .andExpect(jsonPath("$.chunksProcessed").value(2))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degradeReasons[0]").value("GRAPH_UNAVAILABLE"));
    }

    @Test
    void shouldReturnQaAnswer() throws Exception {
        QAResult qaResult = QAResult.builder()
                .question("What is AQS?")
                .answer("AQS is a synchronization framework.")
                .confidence(0.91)
                .intent("descriptive")
                .contexts(List.of())
                .reasoningSteps(List.of("COMPLETED"))
                .build();
        when(qaWorkflowService.ask(any())).thenReturn(qaResult);

        mockMvc.perform(post("/api/qa/ask")
                        .contentType(APPLICATION_JSON)
                        .content("{\"sessionId\":\"demo-session\",\"question\":\"What is AQS?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("What is AQS?"))
                .andExpect(jsonPath("$.answer").value("AQS is a synchronization framework."))
                .andExpect(jsonPath("$.intent").value("descriptive"));

        verify(qaWorkflowService).ask(any());
    }

    @Test
    void shouldReturnAdminStats() throws Exception {
        when(vectorStoreService.getStats()).thenReturn(Map.of("status", "ready", "backend", "milvus"));
        when(knowledgeGraphService.getStats()).thenReturn(Map.of("totalEntities", 12, "totalRelations", 20));

        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vectorStore.status").value("ready"))
                .andExpect(jsonPath("$.vectorStore.backend").value("milvus"))
                .andExpect(jsonPath("$.knowledgeGraph.totalEntities").value(12))
                .andExpect(jsonPath("$.knowledgeGraph.totalRelations").value(20));
    }

    @Test
    void shouldReturnEventDetails() throws Exception {
        EventProcessingStore.EventState eventState = new EventProcessingStore.EventState(
                "evt-1",
                "E:/docs/agent.md",
                "modified",
                3L,
                Instant.parse("2026-04-26T10:00:00Z"),
                EventProcessingStore.ProcessingStatus.SUCCEEDED,
                Instant.parse("2026-04-26T10:00:05Z"),
                "",
                "{\"event_id\":\"evt-1\"}"
        );
        when(eventReplayService.getEventState("evt-1")).thenReturn(eventState);

        mockMvc.perform(get("/api/admin/events/evt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.filePath").value("E:/docs/agent.md"))
                .andExpect(jsonPath("$.changeType").value("modified"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void shouldReplayEvent() throws Exception {
        when(eventReplayService.replay("evt-2")).thenReturn(Map.of(
                "eventId", "evt-2",
                "status", "replayed",
                "changeType", "created",
                "version", 1
        ));

        mockMvc.perform(post("/api/admin/events/evt-2/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-2"))
                .andExpect(jsonPath("$.status").value("replayed"))
                .andExpect(jsonPath("$.changeType").value("created"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void shouldReturnHealth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("AgentKnowledgeHub-Java"));
    }
}
