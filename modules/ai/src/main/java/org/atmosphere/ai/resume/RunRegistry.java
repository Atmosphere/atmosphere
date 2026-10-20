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
import java.util.LinkedHashMap;
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
 * A run registers on start via {@link #register}. When the execution
 * handle's future completes it leaves the live set ({@link #lookup} no longer
 * finds it) but stays replayable through {@link #lookupReplayable} for the
 * registry TTL, so a client that reconnects after the run finished still
 * receives its tail and terminal event. The execution of an
 * {@code @AiEndpoint} run completes when its session sent its terminal frame:
 * at the {@code @Prompt} method's return for a session it ended, later for a
 * session the method returned leaving open ({@link #keepOpenAfterPrompt}),
 * which is ended by its own terminal frame, its prompter gone for good, an
 * operator's cancel, the destroy of the endpoint that started it, or the idle sweep of streaming sessions (idle, or open
 * past {@code atmosphere.ai.session-ttl-ms} after the return). Retired runs are bounded by
 * {@link #MAX_RETAINED_COMPLETED} (oldest evicted first) and expire on access,
 * so they need no sweeper. Runs that never complete are removed by
 * {@link #sweepExpired}, which nothing in the framework schedules — an
 * application that registers runs which may never complete calls it.
 *
 * <p>A run's end is its replay buffer's end too: when the execution handle
 * completes, and when a run that has not completed is swept, unregistered or
 * rehydrated without its generation, the registry
 * {@linkplain RunEventReplayBuffer#terminate terminates} the buffer unless the
 * run already captured its own {@code complete} / {@code error} /
 * {@code cancelled} frame. A client
 * that reattached to the run therefore always receives a terminal frame, and
 * its live subscription is released (Correctness Invariant #2).</p>
 *
 * <h2>Backpressure</h2>
 *
 * No hard cap on registered runs today; applications that need one wrap
 * the registry and reject registrations above a configured limit. The
 * {@link #sweepExpired} TTL is the primary leak guard. The runs whose
 * {@code @Prompt} method returned leaving their session open are capped,
 * per registry, at {@value #DEFAULT_MAX_OPEN_AFTER_PROMPT} by default
 * ({@value #MAX_OPEN_AFTER_PROMPT_PROPERTY}): past that, such a run ends at
 * its method's return, as {@code complete} ({@link #keepOpenAfterPrompt}).
 */
public final class RunRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RunRegistry.class);

    /** Default TTL — runs idle longer than this are candidates for sweep. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /**
     * One-shot latch for the durable-degradation WARN: the first journal
     * failure is an operator-visible incident, the rest are TRACE noise.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean DEGRADED_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Test isolation for the one-shot degradation WARN. */
    static void resetDegradationWarningForTests() {
        DEGRADED_WARNED.set(false);
    }

    /** Upper bound on finished runs kept replayable (Correctness Invariant #3). */
    public static final int MAX_RETAINED_COMPLETED = 256;

    /**
     * System property: how many runs whose {@code @Prompt} method returned
     * leaving their session open one registry keeps live at once; a positive
     * integer, {@value #DEFAULT_MAX_OPEN_AFTER_PROMPT} by default. Read when
     * the registry is built: the registry is process-wide and belongs to no
     * one application, so no application's init-params apply to it.
     */
    public static final String MAX_OPEN_AFTER_PROMPT_PROPERTY = "atmosphere.ai.max-runs-open-after-prompt";

    /** Default of {@link #MAX_OPEN_AFTER_PROMPT_PROPERTY} (Correctness Invariant #3). */
    public static final int DEFAULT_MAX_OPEN_AFTER_PROMPT = 1024;

    private final Map<String, AgentResumeHandle> runs = new ConcurrentHashMap<>();
    /** Finished runs still replayable, oldest first. Guarded by itself. */
    private final LinkedHashMap<String, Retired> completed = new LinkedHashMap<>();

    private record Retired(AgentResumeHandle handle, Instant completedAt) {
    }
    private final Clock clock;
    private final Duration ttl;
    private final RunJournal journal;
    private final int maxOpenAfterPrompt;
    /** Ids of the live runs kept open after their {@code @Prompt} returned. Guarded by itself for admission. */
    private final java.util.Set<String> openAfterPrompt = ConcurrentHashMap.newKeySet();
    /** The cap's WARN is logged once per registry, then at DEBUG. */
    private final java.util.concurrent.atomic.AtomicBoolean openAfterPromptCapWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

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
        this.maxOpenAfterPrompt = readMaxOpenAfterPrompt();
    }

    private static int readMaxOpenAfterPrompt() {
        var raw = System.getProperty(MAX_OPEN_AFTER_PROMPT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_OPEN_AFTER_PROMPT;
        }
        try {
            var parsed = Integer.parseInt(raw.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            logger.trace("Malformed {}={}", MAX_OPEN_AFTER_PROMPT_PROPERTY, raw, e);
        }
        logger.warn("Ignoring {}={}: not a positive integer, using the default {}",
                MAX_OPEN_AFTER_PROMPT_PROPERTY, raw, DEFAULT_MAX_OPEN_AFTER_PROMPT);
        return DEFAULT_MAX_OPEN_AFTER_PROMPT;
    }

    /**
     * How many runs whose {@code @Prompt} method returned leaving their
     * session open this registry keeps live at once
     * ({@value #MAX_OPEN_AFTER_PROMPT_PROPERTY}).
     */
    public int maxOpenAfterPrompt() {
        return maxOpenAfterPrompt;
    }

    /**
     * Ask to keep {@code run} live after its {@code @Prompt} method returned
     * leaving its session open, until its execution completes. Granted while
     * fewer than {@link #maxOpenAfterPrompt()} runs of this registry are kept
     * so; a run that ends frees its place. Refused past that: the caller ends
     * the run at the return, and the refusal is logged at WARN once per
     * registry, then at DEBUG (Correctness Invariant #3).
     *
     * @param run a live run of this registry
     * @return {@code true} when the run may stay live past its method's return
     */
    public boolean keepOpenAfterPrompt(AgentResumeHandle run) {
        Objects.requireNonNull(run, "run");
        synchronized (openAfterPrompt) {
            if (openAfterPrompt.size() >= maxOpenAfterPrompt) {
                if (openAfterPromptCapWarned.compareAndSet(false, true)) {
                    logger.warn("{} runs are live after their @Prompt method returned leaving the session open, "
                            + "the most one registry keeps ({}): run {} and the next past the cap end at "
                            + "their method's return, as complete; further refusals are logged at DEBUG",
                            openAfterPrompt.size(), MAX_OPEN_AFTER_PROMPT_PROPERTY, run.runId());
                } else {
                    logger.debug("Run {} ends at its @Prompt method's return: {} runs are kept open already ({})",
                            run.runId(), openAfterPrompt.size(), MAX_OPEN_AFTER_PROMPT_PROPERTY);
                }
                return false;
            }
            openAfterPrompt.add(run.runId());
        }
        if (run.isDone()) {
            // It ended meanwhile: its end may have run before it took the place.
            openAfterPrompt.remove(run.runId());
        }
        return true;
    }

    /**
     * How long a finished run stays replayable and an unfinished one stays
     * registered before {@link #sweepExpired} removes it; also the upper bound
     * of the reattach grace a disconnected run is granted.
     */
    public Duration ttl() {
        return ttl;
    }

    /** The journal backing this registry; {@link RunJournal#NOOP} if none. */
    public RunJournal journal() {
        return journal;
    }

    /**
     * Register a new run. Generates a fresh {@code runId} unless one is
     * supplied explicitly (for callers threading their own id through
     * upstream protocols). Its replay buffer has the default bounds
     * ({@link RunEventReplayBuffer#RunEventReplayBuffer()}).
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
        // Its warnings name the run, a journal attached or not.
        Objects.requireNonNull(replayBuffer, "replayBuffer").nameRun(id);
        if (journal != RunJournal.NOOP) {
            // Persist metadata up front and bind the buffer to the journal so
            // every captured event is mirrored durably. recordRun is
            // best-effort — a journal failure degrades the run to in-memory
            // replay rather than failing the registration (Invariant #3).
            try {
                journal.recordRun(new RunJournal.RunRecord(id, agentId, userId, sessionId, createdAt));
                replayBuffer.attachJournal(journal, id);
            } catch (RuntimeException e) {
                // The degradation itself is the documented design; the
                // SIGNAL must not be. An operator who provisioned durable
                // storage learns on the first failure that runs stopped
                // surviving restart — subsequent failures stay at TRACE.
                if (DEGRADED_WARNED.compareAndSet(false, true)) {
                    logger.warn("RunJournal.recordRun failed — durable run state has "
                            + "degraded to IN-MEMORY: runs will not survive a restart "
                            + "until the journal recovers. First failing run {} ({}: {})",
                            id, e.getClass().getSimpleName(), e.getMessage(), e);
                } else {
                    logger.trace("RunJournal.recordRun failed for run {} ({}): {}",
                            id, e.getClass().getSimpleName(), e.getMessage(), e);
                }
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
            // A run that ended on its own within a reattach grace must not be
            // cancelled by that grace's timer later.
            RunDisconnectGrace.runEnded(id);
            openAfterPrompt.remove(id);
            endRun(handle, err);
            retire(handle);
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
            retire(handle);
            return Optional.empty();
        }
        return Optional.of(handle);
    }

    /**
     * Look up a run whose events a reconnecting client may replay: a live run,
     * or one that finished within the TTL. Empty when unknown or expired.
     */
    public Optional<AgentResumeHandle> lookupReplayable(String runId) {
        var live = lookup(runId);
        if (live.isPresent()) {
            return live;
        }
        synchronized (completed) {
            var retired = completed.get(runId);
            if (retired == null) {
                return Optional.empty();
            }
            if (retired.completedAt().isBefore(Instant.now(clock).minus(ttl))) {
                completed.remove(runId);
                return Optional.empty();
            }
            return Optional.of(retired.handle());
        }
    }

    /**
     * Every run whose events a reconnecting client may replay
     * ({@link #lookupReplayable}): the live runs and those that finished within
     * the TTL, at most {@code MAX_RETAINED_COMPLETED} of them. A snapshot.
     */
    public List<AgentResumeHandle> replayable() {
        var all = new LinkedHashMap<String, AgentResumeHandle>();
        var cutoff = Instant.now(clock).minus(ttl);
        synchronized (completed) {
            for (var retired : completed.values()) {
                if (!retired.completedAt().isBefore(cutoff)) {
                    all.put(retired.handle().runId(), retired.handle());
                }
            }
        }
        for (var run : runs.values()) {
            all.putIfAbsent(run.runId(), run);
        }
        return List.copyOf(all.values());
    }

    /** Number of finished runs currently kept replayable. */
    public int retainedCompletedCount() {
        synchronized (completed) {
            return completed.size();
        }
    }

    /**
     * Close the replay buffer of a run whose execution ended, so a reattached
     * client waiting on its live subscription gets a terminal frame even when
     * the run never emitted one: an execution that ended normally ends in
     * {@code complete}, one that failed in {@code error}, one that was
     * cancelled in {@code cancelled}. The {@code @AiEndpoint} handler ends a
     * run only once its session sent its terminal frame, which the replay
     * already holds, so this records nothing more for it: the replay ends with
     * the frame the session ended with.
     */
    private static void endRun(AgentResumeHandle handle, Throwable failure) {
        if (failure == null) {
            handle.replayBuffer().terminate("complete", "");
            return;
        }
        var cancelled = org.atmosphere.ai.RunCancelledException.reasonOf(failure);
        if (cancelled.isPresent()) {
            handle.replayBuffer().terminate(org.atmosphere.ai.RunCancelledException.FRAME_TYPE,
                    cancelled.get().wire());
            return;
        }
        var cause = failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null ? failure.getCause() : failure;
        var message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        handle.replayBuffer().terminate("error", message);
    }

    /** Close the replay buffer of a run that is removed before its execution ended. */
    private static void endRemovedRun(AgentResumeHandle handle, String reason) {
        handle.replayBuffer().terminate("error", reason);
    }

    /**
     * Move a finished run out of the live set into the bounded replayable
     * set. Only the registered handle is moved, so a late completion callback
     * never retires a different run under the same id.
     */
    private void retire(AgentResumeHandle handle) {
        if (!runs.remove(handle.runId(), handle)) {
            return;
        }
        synchronized (completed) {
            completed.put(handle.runId(), new Retired(handle, Instant.now(clock)));
            var it = completed.entrySet().iterator();
            while (completed.size() > MAX_RETAINED_COMPLETED && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /**
     * The client of tracking id {@code consumer} is gone for good: every run
     * still replayable, live or finished, forgets its replay position
     * ({@link RunEventReplayBuffer#forget}). Walks the live runs and at most
     * {@link #MAX_RETAINED_COMPLETED} finished ones.
     */
    public void forgetConsumer(String consumer) {
        if (consumer == null) {
            return;
        }
        for (var run : runs.values()) {
            run.replayBuffer().forget(consumer);
        }
        List<Retired> retired;
        synchronized (completed) {
            retired = List.copyOf(completed.values());
        }
        for (var run : retired) {
            run.handle().replayBuffer().forget(consumer);
        }
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
                if (runs.remove(entry.getKey(), entry.getValue())) {
                    openAfterPrompt.remove(entry.getKey());
                    endRemovedRun(entry.getValue(), "Run expired after the registry TTL of " + ttl);
                    removed++;
                    removeFromJournal(entry.getKey());
                    logger.trace("swept run {}", entry.getKey());
                }
            }
        }
        synchronized (completed) {
            completed.values().removeIf(r -> r.completedAt().isBefore(cutoff));
        }
        return removed;
    }

    /** Explicit removal — typically used when the owning session is closed. */
    public void unregister(String runId) {
        var removed = runs.remove(runId);
        if (removed != null) {
            openAfterPrompt.remove(runId);
            endRemovedRun(removed, "Run was unregistered");
        }
        synchronized (completed) {
            completed.remove(runId);
        }
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
        openAfterPrompt.clear();
        synchronized (completed) {
            completed.clear();
        }
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
     * still enforces caller-owns-run authorization (Invariant #6). The
     * rebuilt buffers have the default long-polling bounds
     * ({@link RunEventReplayBuffer#MAX_REPLAY_POSITIONS},
     * {@link RunEventReplayBuffer#MAX_WAITING_POLLS}): the registry belongs to
     * no application, so the init parameters of none apply to them.
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
            var buffer = new RunEventReplayBuffer(record.runId(), RunEventReplayBuffer.DEFAULT_CAPACITY);
            buffer.restore(journal.loadEvents(record.runId()));
            // Nothing writes to this buffer again: end it, so a client that
            // reattaches over a streaming transport gets the backlog and a
            // terminal frame instead of a live subscription that never ends.
            // Before the journal is attached, so the journal keeps exactly
            // what the run recorded.
            buffer.terminate("error", "Run did not survive a restart; its generation cannot continue");
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
