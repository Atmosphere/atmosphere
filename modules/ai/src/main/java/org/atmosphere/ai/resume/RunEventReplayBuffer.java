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
import java.util.concurrent.atomic.AtomicLong;

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
 * Invariant #3): a journal failure is logged at TRACE and never thrown into
 * the live stream. By default the journal is {@link RunJournal#NOOP} and
 * capture adds zero extra work.
 */
public final class RunEventReplayBuffer {

    private static final Logger logger = LoggerFactory.getLogger(RunEventReplayBuffer.class);

    /** Default capacity — enough for a few minutes of streaming. */
    public static final int DEFAULT_CAPACITY = 1_024;

    private final int capacity;
    private final AtomicLong nextSequence = new AtomicLong();
    private final List<RunEvent> buffer;
    private volatile RunJournal journal = RunJournal.NOOP;
    private volatile String runId;

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
        synchronized (buffer) {
            buffer.add(event);
            while (buffer.size() > capacity) {
                buffer.remove(0);
            }
        }
        mirrorToJournal(event);
        return event;
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
            // to in-memory-only replay; surface the cause at TRACE so it is
            // diagnosable without spamming the hot path.
            logger.trace("RunJournal.appendEvent failed for run {} ({}): {}",
                    runId, e.getClass().getSimpleName(), e.getMessage(), e);
        }
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
