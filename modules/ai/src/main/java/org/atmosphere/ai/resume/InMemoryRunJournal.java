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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference {@link RunJournal}. Persists run metadata and events
 * in {@link ConcurrentHashMap}s and supports {@linkplain
 * RunRegistry#rehydrate() rehydration} of a fresh registry over the same
 * instance — which is how the resume wiring is proven in tests and what a
 * real backend (Redis/Postgres/disk) replaces by storing the same shape
 * out-of-process.
 *
 * <h2>Not crash-durable</h2>
 *
 * This implementation lives in heap and dies with the JVM, so
 * {@link #durable()} returns {@code false}. It is the SPI reference and the
 * mechanism proof — it demonstrates that a fresh {@link RunRegistry} built
 * over a populated journal rehydrates runs and their events — but it does
 * NOT survive a process restart. Supply a durable backend bean to advertise
 * crash-durable resume (Correctness Invariant #5).
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * Both the number of journaled runs and the events retained per run are
 * bounded. Per-run events evict oldest-first at {@link #maxEventsPerRun},
 * mirroring {@link RunEventReplayBuffer}'s ring semantics so replay
 * fidelity matches the live buffer. When the run count exceeds
 * {@link #maxRuns} the oldest run (by {@code createdAt}) is evicted, so an
 * abandoned-run leak cannot exhaust memory even if {@link #removeRun} is
 * never called for some runs.
 */
public final class InMemoryRunJournal implements RunJournal {

    /** Default cap on concurrently journaled runs. */
    public static final int DEFAULT_MAX_RUNS = 10_000;

    private final int maxRuns;
    private final int maxEventsPerRun;
    private final Map<String, RunRecord> records = new ConcurrentHashMap<>();
    private final Map<String, List<RunEvent>> events = new ConcurrentHashMap<>();

    public InMemoryRunJournal() {
        this(DEFAULT_MAX_RUNS, RunEventReplayBuffer.DEFAULT_CAPACITY);
    }

    public InMemoryRunJournal(int maxRuns, int maxEventsPerRun) {
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("maxRuns must be > 0, got " + maxRuns);
        }
        if (maxEventsPerRun <= 0) {
            throw new IllegalArgumentException("maxEventsPerRun must be > 0, got " + maxEventsPerRun);
        }
        this.maxRuns = maxRuns;
        this.maxEventsPerRun = maxEventsPerRun;
    }

    @Override
    public void recordRun(RunRecord run) {
        records.put(run.runId(), run);
        events.computeIfAbsent(run.runId(), k -> new ArrayList<>());
        evictOldestRunIfOverCapacity();
    }

    @Override
    public void appendEvent(String runId, RunEvent event) {
        // Only journal events for runs we know about — an event for an
        // unrecorded run (e.g. one already swept) is dropped rather than
        // resurrecting a half-run with no metadata.
        var list = events.get(runId);
        if (list == null) {
            return;
        }
        synchronized (list) {
            list.add(event);
            while (list.size() > maxEventsPerRun) {
                list.remove(0);
            }
        }
    }

    @Override
    public void removeRun(String runId) {
        records.remove(runId);
        events.remove(runId);
    }

    @Override
    public List<RunRecord> loadAll() {
        return List.copyOf(records.values());
    }

    @Override
    public List<RunEvent> loadEvents(String runId) {
        var list = events.get(runId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    @Override
    public boolean durable() {
        // In-memory: dies with the JVM. The SPI reference, not a crash-durable store.
        return false;
    }

    /** Visible for tests / admin: number of runs currently journaled. */
    public int runCount() {
        return records.size();
    }

    private void evictOldestRunIfOverCapacity() {
        while (records.size() > maxRuns) {
            var oldest = records.values().stream()
                    .min(Comparator.comparing(RunRecord::createdAt))
                    .map(RunRecord::runId)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            removeRun(oldest);
        }
    }
}
