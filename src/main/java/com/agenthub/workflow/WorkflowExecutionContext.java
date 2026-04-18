package com.agenthub.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WorkflowExecutionContext<S extends Enum<S>> {

    private final Map<S, Set<S>> allowedTransitions;
    private final Map<String, Integer> retryAttempts = new ConcurrentHashMap<>();
    private final List<String> degradeReasons = Collections.synchronizedList(new ArrayList<>());
    private final Instant startedAt = Instant.now();

    private volatile Instant endedAt;
    private volatile S currentState;
    private volatile boolean degraded;

    public WorkflowExecutionContext(S initialState, Map<S, Set<S>> allowedTransitions) {
        this.currentState = initialState;
        this.allowedTransitions = allowedTransitions;
    }

    public synchronized void transitionTo(S nextState) {
        Set<S> allowedStates = allowedTransitions.getOrDefault(currentState, Set.of());
        if (!allowedStates.contains(nextState)) {
            throw new IllegalStateException("Illegal state transition from " + currentState + " to " + nextState);
        }
        currentState = nextState;
    }

    public synchronized void fail(S failedState) {
        currentState = failedState;
        endedAt = Instant.now();
    }

    public synchronized void finish() {
        endedAt = Instant.now();
    }

    public void recordAttempt(String stage) {
        retryAttempts.merge(stage, 1, Integer::sum);
    }

    public void markDegraded(String reason) {
        degraded = true;
        if (reason != null && !reason.isBlank() && !degradeReasons.contains(reason)) {
            degradeReasons.add(reason);
        }
    }

    public S currentState() {
        return currentState;
    }

    public boolean degraded() {
        return degraded;
    }

    public List<String> degradeReasons() {
        return List.copyOf(degradeReasons);
    }

    public Map<String, Integer> retryAttempts() {
        return retryAttempts.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Math.max(0, entry.getValue() - 1)
                ));
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }
}
