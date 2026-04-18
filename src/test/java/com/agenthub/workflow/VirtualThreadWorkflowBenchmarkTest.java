package com.agenthub.workflow;

import com.agenthub.config.ExecutionConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualThreadWorkflowBenchmarkTest {

    private final ExecutionConfig executionConfig = new ExecutionConfig();

    @Test
    void shouldCompleteSleepHeavyWorkflowFasterWithVirtualThreads() {
        int tasks = 24;
        long sleepMs = 80L;

        ExecutorService platformExecutor = executionConfig.createExecutor("platform", 4);
        ExecutorService virtualExecutor = executionConfig.createExecutor("virtual", 4);
        try {
            long platformDuration = runSleepBenchmark(platformExecutor, tasks, sleepMs);
            long virtualDuration = runSleepBenchmark(virtualExecutor, tasks, sleepMs);

            System.out.printf("Virtual thread benchmark: platform=%dms, virtual=%dms%n",
                    platformDuration, virtualDuration);

            assertThat(virtualDuration).isLessThan(platformDuration);
        } finally {
            platformExecutor.shutdownNow();
            virtualExecutor.shutdownNow();
        }
    }

    private long runSleepBenchmark(ExecutorService executor, int tasks, long sleepMs) {
        Instant startedAt = Instant.now();
        List<CompletableFuture<Void>> futures = new ArrayList<>(tasks);
        for (int i = 0; i < tasks; i++) {
            futures.add(CompletableFuture.runAsync(() -> sleep(sleepMs), executor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    private void sleep(long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark interrupted", e);
        }
    }
}
