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
    private final RunJournal journal;

    public RunRegistry() {
        this(Clock.systemUTC(), DEFAULT_TTL, RunJournal.NOOP);
    }

    public RunRegistry(Clock clock, Duration ttl) {
        this(clock, ttl, RunJournal.NOOP);
    }

    /**
     * Build a registry backed by a durable {@link RunJournal}. Every run's
     * metadata and captured events are persisted so a fresh registry over
     * the same journal can {@link #rehydrate()} them after a process
     * restart. Pass {@link RunJournal#NOOP} (or use the no-arg constructor)
     * for the in-memory-only behaviour.
     */
    public RunRegistry(Clock clock, Duration ttl, RunJournal journal) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        this.ttl = ttl;
        this.journal = journal != null ? journal : RunJournal.NOOP;
    }

    /** The journal backing this registry; {@link RunJournal#NOOP} if none. */
    public RunJournal journal() {
        return journal;
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
        var createdAt = Instant.now(clock);
        if (journal != RunJournal.NOOP) {
            // Persist metadata up front and bind the buffer to the journal so
            // every captured event is mirrored durably. recordRun is
            // best-effort — a journal failure degrades the run to in-memory
            // replay rather than failing the registration (Invariant #3).
            try {
                journal.recordRun(new RunJournal.RunRecord(id, agentId, userId, sessionId, createdAt));
                replayBuffer.attachJournal(journal, id);
            } catch (RuntimeException e) {
                logger.trace("RunJournal.recordRun failed for run {} ({}): {}",
                        id, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
        var handle = new AgentResumeHandle(
                id, agentId, userId, sessionId,
                executionHandle, replayBuffer, createdAt);
        runs.put(id, handle);
        // Remove on completion so finished runs do not linger — and delete
        // the durable journal entry too, closing the terminal path
        // (Invariant #2) so a completed run is not rehydrated after restart.
        executionHandle.whenDone().whenComplete((ok, err) -> {
            runs.remove(id);
            removeFromJournal(id);
        });
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
                    removeFromJournal(entry.getKey());
                    logger.trace("swept run {}", entry.getKey());
                }
            }
        }
        return removed;
    }

    /** Explicit removal — typically used when the owning session is closed. */
    public void unregister(String runId) {
        runs.remove(runId);
        removeFromJournal(runId);
    }

    /**
     * Drop all in-memory runs. Typically called on framework shutdown. The
     * durable journal is intentionally left intact — its whole purpose is to
     * survive shutdown so a fresh registry can {@link #rehydrate()} the runs
     * after restart.
     */
    public void clear() {
        runs.clear();
    }

    /**
     * Rebuild in-memory run handles from the durable journal — call once on
     * startup before serving reattach requests. Each persisted run becomes a
     * replay-only {@link AgentResumeHandle}: its original execution died with
     * the previous process, so the handle carries a fresh, never-completing
     * {@link ExecutionHandle} (a reconnecting client can drain the buffered
     * events but the generation cannot continue), and its replay buffer is
     * re-seeded from the journal with original sequence numbers preserved.
     * The original {@code userId} is restored so {@code RunReattachSupport}
     * still enforces caller-owns-run authorization (Invariant #6).
     *
     * <p>Returns the number of runs rehydrated. A no-op (returns 0) when the
     * registry has no durable journal.</p>
     */
    public int rehydrate() {
        if (journal == RunJournal.NOOP) {
            return 0;
        }
        var count = 0;
        for (var record : journal.loadAll()) {
            var buffer = new RunEventReplayBuffer();
            buffer.restore(journal.loadEvents(record.runId()));
            buffer.attachJournal(journal, record.runId());
            // A never-completing handle so lookup() keeps serving the
            // rehydrated run until it is replayed and then swept by TTL; the
            // dead generation has no native cancel to invoke.
            var handle = new AgentResumeHandle(
                    record.runId(), record.agentId(), record.userId(), record.sessionId(),
                    new ExecutionHandle.Settable(null), buffer, record.createdAt());
            if (runs.putIfAbsent(record.runId(), handle) == null) {
                count++;
            }
        }
        if (count > 0) {
            logger.info("Rehydrated {} run(s) from durable journal (durable={})",
                    count, journal.durable());
        }
        return count;
    }

    private void removeFromJournal(String runId) {
        if (journal == RunJournal.NOOP) {
            return;
        }
        try {
            journal.removeRun(runId);
        } catch (RuntimeException e) {
            logger.trace("RunJournal.removeRun failed for run {} ({}): {}",
                    runId, e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    public int size() {
        return runs.size();
    }
}
