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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.ExecutionHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of in-flight {@link AgentResumeHandle}s keyed by
 * {@code runId}. {@code DurableSessionInterceptor} consults it on reconnect
 * to reattach to a live run.
 *
 * <h2>Lifecycle</h2>
 *
 * A run registers on start via {@link #register}. It removes itself when
 * the execution handle's future completes. Runs that never complete are
 * garbage-collected by {@link #sweepExpired} — callers typically invoke
 * this from a scheduled executor to keep abandoned runs from leaking
 * memory.
 *
 * <h2>Backpressure</h2>
 *
 * No hard cap on registered runs today; applications that need one wrap
 * the registry and reject registrations above a configured limit. The
 * {@link #sweepExpired} TTL is the primary leak guard.
 */
public final class RunRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RunRegistry.class);

    /** Default TTL — runs idle longer than this are candidates for sweep. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final Map<String, AgentResumeHandle> runs = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public RunRegistry() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    public RunRegistry(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        this.ttl = ttl;
    }

    /**
     * Register a new run. Generates a fresh {@code runId} unless one is
     * supplied explicitly (for callers threading their own id through
     * upstream protocols).
     */
    public AgentResumeHandle register(
            String agentId,
            String userId,
            String sessionId,
            ExecutionHandle executionHandle) {
        return register(agentId, userId, sessionId, executionHandle,
                new RunEventReplayBuffer(), null);
    }

    /** Full-parameter register — explicit replay buffer and optional runId. */
    public AgentResumeHandle register(
            String agentId,
            String userId,
            String sessionId,
            ExecutionHandle executionHandle,
            RunEventReplayBuffer replayBuffer,
            String runId) {
        var id = runId != null && !runId.isBlank() ? runId : UUID.randomUUID().toString();
        var handle = new AgentResumeHandle(
                id, agentId, userId, sessionId,
                executionHandle, replayBuffer, Instant.now(clock));
        runs.put(id, handle);
        // Remove on completion so finished runs do not linger.
        executionHandle.whenDone().whenComplete((ok, err) -> runs.remove(id));
        return handle;
    }

    /** Look up an active run by id. Returns empty if unknown or completed. */
    public Optional<AgentResumeHandle> lookup(String runId) {
        var handle = runs.get(runId);
        if (handle == null) {
            return Optional.empty();
        }
        if (handle.isDone()) {
            runs.remove(runId);
            return Optional.empty();
        }
        return Optional.of(handle);
    }

    /**
     * All currently tracked runs; primarily for admin inspection. The
     * returned list is a snapshot — subsequent registrations are not
     * reflected.
     */
    public List<AgentResumeHandle> all() {
        return List.copyOf(runs.values());
    }

    /**
     * Remove runs older than the TTL. Intended for scheduled sweepers.
     * Returns the number removed.
     */
    public int sweepExpired() {
        var cutoff = Instant.now(clock).minus(ttl);
        var removed = 0;
        for (var entry : Map.copyOf(runs).entrySet()) {
            if (entry.getValue().createdAt().isBefore(cutoff)
                    || entry.getValue().isDone()) {
                if (runs.remove(entry.getKey()) != null) {
                    removed++;
                    logger.trace("swept run {}", entry.getKey());
                }
            }
        }
        return removed;
    }

    /** Explicit removal — typically used when the owning session is closed. */
    public void unregister(String runId) {
        runs.remove(runId);
    }

    /** Drop all runs. Typically called on framework shutdown. */
    public void clear() {
        runs.clear();
    }

    public int size() {
        return runs.size();
    }
}
