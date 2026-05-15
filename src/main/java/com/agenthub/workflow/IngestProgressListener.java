package com.agenthub.workflow;

public interface IngestProgressListener {

    IngestProgressListener NOOP = progress -> {
    };

    void onProgress(IngestProgress progress);
}
