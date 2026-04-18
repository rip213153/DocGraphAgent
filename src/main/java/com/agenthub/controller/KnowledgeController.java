package com.agenthub.controller;

import com.agenthub.model.QAResult;
import com.agenthub.model.QaAskRequest;
import com.agenthub.service.EventReplayService;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import com.agenthub.workflow.IngestWorkflowService;
import com.agenthub.workflow.QaWorkflowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * REST API 控制器 (Java版)
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final IngestWorkflowService ingestWorkflowService;
    private final QaWorkflowService qaWorkflowService;
    private final VectorStoreService vectorStore;
    private final KnowledgeGraphService knowledgeGraph;
    private final EventReplayService eventReplayService;

    public KnowledgeController(IngestWorkflowService ingestWorkflowService,
                                QaWorkflowService qaWorkflowService,
                                VectorStoreService vectorStore, KnowledgeGraphService knowledgeGraph,
                                EventReplayService eventReplayService) {
        this.ingestWorkflowService = ingestWorkflowService;
        this.qaWorkflowService = qaWorkflowService;
        this.vectorStore = vectorStore;
        this.knowledgeGraph = knowledgeGraph;
        this.eventReplayService = eventReplayService;
    }

    @PostMapping("/ingest/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        Path tempDir = Files.createTempDirectory("uploads");
        File saved = tempDir.resolve(file.getOriginalFilename()).toFile();
        file.transferTo(saved);

        return ResponseEntity.ok(
                ingestWorkflowService.ingest(file.getOriginalFilename(), saved.getAbsolutePath())
        );
    }

    @PostMapping("/qa/ask")
    public ResponseEntity<QAResult> ask(@RequestBody QaAskRequest body) {
        QAResult result = qaWorkflowService.ask(body);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/admin/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "vectorStore", vectorStore.getStats(),
                "knowledgeGraph", knowledgeGraph.getStats()
        ));
    }

    @GetMapping("/admin/events/{eventId}")
    public ResponseEntity<Map<String, Object>> event(@PathVariable String eventId) {
        var state = eventReplayService.getEventState(eventId);
        return ResponseEntity.ok(Map.of(
                "eventId", state.eventId(),
                "filePath", state.filePath(),
                "changeType", state.changeType(),
                "version", state.version(),
                "timestamp", state.timestamp(),
                "status", state.status(),
                "processedAt", state.processedAt(),
                "failureReason", state.failureReason()
        ));
    }

    @PostMapping("/admin/events/{eventId}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable String eventId) {
        return ResponseEntity.ok(eventReplayService.replay(eventId));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "AgentKnowledgeHub-Java"));
    }
}
