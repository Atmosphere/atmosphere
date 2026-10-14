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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * the run, which keeps writing into the original, now-closed session.
 * {@link #subscribe} hands back the retained backlog and registers a
 * listener for every later capture in one step under the buffer lock, so
 * nothing falls between the backlog and the first live event and nothing is
 * delivered twice. Listeners are notified outside the lock, are dropped when
 * they throw, and are released once a terminal {@code complete} /
 * {@code error} event is captured. At most {@link #MAX_SUBSCRIBERS} are held
 * per run (Correctness Invariant #3).
 */
public final class RunEventReplayBuffer {

    private static final Logger logger = LoggerFactory.getLogger(RunEventReplayBuffer.class);

    /** Default capacity — enough for a few minutes of streaming. */
    public static final int DEFAULT_CAPACITY = 1_024;

    /** Upper bound on live subscribers per run — one per reattached connection. */
    public static final int MAX_SUBSCRIBERS = 8;

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
    private final AtomicLong nextSequence = new AtomicLong();
    private final List<RunEvent> buffer;
    private volatile RunJournal journal = RunJournal.NOOP;
    private volatile String runId;
    /** Guarded by {@code buffer}. */
    private final List<Consumer<RunEvent>> subscribers = new ArrayList<>();
    /** Guarded by {@code buffer}: a terminal event was captured. */
    private boolean terminated;

    public RunEventReplayBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public RunEventReplayBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.capacity = capacity;
        this.buffer = Collections.synchronizedList(new ArrayList<>(capacity));
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
        var event = new RunEvent(
                nextSequence.getAndIncrement(),
                type,
                payload,
                Instant.now());
        List<Consumer<RunEvent>> listeners;
        synchronized (buffer) {
            buffer.add(event);
            while (buffer.size() > capacity) {
                buffer.remove(0);
            }
            listeners = subscribers.isEmpty() ? List.of() : List.copyOf(subscribers);
            if (isTerminal(type)) {
                terminated = true;
                subscribers.clear();
            }
        }
        mirrorToJournal(event);
        // Outside the lock: a listener writes to a network connection, and a
        // reattach holds its own write lock while it subscribes.
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                logger.warn("Run event subscriber failed on event {} of run {}; dropping it ({})",
                        event.sequence(), runId, e.toString());
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
                logger.warn("Run {} already has {} live subscribers; this reattach gets the "
                        + "backlog only", runId, MAX_SUBSCRIBERS);
                return new Subscription(backlog, false, () -> { });
            }
            subscribers.add(listener);
            return new Subscription(backlog, true, () -> unsubscribe(listener));
        }
    }

    /** Number of live subscribers. */
    public int subscriberCount() {
        synchronized (buffer) {
            return subscribers.size();
        }
    }

    private void unsubscribe(Consumer<RunEvent> listener) {
        synchronized (buffer) {
            subscribers.remove(listener);
        }
    }

    private static boolean isTerminal(String type) {
        return "complete".equals(type) || "error".equals(type);
    }

    /**
     * Re-seed this buffer from previously journaled events, preserving their
     * original sequence numbers so replay ordering survives a restart. Used
     * by {@link RunRegistry#rehydrate()}. Advances the internal sequence
     * counter past the highest restored sequence so any further captures do
     * not collide with rehydrated history.
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

    public int capacity() {
        return capacity;
    }

    public int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }
}
