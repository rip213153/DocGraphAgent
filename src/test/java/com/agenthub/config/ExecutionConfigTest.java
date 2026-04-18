package com.agenthub.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionConfigTest {

    private final ExecutionConfig executionConfig = new ExecutionConfig();

    @Test
    void shouldCreateVirtualThreadExecutorByDefault() throws Exception {
        ExecutorService executor = executionConfig.createExecutor("virtual", 4);
        try {
            boolean virtual = executor.submit(() -> Thread.currentThread().isVirtual()).get();
            assertThat(virtual).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldCreatePlatformThreadExecutorWhenConfigured() throws Exception {
        ExecutorService executor = executionConfig.createExecutor("platform", 4);
        try {
            boolean virtual = executor.submit(() -> Thread.currentThread().isVirtual()).get();
            assertThat(virtual).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }
}
