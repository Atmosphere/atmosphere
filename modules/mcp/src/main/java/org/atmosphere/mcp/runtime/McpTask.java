/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.mcp.runtime;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * State for an MCP task (spec 2025-11-25, experimental). A task represents
 * a long-running, durable request — typically a {@code tools/call} that the
 * client elected to run in the background by passing {@code params.task}.
 *
 * <p>Tasks are state machines:</p>
 * <pre>
 *   working ──┬──→ input_required ──┬──→ completed
 *             │                    │
 *             │                    ├──→ failed
 *             │                    │
 *             │                    └──→ cancelled
 *             ├──→ completed
 *             ├──→ failed
 *             └──→ cancelled
 * </pre>
 *
 * <p>Terminal states ({@code completed} / {@code failed} / {@code cancelled})
 * are absorbing — transitions out are forbidden by the spec.</p>
 *
 * @since 4.0.43
 */
public final class McpTask {

    /** MCP task lifecycle status. */
    public enum Status {
        WORKING("working"),
        INPUT_REQUIRED("input_required"),
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELLED("cancelled");

        private final String wireValue;

        Status(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    private final String taskId;
    private final Instant createdAt;
    private final long ttlMs;
    private final Long pollIntervalMs;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.WORKING);
    private volatile Instant lastUpdatedAt;
    private volatile String statusMessage;

    /** Resolved when the task reaches a terminal status; carries the underlying request's result. */
    private final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();

    public McpTask(long ttlMs, Long pollIntervalMs) {
        this.taskId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.lastUpdatedAt = this.createdAt;
        this.ttlMs = ttlMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    public String taskId() {
        return taskId;
    }

    public Status status() {
        return status.get();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUpdatedAt() {
        return lastUpdatedAt;
    }

    public long ttlMs() {
        return ttlMs;
    }

    public Long pollIntervalMs() {
        return pollIntervalMs;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public CompletableFuture<Map<String, Object>> result() {
        return result;
    }

    /** True when the task's TTL has expired since creation. */
    public boolean isExpired() {
        if (ttlMs <= 0) {
            return false; // 0/negative TTL == no expiry
        }
        return Instant.now().isAfter(createdAt.plusMillis(ttlMs));
    }

    /**
     * Attempt to transition the task to a new status. Spec-aligned rules:
     * terminal states are absorbing; from working/input_required only the
     * documented destinations are reachable. Returns {@code true} on
     * success, {@code false} when the transition was rejected (current
     * status is terminal or the move is not allowed).
     */
    public boolean transition(Status to, String message) {
        while (true) {
            var current = status.get();
            if (current.isTerminal()) {
                return false;
            }
            if (!isAllowed(current, to)) {
                return false;
            }
            if (status.compareAndSet(current, to)) {
                this.statusMessage = message;
                this.lastUpdatedAt = Instant.now();
                return true;
            }
        }
    }

    /**
     * Convenience: complete the task with the underlying request's result.
     * Transitions to COMPLETED and fulfills {@link #result()}.
     */
    public boolean complete(Map<String, Object> finalResult, String message) {
        if (transition(Status.COMPLETED, message)) {
            result.complete(finalResult);
            return true;
        }
        return false;
    }

    /**
     * Convenience: fail the task with a result envelope. Transitions to
     * FAILED and fulfills {@link #result()} with the provided result so
     * {@code tasks/result} can return it verbatim.
     */
    public boolean fail(Map<String, Object> finalResult, String message) {
        if (transition(Status.FAILED, message)) {
            result.complete(finalResult);
            return true;
        }
        return false;
    }

    public boolean cancel(String message) {
        if (transition(Status.CANCELLED, message)) {
            // Per spec: tasks/result for cancelled tasks should still resolve
            // (returning whatever the underlying op had produced if anything,
            // or an error envelope). We complete with a synthetic envelope.
            result.complete(Map.of(
                    "content", java.util.List.of(Map.of(
                            "type", "text",
                            "text", message != null ? message : "Task cancelled")),
                    "isError", true));
            return true;
        }
        return false;
    }

    /**
     * Serialize the task envelope per MCP {@code Task} schema. Used by
     * {@code tasks/get}, {@code tasks/list}, the initial {@code CreateTaskResult},
     * and {@code notifications/tasks/status}.
     */
    public Map<String, Object> toWire() {
        var m = new LinkedHashMap<String, Object>();
        m.put("taskId", taskId);
        m.put("status", status.get().wireValue());
        if (statusMessage != null && !statusMessage.isEmpty()) {
            m.put("statusMessage", statusMessage);
        }
        m.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(createdAt));
        m.put("lastUpdatedAt", DateTimeFormatter.ISO_INSTANT.format(lastUpdatedAt));
        m.put("ttl", ttlMs);
        if (pollIntervalMs != null) {
            m.put("pollInterval", pollIntervalMs);
        }
        return m;
    }

    private static boolean isAllowed(Status from, Status to) {
        if (from.isTerminal()) {
            return false;
        }
        // working ↔ input_required, both → terminal — covered by these two cases:
        if (from == Status.WORKING) {
            return to == Status.INPUT_REQUIRED || to.isTerminal();
        }
        if (from == Status.INPUT_REQUIRED) {
            return to == Status.WORKING || to.isTerminal();
        }
        return false;
    }
}
