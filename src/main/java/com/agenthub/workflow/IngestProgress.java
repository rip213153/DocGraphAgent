package com.agenthub.workflow;

import java.util.List;

public record IngestProgress(
        String currentStage,
        Integer chunksTotal,
        Integer chunksProcessed,
        boolean degraded,
        List<String> degradeReasons
) {
    public IngestProgress {
        degradeReasons = degradeReasons == null ? List.of() : List.copyOf(degradeReasons);
    }
}
