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

import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded in-memory ring buffer of recent {@link RunEvent}s for a single
 * in-flight run. When a client disconnects and reconnects with the same
 * {@code runId}, the buffer replays the events it missed.
 *
 * <h2>Backpressure</h2>
 *
 * Capacity is bounded at construction; when the limit is exceeded the
 * oldest entries are evicted (Correctness Invariant #3 — every buffer
 * declares a size bound). Replay consumers MUST treat missing earlier
 * sequence numbers as "replay buffer too small; history unavailable" and
 * fall back to their own recovery story rather than assuming complete
 * replay.
 *
 * <h2>Durability</h2>
 *
 * A buffer may optionally mirror every captured event to a
 * {@link RunJournal} via {@link #attachJournal}. When a journal is attached
 * the registry persists captures so a fresh process can rehydrate the run's
 * events after a crash. Journal writes are best-effort (Correctness
 * Invariant #3) and never thrown into the live stream: the first failure is
 * logged at WARN — durability silently turning itself off is an operator
 * incident — and repeats at TRACE. By default the journal is
 * {@link RunJournal#NOOP} and capture adds zero extra work.
 *
 * <h2>Live subscribers</h2>
 *
 * A reconnecting client needs the events it missed <em>and</em> the rest of
 * the run, which keeps writing into the original, now-closed session. "The
 * run" is what {@link RunEventCapturingSession} captures: the text and the
 * terminal frame the client received, whether the {@code @Prompt} method sent
 * them or the runtime streamed them for {@code session.stream(...)}.
 * {@link #subscribe} hands back the retained backlog and registers a
 * listener for every later capture in one step under the buffer lock, so
 * nothing falls between the backlog and the first live event and nothing is
 * delivered twice. Listeners are notified outside the lock, are dropped when
 * they throw, and are released once a terminal {@code complete} /
 * {@code error} / {@code cancelled} event is captured. A run that ends without capturing one (its
 * {@code @Prompt} returned without completing, threw an {@link Error}, timed
 * out after its client left, or was removed from the {@link RunRegistry}) is
 * closed by {@link #terminate}, which the registry calls on each of those
 * paths, so every live listener receives a terminal frame and is released
 * (Correctness Invariant #2). At most {@link #MAX_SUBSCRIBERS} are held per run
 * (Correctness Invariant #3).
 *
 * <p>A long-polling poll waiting for the run's next event listens through
 * {@link #awaitNext} instead, apart from those subscribers: it takes none of
 * their places, so polls waiting on a run never stop a WebSocket, SSE or
 * streaming reattach from receiving the rest of it. At most one poll per
 * consumer (a tracking id) waits, the newer taking over from the older, and at
 * most {@link #maxWaitingPolls()} consumers' polls per run (Correctness
 * Invariant #3); waiting polls are notified and released like the subscribers.</p>
 *
 * <h2>Replay positions</h2>
 *
 * A long-polling client gets the run in pieces, one per poll, and re-sends the
 * run id on each one until it reads a terminal frame. {@link #claimNew} and
 * {@link #claim} hand each event to such a consumer once: the buffer remembers,
 * per consumer (a tracking id), the sequence of the last event it handed over,
 * for at most {@link #maxReplayPositions()} consumers per run, the least
 * recently used forgotten first. A forgotten consumer is handed the retained
 * events again. The frames a client received through a long-polling poll as a
 * reply ({@code org.atmosphere.ai.LongPollingReplies}) count as handed to it
 * too ({@link #handedUpTo}), so a later poll of it carrying the run id is not
 * handed them again.
 *
 * <p>Both bounds are {@value #MAX_REPLAY_POSITIONS} by default; {@link #forRun}
 * reads them from the init parameters {@value #MAX_REPLAY_POSITIONS_PARAM} and
 * {@value #MAX_WAITING_POLLS_PARAM} of the run's application.</p>
 */
public final class RunEventReplayBuffer {

    private static final Logger logger = LoggerFactory.getLogger(RunEventReplayBuffer.class);

    /** Default capacity — enough for a few minutes of streaming. */
    public static final int DEFAULT_CAPACITY = 1_024;

    /** Upper bound on live subscribers per run — one per reattached connection. */
    public static final int MAX_SUBSCRIBERS = 8;

    /**
     * Default upper bound on the consumers whose replay position one run
     * remembers; see {@link #MAX_REPLAY_POSITIONS_PARAM}.
     */
    public static final int MAX_REPLAY_POSITIONS = 64;

    /**
     * Default upper bound on the consumers whose long-polling poll waits for one
     * run's next event: as many as the consumers whose replay position it
     * remembers; see {@link #MAX_WAITING_POLLS_PARAM}.
     */
    public static final int MAX_WAITING_POLLS = MAX_REPLAY_POSITIONS;

    /**
     * Init parameter: the consumers (long-polling tracking ids) whose replay
     * position one run remembers; a positive integer, {@value #MAX_REPLAY_POSITIONS}
     * by default.
     */
    public static final String MAX_REPLAY_POSITIONS_PARAM = "org.atmosphere.ai.longPolling.maxReplayPositionsPerRun";

    /**
     * Init parameter: the consumers (long-polling tracking ids) whose poll may
     * wait for one run's next event at once; a positive integer,
     * {@value #MAX_WAITING_POLLS} by default.
     */
    public static final String MAX_WAITING_POLLS_PARAM = "org.atmosphere.ai.longPolling.maxWaitingPollsPerRun";

    /**
     * Where a run event is in its run, as a replay position: once a client
     * received the event's frame, {@link #reachedBy} records that it was handed
     * every event of the run up to this one.
     *
     * @param run      the run's buffer
     * @param sequence the event's sequence
     */
    public record Position(RunEventReplayBuffer run, long sequence) {

        public Position {
            Objects.requireNonNull(run, "run");
        }

        /** {@code consumer} received the run's frames up to this event; see {@link RunEventReplayBuffer#handedUpTo}. */
        public void reachedBy(String consumer) {
            run.handedUpTo(consumer, sequence);
        }
    }

    /**
     * The backlog a {@link #subscribe} call returned, whether later captures
     * will reach the listener, and the handle that stops them.
     *
     * @param backlog every retained event at subscription time, oldest first
     * @param live    {@code true} when the listener was registered; {@code false}
     *                when the run already ended or the subscriber cap was reached
     * @param cancel  removes the listener; idempotent, a no-op when not live
     */
    public record Subscription(List<RunEvent> backlog, boolean live, Runnable cancel) {
    }

    private final int capacity;
    private final int maxReplayPositions;
    private final int maxWaitingPolls;
    private final AtomicLong nextSequence = new AtomicLong();
    private final List<RunEvent> buffer;
    private volatile RunJournal journal = RunJournal.NOOP;
    private volatile String runId;
    /** Guarded by {@code buffer}. */
    private final List<Consumer<RunEvent>> subscribers = new ArrayList<>();
    /** Guarded by {@code buffer}: consumer -> the listener of its poll waiting for the next event. */
    private final Map<String, Consumer<RunEvent>> waitingPolls = new LinkedHashMap<>();
    /** Guarded by {@code buffer}: a terminal event was captured. */
    private boolean terminated;
    /** Guarded by {@code buffer}: consumer -> sequence of the last event handed to it, least recently used first. */
    private final Map<String, Long> positions = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > maxReplayPositions;
        }
    };

    public RunEventReplayBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public RunEventReplayBuffer(int capacity) {
        this(null, capacity);
    }

    /**
     * A buffer of the run {@code runId}, which its warnings name.
     *
     * @param runId    the run's id; {@code null} for a buffer of no registered run,
     *                 which {@link RunRegistry} names when it registers the run
     * @param capacity the events it retains
     */
    public RunEventReplayBuffer(String runId, int capacity) {
        this(runId, capacity, MAX_REPLAY_POSITIONS, MAX_WAITING_POLLS);
    }

    /**
     * A buffer of the run {@code runId}, with its long-polling bounds.
     *
     * @param runId              the run's id; {@code null} for a buffer of no
     *                           registered run, which {@link RunRegistry} names
     *                           when it registers the run
     * @param capacity           the events it retains
     * @param maxReplayPositions the consumers whose replay position it remembers
     * @param maxWaitingPolls    the consumers whose poll may wait for its next event
     * @throws IllegalArgumentException when a bound is not positive
     */
    public RunEventReplayBuffer(String runId, int capacity, int maxReplayPositions, int maxWaitingPolls) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        if (maxReplayPositions <= 0) {
            throw new IllegalArgumentException("maxReplayPositions must be > 0, got " + maxReplayPositions);
        }
        if (maxWaitingPolls <= 0) {
            throw new IllegalArgumentException("maxWaitingPolls must be > 0, got " + maxWaitingPolls);
        }
        this.capacity = capacity;
        this.maxReplayPositions = maxReplayPositions;
        this.maxWaitingPolls = maxWaitingPolls;
        this.runId = runId;
        this.buffer = Collections.synchronizedList(new ArrayList<>(capacity));
    }

    /**
     * A buffer of {@link #DEFAULT_CAPACITY} events for the run {@code runId},
     * with the long-polling bounds the init parameters
     * {@value #MAX_REPLAY_POSITIONS_PARAM} and {@value #MAX_WAITING_POLLS_PARAM}
     * of {@code config} set. A value that is not a positive integer is ignored,
     * with a warning, for the default.
     *
     * @param runId  the run's id
     * @param config the run's application; {@code null} for the defaults
     */
    public static RunEventReplayBuffer forRun(String runId, AtmosphereConfig config) {
        return new RunEventReplayBuffer(runId, DEFAULT_CAPACITY,
                positive(config, MAX_REPLAY_POSITIONS_PARAM, MAX_REPLAY_POSITIONS),
                positive(config, MAX_WAITING_POLLS_PARAM, MAX_WAITING_POLLS));
    }

    private static int positive(AtmosphereConfig config, String name, int defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        String raw;
        try {
            raw = config.getInitParameter(name);
        } catch (RuntimeException e) {
            logger.trace("Unable to read {}", name, e);
            return defaultValue;
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            var value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            logger.trace("{} is not a number", name, e);
        }
        logger.warn("Ignoring {}={}: not a positive integer; using {}", name, raw, defaultValue);
        return defaultValue;
    }

    /** The id of the run this buffer holds the events of, or {@code null} when it was given none. */
    public String runId() {
        return runId;
    }

    /** Names the run of a buffer created without an id; a no-op once it has one. */
    void nameRun(String id) {
        if (id == null) {
            return;
        }
        synchronized (buffer) {
            if (runId == null) {
                runId = id;
            }
        }
    }

    /** The run as the warnings name it. */
    private String describe() {
        var id = runId;
        return id != null ? "Run " + id : "A run with no id";
    }

    /**
     * Attach a durable journal so every subsequent {@link #capture} is also
     * persisted under {@code runId}. Idempotent and one-way: only the first
     * non-NOOP attach takes effect, so a registry cannot accidentally
     * rebind an active buffer to a different journal mid-run (Ownership —
     * Correctness Invariant #1). A {@code null} or NOOP journal leaves the
     * buffer in its default (in-memory only) state.
     */
    public void attachJournal(RunJournal journal, String runId) {
        if (journal == null || journal == RunJournal.NOOP || runId == null) {
            return;
        }
        synchronized (buffer) {
            if (this.journal == RunJournal.NOOP) {
                this.journal = journal;
                this.runId = runId;
            }
        }
    }

    /**
     * Capture an event. Returns the stored {@link RunEvent} with its
     * assigned sequence so the caller can thread it through the live
     * stream.
     */
    public RunEvent capture(String type, String payload) {
        return append(type, payload, false);
    }

    /**
     * End the run with a terminal {@code complete}, {@code error} or
     * {@code cancelled} event
     * unless one was already captured: the event is stored, delivered to every
     * live listener and the listeners are released, exactly as a captured
     * terminal event is. Idempotent; the first terminal event wins.
     *
     * @return {@code true} when this call ended the run, {@code false} when it
     *         had already ended
     */
    public boolean terminate(String type, String payload) {
        if (!isTerminal(type)) {
            throw new IllegalArgumentException("not a terminal event type: " + type);
        }
        return terminateEvent(type, payload) != null;
    }

    /**
     * {@link #terminate}, returning the terminal event stored, or {@code null}
     * when the run had already ended.
     */
    RunEvent terminateEvent(String type, String payload) {
        if (!isTerminal(type)) {
            throw new IllegalArgumentException("not a terminal event type: " + type);
        }
        return append(type, payload, true);
    }

    /** The sequence of the last event captured, or {@code -1} when none was. */
    long lastSequence() {
        return nextSequence.get() - 1;
    }

    /** Whether a terminal {@code complete} / {@code error} / {@code cancelled} event was captured. */
    public boolean isTerminated() {
        synchronized (buffer) {
            return terminated;
        }
    }

    private RunEvent append(String type, String payload, boolean onlyIfOpen) {
        RunEvent event;
        List<Consumer<RunEvent>> listeners;
        synchronized (buffer) {
            if (onlyIfOpen && terminated) {
                return null;
            }
            event = new RunEvent(
                    nextSequence.getAndIncrement(),
                    type,
                    payload,
                    Instant.now());
            buffer.add(event);
            while (buffer.size() > capacity) {
                buffer.remove(0);
            }
            listeners = listeners();
            if (isTerminal(type)) {
                terminated = true;
                subscribers.clear();
                waitingPolls.clear();
            }
        }
        mirrorToJournal(event);
        // Outside the lock: a listener writes to a network connection, and a
        // reattach holds its own write lock while it subscribes.
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                logger.warn("A run event subscriber failed on event {} ({}); dropping it ({})",
                        event.sequence(), describe(), e.toString());
                unsubscribe(listener);
            }
        }
        return event;
    }

    /**
     * Return the retained backlog and, unless the run already ended or
     * {@link #MAX_SUBSCRIBERS} listeners are registered, deliver every later
     * capture to {@code listener}. Backlog and registration happen atomically,
     * so the two together are the complete, duplicate-free event stream.
     */
    public Subscription subscribe(Consumer<RunEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (buffer) {
            var backlog = List.copyOf(buffer);
            if (terminated) {
                return new Subscription(backlog, false, () -> { });
            }
            if (subscribers.size() >= MAX_SUBSCRIBERS) {
                logger.warn("{} already has {} live subscribers; this WebSocket, SSE or streaming "
                        + "reattach gets the backlog only", describe(), MAX_SUBSCRIBERS);
                return new Subscription(backlog, false, () -> { });
            }
            subscribers.add(listener);
            return new Subscription(backlog, true, () -> unsubscribe(listener));
        }
    }

    /**
     * Unless the run already ended, deliver every later capture to
     * {@code listener}, the long-polling poll of {@code consumer} waiting for the
     * run's next event, in place of an older poll of {@code consumer} still
     * waiting. Not counted against {@link #MAX_SUBSCRIBERS}; refused when
     * {@link #maxWaitingPolls()} other consumers' polls wait. Backlog and
     * registration happen atomically, as for {@link #subscribe}.
     */
    public Subscription awaitNext(String consumer, Consumer<RunEvent> listener) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(listener, "listener");
        synchronized (buffer) {
            var backlog = List.copyOf(buffer);
            if (terminated) {
                return new Subscription(backlog, false, () -> { });
            }
            if (waitingPolls.size() >= maxWaitingPolls && !waitingPolls.containsKey(consumer)) {
                logger.warn("{} already has polls of {} clients waiting for its next event; this poll "
                        + "waits for a reply or its suspend timeout, and its client's next poll gets what "
                        + "it missed", describe(), maxWaitingPolls);
                return new Subscription(backlog, false, () -> { });
            }
            waitingPolls.put(consumer, listener);
            return new Subscription(backlog, true, () -> {
                synchronized (buffer) {
                    waitingPolls.remove(consumer, listener);
                }
            });
        }
    }

    /** Number of live listeners: subscribers and waiting polls. */
    public int subscriberCount() {
        synchronized (buffer) {
            return subscribers.size() + waitingPolls.size();
        }
    }

    /** Guarded by {@code buffer}: a copy of every live listener. */
    private List<Consumer<RunEvent>> listeners() {
        if (subscribers.isEmpty() && waitingPolls.isEmpty()) {
            return List.of();
        }
        var all = new ArrayList<Consumer<RunEvent>>(subscribers.size() + waitingPolls.size());
        all.addAll(subscribers);
        all.addAll(waitingPolls.values());
        return List.copyOf(all);
    }

    private void unsubscribe(Consumer<RunEvent> listener) {
        synchronized (buffer) {
            subscribers.remove(listener);
            waitingPolls.values().remove(listener);
        }
    }

    /**
     * Whether {@code type} ends a run: {@code complete}, {@code error} or
     * {@code cancelled}.
     */
    public static boolean isTerminal(String type) {
        return "complete".equals(type) || "error".equals(type)
                || org.atmosphere.ai.RunCancelledException.FRAME_TYPE.equals(type);
    }

    /**
     * Re-seed this buffer from previously journaled events, preserving their
     * original sequence numbers so replay ordering survives a restart. Used
     * by {@link RunRegistry#rehydrate()}. Advances the internal sequence
     * counter past the highest restored sequence so any further captures do
     * not collide with rehydrated history. A restored terminal event ends the
     * run, as a captured one does.
     */
    void restore(List<RunEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        synchronized (buffer) {
            var maxSeq = -1L;
            for (var event : events) {
                buffer.add(event);
                maxSeq = Math.max(maxSeq, event.sequence());
                terminated |= isTerminal(event.type());
            }
            while (buffer.size() > capacity) {
                buffer.remove(0);
            }
            nextSequence.set(maxSeq + 1);
        }
    }

    private void mirrorToJournal(RunEvent event) {
        var j = journal;
        if (j == RunJournal.NOOP) {
            return;
        }
        try {
            j.appendEvent(runId, event);
        } catch (RuntimeException e) {
            // Best-effort persistence (Correctness Invariant #3): a journal
            // failure must never break the live stream. The run falls back
            // to in-memory-only replay — the FIRST failure is an
            // operator-visible incident (durability silently off is exactly
            // what a provisioned deployment must not discover at restore
            // time); repeats stay at TRACE off the hot path.
            if (DEGRADED_WARNED.compareAndSet(false, true)) {
                logger.warn("RunJournal.appendEvent failed — durable run replay has "
                        + "degraded to IN-MEMORY for run {}: buffered events will not "
                        + "survive a restart until the journal recovers ({}: {})",
                        runId, e.getClass().getSimpleName(), e.getMessage(), e);
            } else {
                logger.trace("RunJournal.appendEvent failed for run {} ({}): {}",
                        runId, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /** One-shot latch: the first journal failure warns, the rest trace. */
    private static final java.util.concurrent.atomic.AtomicBoolean DEGRADED_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Test isolation for the one-shot degradation WARN. */
    static void resetDegradationWarningForTests() {
        DEGRADED_WARNED.set(false);
    }

    /** Snapshot of all currently retained events, oldest first. */
    public List<RunEvent> snapshot() {
        synchronized (buffer) {
            return List.copyOf(buffer);
        }
    }

    /**
     * Events captured with {@code sequence >= fromSequence}, oldest first.
     * Used when a reconnecting client already knows the last sequence it
     * saw and only needs what came after.
     */
    public List<RunEvent> replayFrom(long fromSequence) {
        synchronized (buffer) {
            var out = new ArrayList<RunEvent>();
            for (var event : buffer) {
                if (event.sequence() >= fromSequence) {
                    out.add(event);
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * The retained events not handed to {@code consumer} yet, oldest first; they
     * count as handed to it from now on. Two calls for one consumer never
     * return the same event.
     */
    public List<RunEvent> claimNew(String consumer) {
        Objects.requireNonNull(consumer, "consumer");
        synchronized (buffer) {
            var last = positions.getOrDefault(consumer, -1L);
            var out = new ArrayList<RunEvent>();
            for (var event : buffer) {
                if (event.sequence() > last) {
                    out.add(event);
                }
            }
            if (!out.isEmpty()) {
                positions.put(consumer, out.get(out.size() - 1).sequence());
            }
            return List.copyOf(out);
        }
    }

    /**
     * Whether {@code event} was not handed to {@code consumer} yet; it counts as
     * handed to it from now on.
     */
    public boolean claim(String consumer, RunEvent event) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(event, "event");
        synchronized (buffer) {
            if (event.sequence() <= positions.getOrDefault(consumer, -1L)) {
                return false;
            }
            positions.put(consumer, event.sequence());
            return true;
        }
    }

    /**
     * {@code consumer} received the run's frames up to the event
     * {@code sequence} some other way than a replay (a long-polling reply): no
     * {@link #claimNew} or {@link #claim} hands it those events. A position
     * already past {@code sequence} stays.
     */
    public void handedUpTo(String consumer, long sequence) {
        Objects.requireNonNull(consumer, "consumer");
        synchronized (buffer) {
            positions.merge(consumer, sequence, Math::max);
        }
    }

    /**
     * Give back to {@code consumer} the events it claimed from {@code sequence}
     * on, because they never reached it: the next {@link #claimNew} hands them
     * over again.
     */
    public void unclaim(String consumer, long sequence) {
        Objects.requireNonNull(consumer, "consumer");
        synchronized (buffer) {
            var last = positions.get(consumer);
            if (last != null && last >= sequence) {
                positions.put(consumer, sequence - 1);
            }
        }
    }

    public int capacity() {
        return capacity;
    }

    /** The consumers whose replay position this run remembers at most. */
    public int maxReplayPositions() {
        return maxReplayPositions;
    }

    /** The consumers whose long-polling poll may wait for this run's next event at once. */
    public int maxWaitingPolls() {
        return maxWaitingPolls;
    }

    public int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }
}
