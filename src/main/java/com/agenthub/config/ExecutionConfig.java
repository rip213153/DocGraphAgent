package com.agenthub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ExecutionConfig {

    @Bean(name = "workflowExecutor", destroyMethod = "shutdown")
    ExecutorService workflowExecutor(
            @Value("${app.execution.mode:virtual}") String mode,
            @Value("${app.execution.platform-pool-size:4}") int platformPoolSize) {
        return createExecutor(mode, platformPoolSize);
    }

    public ExecutorService createExecutor(String mode, int platformPoolSize) {
        if ("platform".equalsIgnoreCase(mode)) {
            return Executors.newFixedThreadPool(Math.max(1, platformPoolSize));
        }
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
