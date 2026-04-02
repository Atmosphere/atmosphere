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
package org.atmosphere.mcp;

import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bidirectional tool invocation between server and client. Allows the server to call
 * tools on connected clients (e.g. browser-side JavaScript functions) and receive
 * results asynchronously via {@link CompletableFuture}.
 */
public class BiDirectionalToolBridge {

    private static final Logger logger = LoggerFactory.getLogger(BiDirectionalToolBridge.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingCalls =
            new ConcurrentHashMap<>();

    private final Duration timeout;
    private final ScheduledExecutorService cleanupScheduler;

    /**
     * Creates a bridge with the default timeout (30 seconds).
     */
    public BiDirectionalToolBridge() {
        this(DEFAULT_TIMEOUT);
    }

    /**
     * Creates a bridge with a custom timeout.
     *
     * @param timeout maximum time to wait for a client response
     */
    public BiDirectionalToolBridge(Duration timeout) {
        this.timeout = timeout;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofPlatform().daemon().name("tool-bridge-cleanup").unstarted(r);
            return t;
        });
        long intervalMs = Math.max(timeout.toMillis(), 30_000L);
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupOrphanedCalls,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Call a tool on the connected client and return a future for the result.
     *
     * @param resource the client's Atmosphere resource (transport connection)
     * @param toolName the name of the tool to invoke on the client
     * @param args     the tool arguments
     * @return a future that completes with the tool result string
     */
    public CompletableFuture<String> callClientTool(
            AtmosphereResource resource,
            String toolName,
            Map<String, Object> args) {

        var callId = UUID.randomUUID().toString();
        var request = new ToolCallRequest(callId, toolName, args);
        var future = new CompletableFuture<String>();

        pendingCalls.put(callId, future);

        // Auto-cleanup on completion (success, error, or timeout)
        future.whenComplete((result, error) -> pendingCalls.remove(callId));

        var json = request.toJson();
        try {
            resource.getResponse().write(json);
        } catch (Exception e) {
            pendingCalls.remove(callId);
            future.completeExceptionally(e);
            return future;
        }
        logger.debug("Sent tool call {} to client {}: {}", callId, resource.uuid(), toolName);

        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Complete a pending tool call with a response from the client.
     *
     * <p>Called by {@link ToolResponseHandler} when a client sends back a result.</p>
     *
     * @param json the response JSON from the client
     */
    public void completePendingCall(String json) {
        ToolCallResponse response;
        try {
            response = ToolCallResponse.fromJson(json);
        } catch (IllegalArgumentException e) {
            logger.warn("Malformed tool response: {}", e.getMessage());
            return;
        }

        var id = response.id();
        if (id == null) {
            logger.warn("Tool response missing 'id' field, discarding");
            return;
        }

        var future = pendingCalls.get(id);
        if (future == null) {
            logger.debug("No pending call for id {}, discarding response", id);
            return;
        }

        if (response.isError()) {
            future.completeExceptionally(new ToolCallException(id, response.error()));
        } else {
            future.complete(response.result());
        }
    }

    /**
     * @return the number of currently pending tool calls
     */
    public int pendingCount() {
        return pendingCalls.size();
    }

    /**
     * @return an unmodifiable view of pending call IDs to futures (for testing/monitoring)
     */
    public Map<String, CompletableFuture<String>> pendingCalls() {
        return java.util.Collections.unmodifiableMap(pendingCalls);
    }

    /**
     * Shuts down the background cleanup scheduler.
     */
    public void close() {
        cleanupScheduler.shutdownNow();
    }

    private void cleanupOrphanedCalls() {
        pendingCalls.forEach((id, future) -> {
            if (future.isDone()) {
                pendingCalls.remove(id);
            }
        });
    }

    /**
     * Exception thrown when a client-side tool call returns an error.
     */
    public static class ToolCallException extends RuntimeException {

        private final String callId;

        public ToolCallException(String callId, String message) {
            super(message);
            this.callId = callId;
        }

        public String getCallId() {
            return callId;
        }
    }
}
