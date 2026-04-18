package com.agenthub.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkflowStepRunner {

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    private final int maxAttempts;
    private final long delayMs;

    public WorkflowStepRunner(
            @Value("${app.workflow.retry.max-attempts:3}") int maxAttempts,
            @Value("${app.workflow.retry.delay-ms:200}") long delayMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.delayMs = Math.max(0L, delayMs);
    }

    public void run(String stage, WorkflowExecutionContext<?> context, CheckedRunnable runnable) throws Exception {
        supply(stage, context, () -> {
            runnable.run();
            return null;
        });
    }

    public <T> T supply(String stage, WorkflowExecutionContext<?> context, CheckedSupplier<T> supplier) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            context.recordAttempt(stage);
            try {
                return supplier.get();
            } catch (Exception e) {
                last = e;
                if (attempt == maxAttempts) {
                    throw e;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Retry interrupted for stage " + stage, interruptedException);
                }
            }
        }
        throw last == null ? new IllegalStateException("Workflow step failed: " + stage) : last;
    }
}
