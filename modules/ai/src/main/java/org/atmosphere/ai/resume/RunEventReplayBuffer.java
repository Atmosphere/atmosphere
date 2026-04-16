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
 */
public final class RunEventReplayBuffer {

    /** Default capacity — enough for a few minutes of streaming. */
    public static final int DEFAULT_CAPACITY = 1_024;

    private final int capacity;
    private final AtomicLong nextSequence = new AtomicLong();
    private final List<RunEvent> buffer;

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
        return event;
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
