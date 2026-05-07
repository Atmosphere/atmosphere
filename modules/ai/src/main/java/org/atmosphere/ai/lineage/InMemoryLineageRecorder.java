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
package org.atmosphere.ai.lineage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded ring-buffer {@link LineageRecorder} for development / tests / the
 * admin console's "recent activity" panel. Mirrors
 * {@code GovernanceDecisionLog}'s ring-buffer shape so operators get a
 * uniform mental model across the audit surfaces.
 *
 * <p>Production deployments wire a recorder that persists to Kafka /
 * Postgres / S3 — this class is not designed to survive process restart.</p>
 */
public final class InMemoryLineageRecorder implements LineageRecorder {

    /**
     * Default capacity — bounded so the recorder never exhibits unbounded
     * growth (Correctness Invariant #3, Backpressure). Operators that want
     * more history install a recorder backed by a persistent sink.
     */
    public static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    private final Deque<LineageEntry> entries;

    public InMemoryLineageRecorder() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryLineageRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(Math.min(capacity, 256));
    }

    @Override
    public void record(LineageEntry entry) {
        if (entry == null) {
            return;
        }
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
    }

    /** Snapshot of the most-recent entries, newest first, up to {@code limit}. */
    public List<LineageEntry> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (entries) {
            var size = entries.size();
            var n = Math.min(limit, size);
            var out = new java.util.ArrayList<LineageEntry>(n);
            var it = entries.descendingIterator();
            while (it.hasNext() && out.size() < n) {
                out.add(it.next());
            }
            return List.copyOf(out);
        }
    }

    /** Total entries currently buffered. */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** Clear every entry. */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }
}
