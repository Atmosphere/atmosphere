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
package org.atmosphere.ai.tape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous write path between {@link TapeRecordingSession} producers and a
 * {@link TapeStore} — modeled on the {@code AsyncAuditSink} idiom so the
 * streaming hot path never blocks on persistence.
 *
 * <h2>Delivery semantics</h2>
 * <ul>
 *   <li>Bounded in-memory queue (default capacity 8192). On queue-full, steps
 *       are <b>dropped</b> and counted per run and in total — never blocking
 *       the producer (Correctness Invariant #3, Backpressure).</li>
 *   <li>Single background virtual thread drains batches, groups them per run
 *       and appends via {@link TapeStore#append}.</li>
 *   <li><b>Terminals do not ride the droppable queue</b>: each open run has
 *       one control slot ({@code requestedTerminal}) the writer drains
 *       unconditionally every cycle. Queue overflow can drop steps, never
 *       terminals. Before applying a terminal the writer drains the queue
 *       once more, so steps a session offered before its terminal signal
 *       land ahead of the status write; genuinely-late steps then hit the
 *       append-after-terminal drop-and-count path.</li>
 *   <li>Terminal status is write-once — the first terminal wins; later
 *       terminal signals increment {@link #lateTerminals()} and never flip
 *       the status.</li>
 *   <li>Store failures are logged once per run at WARN, counted, and never
 *       propagate (the tape is best-effort; it must never fail a healthy
 *       stream).</li>
 *   <li>Idle sweep: an OPEN run with no append for the configured idle
 *       timeout is flushed and closed as {@link TapeStatus#ABANDONED}.</li>
 *   <li>Bounded uninstall: {@link #close()} stops intake (sessions holding a
 *       closed recorder degrade to no-ops), drains with a 2s join timeout,
 *       then drops the remainder with a WARN and final counts. The recorder
 *       never closes the store — the installer that created the store owns
 *       its lifecycle (Correctness Invariant #1, Ownership).</li>
 * </ul>
 */
public final class TapeRecorder {

    private static final Logger logger = LoggerFactory.getLogger(TapeRecorder.class);

    /** Writer tick / poll granularity — bounds control-map and sweep latency. */
    private static final long TICK_MILLIS = 100;

    /**
     * Recorder tuning. Store-level bounds (max runs, max steps per run) live
     * on the {@link TapeStore}; these are the write-path knobs.
     *
     * @param queueCapacity     bounded step-queue capacity
     * @param maxTextChars      per-run text accumulator cap before a forced flush
     * @param idleTimeout       OPEN runs with no append for this long are ABANDONED
     * @param textFlushInterval min age before the writer tick flushes accumulated text
     */
    public record Config(int queueCapacity, int maxTextChars,
                         Duration idleTimeout, Duration textFlushInterval) {

        public Config {
            if (queueCapacity <= 0) {
                throw new IllegalArgumentException("queueCapacity must be > 0, got " + queueCapacity);
            }
            if (maxTextChars <= 0) {
                throw new IllegalArgumentException("maxTextChars must be > 0, got " + maxTextChars);
            }
            Objects.requireNonNull(idleTimeout, "idleTimeout");
            Objects.requireNonNull(textFlushInterval, "textFlushInterval");
            if (idleTimeout.isNegative() || idleTimeout.isZero()) {
                throw new IllegalArgumentException("idleTimeout must be positive, got " + idleTimeout);
            }
            if (textFlushInterval.isNegative() || textFlushInterval.isZero()) {
                throw new IllegalArgumentException("textFlushInterval must be positive, got "
                        + textFlushInterval);
            }
        }

        /** 8192-step queue, 256 KiB text cap, 30 min idle timeout, 10 s text flush. */
        public static Config defaults() {
            return new Config(8192, 262_144, Duration.ofMinutes(30), Duration.ofSeconds(10));
        }
    }

    /**
     * Mutable per-run write state shared between one {@link TapeRecordingSession}
     * and the writer thread. The control slot ({@code requestedTerminal}) is the
     * non-droppable terminal channel — one slot per open run.
     */
    static final class OpenRun {
        volatile String runId;
        volatile String userId;
        final String tapeId;
        final String sessionId;
        final String resourceUuid;
        final String endpoint;
        final String model;
        final String runtimeName;
        final long startedAt = System.currentTimeMillis();
        final AtomicReference<TapeStatus> requestedTerminal = new AtomicReference<>();
        final AtomicLong dropped = new AtomicLong();
        volatile TapeRecordingSession session;
        volatile boolean terminalApplied;
        volatile long lastActivityNanos = System.nanoTime();
        // Writer-thread-only state below.
        long nextSeq;
        long persistedSteps;
        boolean begun;
        boolean truncated;
        boolean storeFailureLogged;

        OpenRun(String runId, TapeRunInfo info, String sessionId) {
            this.runId = runId;
            this.userId = info.userId();
            this.tapeId = info.tapeId();
            this.sessionId = sessionId;
            this.resourceUuid = info.resourceUuid();
            this.endpoint = info.endpoint();
            this.model = info.model();
            this.runtimeName = info.runtimeName();
        }
    }

    private record StepOp(OpenRun run, String kind, String payload, long ts) {
    }

    private final TapeStore store;
    private final Config config;
    private final BlockingQueue<StepOp> queue;
    private final Set<OpenRun> openRuns = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<OpenRun>> byResource = new ConcurrentHashMap<>();
    private final Thread writer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong droppedTotal = new AtomicLong();
    private final AtomicLong lateTerminalsTotal = new AtomicLong();
    private final AtomicLong storeFailuresTotal = new AtomicLong();
    private final AtomicLong unserializableTotal = new AtomicLong();

    public TapeRecorder(TapeStore store) {
        this(store, Config.defaults());
    }

    public TapeRecorder(TapeStore store, Config config) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (store == TapeStore.NOOP) {
            throw new IllegalArgumentException(
                    "TapeStore.NOOP cannot back a recorder — leave the tape uninstalled instead");
        }
        this.store = store;
        this.config = Objects.requireNonNull(config, "config");
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.writer = Thread.ofVirtual()
                .name("atmosphere-tape-writer-" + store.name())
                .start(this::drainLoop);
    }

    /**
     * Cancel-mark every open run of a disconnected resource. Their pending
     * text is flushed first, and the writer drains already-queued steps for
     * those runs before the CANCELLED status lands. Already-terminal runs are
     * untouched — a socket closing after normal completion is the normal
     * lifecycle, not a late terminal.
     */
    public void resourceDisconnected(String resourceUuid) {
        if (resourceUuid == null || closed.get()) {
            return;
        }
        var affected = byResource.remove(resourceUuid);
        if (affected == null) {
            return;
        }
        for (var run : affected) {
            var recordingSession = run.session;
            if (recordingSession != null) {
                recordingSession.terminalFlush();
            }
            run.requestedTerminal.compareAndSet(null, TapeStatus.CANCELLED);
        }
    }

    /**
     * Stop intake, drain with a 2s bound, drop the remainder with a WARN and
     * final counts. Idempotent. Never closes the store (Ownership — the
     * installer that created the store closes it).
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        writer.interrupt();
        try {
            writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var remainder = queue.size();
        if (writer.isAlive() || remainder > 0) {
            droppedTotal.addAndGet(remainder);
            queue.clear();
            logger.warn("Tape writer for store '{}' did not drain within 2s — dropped {} queued "
                            + "steps (total dropped={}, lateTerminals={}, storeFailures={}, "
                            + "unserializable={})",
                    store.name(), remainder, droppedTotal.get(), lateTerminalsTotal.get(),
                    storeFailuresTotal.get(), unserializableTotal.get());
        } else {
            logger.debug("Tape recorder for store '{}' closed (dropped={}, lateTerminals={}, "
                            + "storeFailures={}, unserializable={})",
                    store.name(), droppedTotal.get(), lateTerminalsTotal.get(),
                    storeFailuresTotal.get(), unserializableTotal.get());
        }
    }

    /** Whether {@link #close()} has stopped intake. */
    public boolean isClosed() {
        return closed.get();
    }

    /** The backing store. The recorder does not own its lifecycle. */
    public TapeStore store() {
        return store;
    }

    /** The write-path configuration. */
    public Config config() {
        return config;
    }

    /** Steps produced but not persisted (queue overflow, after-terminal, store failure, cap). */
    public long droppedSteps() {
        return droppedTotal.get();
    }

    /** Terminal signals that arrived after a run's first terminal (never a status flip). */
    public long lateTerminals() {
        return lateTerminalsTotal.get();
    }

    /** Store calls that threw; contained, never propagated. */
    public long storeFailures() {
        return storeFailuresTotal.get();
    }

    /** Payloads replaced by the {@code _unserializable} placeholder. */
    public long unserializablePayloads() {
        return unserializableTotal.get();
    }

    /** Current queue depth — for gauge wiring. */
    public int queueDepth() {
        return queue.size();
    }

    /** Number of runs currently tracked as open. */
    public int openRunCount() {
        return openRuns.size();
    }

    // ------------------------------------------------------------------
    // Producer-side seams (called by TapeRecordingSession)
    // ------------------------------------------------------------------

    /** Track a newly wrapped run so the writer's control, flush, and sweep passes see it. */
    void track(OpenRun run, TapeRecordingSession session) {
        run.session = session;
        if (closed.get()) {
            // Degrade: a session holding a closed recorder is a pure forwarder.
            return;
        }
        openRuns.add(run);
        if (run.resourceUuid != null) {
            byResource.computeIfAbsent(run.resourceUuid, k -> ConcurrentHashMap.newKeySet())
                    .add(run);
        }
    }

    /**
     * Enqueue a step for the writer thread: offer-else-drop — never blocks the
     * producer; a full queue drops the step and counts it (rate-limited WARN)
     * rather than propagating the backpressure signal to the streaming path.
     */
    void enqueue(OpenRun run, String kind, String payload, long ts) {
        if (closed.get()) {
            return;
        }
        run.lastActivityNanos = System.nanoTime();
        if (!queue.offer(new StepOp(run, kind, payload, ts))) {
            countDropped(run);
        }
    }

    /**
     * Non-droppable terminal signal: first CAS wins; a later terminal is
     * counted, never a status flip.
     */
    void requestTerminal(OpenRun run, TapeStatus status) {
        if (!run.requestedTerminal.compareAndSet(null, status)) {
            lateTerminalsTotal.incrementAndGet();
        }
    }

    void countDropped(OpenRun run) {
        run.dropped.incrementAndGet();
        var total = droppedTotal.incrementAndGet();
        if (total == 1 || total % 100 == 0) {
            logger.warn("Session tape dropped {} steps so far (store '{}') — queue full, "
                    + "terminal reached, or store failing", total, store.name());
        }
    }

    void countLateTerminal() {
        lateTerminalsTotal.incrementAndGet();
    }

    void countUnserializable() {
        unserializableTotal.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // Writer thread
    // ------------------------------------------------------------------

    private void drainLoop() {
        var batch = new ArrayList<StepOp>(256);
        while (!closed.get()) {
            try {
                cycle(batch);
            } catch (InterruptedException e) {
                if (closed.get()) {
                    break;
                }
                // Spurious interrupt while running: keep draining.
            } catch (RuntimeException e) {
                // A store or serialization bug must never kill the writer.
                logger.warn("Tape writer cycle failed: {}", e.toString(), e);
            }
        }
        // Graceful shutdown: intake already stopped — bounded final drain,
        // then apply the remaining terminals. A run still OPEN here is left
        // OPEN on purpose: an in-flight run is a resume anchor (a graceful
        // shutdown or a crash may both leave the durable-run spine mid-drive),
        // so abandoning it would poison a later crash-resume, whose steps would
        // be dropped as append-after-terminal. Terminal-only retention protects
        // it exactly as SqliteEffectJournal protects a non-terminal effect run;
        // the in-process idle sweep is what bounds silent OPEN runs (external
        // input cannot hold a run OPEN past the idle timeout).
        try {
            batch.clear();
            queue.drainTo(batch);
            if (!batch.isEmpty()) {
                appendBatch(batch);
            }
            processControl(batch);
        } catch (RuntimeException e) {
            logger.warn("Tape writer final drain failed: {}", e.toString(), e);
        }
    }

    private void cycle(List<StepOp> batch) throws InterruptedException {
        batch.clear();
        var first = queue.poll(TICK_MILLIS, TimeUnit.MILLISECONDS);
        if (first != null) {
            batch.add(first);
            queue.drainTo(batch);
            appendBatch(batch);
        }
        processControl(batch);
        tick();
    }

    /**
     * Apply requested terminals. The queue is drained once more first so
     * every step a session offered <em>before</em> its terminal signal
     * (happens-before via the control-slot CAS) lands ahead of the status
     * write — the disconnect / complete ordering contract.
     */
    private void processControl(List<StepOp> batch) {
        List<OpenRun> pending = null;
        for (var run : openRuns) {
            if (run.requestedTerminal.get() != null) {
                if (pending == null) {
                    pending = new ArrayList<>();
                }
                pending.add(run);
            }
        }
        if (pending == null) {
            return;
        }
        batch.clear();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            appendBatch(batch);
        }
        for (var run : pending) {
            applyTerminal(run, run.requestedTerminal.get());
        }
    }

    private void appendBatch(List<StepOp> batch) {
        var byRun = new LinkedHashMap<OpenRun, List<TapeStep>>();
        for (var op : batch) {
            var run = op.run();
            if (run.terminalApplied) {
                // Append-after-terminal: drop and count, never insert.
                countDropped(run);
                continue;
            }
            if (!ensureBegun(run)) {
                countDropped(run);
                continue;
            }
            var pendingForRun = byRun.get(run);
            var pendingCount = pendingForRun != null ? pendingForRun.size() : 0;
            if (run.persistedSteps + pendingCount >= store.maxStepsPerRun()) {
                // Step cap: stop-record + truncated flag — observability, not
                // the effect journal's fail-the-run.
                run.truncated = true;
                countDropped(run);
                continue;
            }
            var step = new TapeStep(run.runId, run.nextSeq++, op.kind(), op.payload(), op.ts());
            byRun.computeIfAbsent(run, k -> new ArrayList<>()).add(step);
        }
        for (var entry : byRun.entrySet()) {
            var run = entry.getKey();
            var steps = entry.getValue();
            try {
                store.append(run.runId, steps);
                run.persistedSteps += steps.size();
            } catch (RuntimeException e) {
                storeFailure(run, "append", e);
                run.dropped.addAndGet(steps.size());
                droppedTotal.addAndGet(steps.size());
            }
        }
    }

    private void applyTerminal(OpenRun run, TapeStatus status) {
        if (!run.terminalApplied) {
            if (ensureBegun(run)) {
                try {
                    store.markTerminal(run.runId, status, new TapeStore.Counters(
                            run.persistedSteps, run.dropped.get(), run.truncated));
                } catch (RuntimeException e) {
                    storeFailure(run, "markTerminal", e);
                }
            }
            run.terminalApplied = true;
        }
        openRuns.remove(run);
        if (run.resourceUuid != null) {
            byResource.computeIfPresent(run.resourceUuid, (k, set) -> {
                set.remove(run);
                return set.isEmpty() ? null : set;
            });
        }
    }

    /** Text-flush staleness + idle sweep, once per writer tick. */
    private void tick() {
        var now = System.nanoTime();
        var idleNanos = config.idleTimeout().toNanos();
        for (var run : openRuns) {
            if (run.terminalApplied || run.requestedTerminal.get() != null) {
                continue;
            }
            var recordingSession = run.session;
            var idle = now - run.lastActivityNanos > idleNanos;
            if (recordingSession != null) {
                if (idle) {
                    recordingSession.terminalFlush();
                } else {
                    recordingSession.flushTextIfStale(config.textFlushInterval());
                }
            }
            if (idle) {
                // ABANDONED can only apply to runs that never saw a terminal —
                // the CAS keeps write-once intact. Applied on the next control
                // pass, after the pre-terminal drain picks up the flush above.
                run.requestedTerminal.compareAndSet(null, TapeStatus.ABANDONED);
            }
        }
    }

    private boolean ensureBegun(OpenRun run) {
        if (run.begun) {
            return true;
        }
        try {
            store.begin(new TapeRun(run.runId, run.tapeId, run.sessionId, run.resourceUuid,
                    run.userId, run.endpoint, run.model, run.runtimeName, run.startedAt,
                    TapeStatus.OPEN, null, 0, 0, false, null));
            run.begun = true;
            return true;
        } catch (RuntimeException e) {
            storeFailure(run, "begin", e);
            return false;
        }
    }

    private void storeFailure(OpenRun run, String operation, RuntimeException e) {
        storeFailuresTotal.incrementAndGet();
        if (!run.storeFailureLogged) {
            run.storeFailureLogged = true;
            logger.warn("TapeStore '{}' {} failed for run {} — further failures for this run "
                            + "are counted silently: {}",
                    store.name(), operation, run.runId, e.toString());
        }
    }
}
