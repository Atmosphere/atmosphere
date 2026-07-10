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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference {@link TapeStore}. Bounded on both axes
 * (Correctness Invariant #3): at most {@link #maxRuns()} retained runs —
 * evicting the oldest <em>terminal</em> run only, never an in-flight one
 * (the {@code InMemoryEffectJournal} retention idiom) — and at most
 * {@link #maxStepsPerRun()} steps per run, beyond which appends are ignored
 * and the run is flagged {@link TapeRun#truncated() truncated}.
 *
 * <p>Not crash-durable: dies with the JVM, so {@link #durable()} returns
 * {@code false} (Correctness Invariant #5 — a deployment must supply a
 * {@code durable() == true} backend before advertising a durable tape).
 * Because the store is process-local, a same-process re-{@code begin} of an
 * existing run keeps its prior steps; the writer's sequence numbers restart
 * per recorder, so a same-process crash-resume against this store may
 * produce overlapping sequence numbers — durable stores continue at
 * {@code MAX(seq)+1} instead.</p>
 */
public final class InMemoryTapeStore implements TapeStore {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryTapeStore.class);

    /** Default cap on retained runs. */
    public static final int DEFAULT_MAX_RUNS = 1000;

    /** Default per-run step cap. */
    public static final int DEFAULT_MAX_STEPS_PER_RUN = 5000;

    private final int maxRuns;
    private final int maxStepsPerRun;
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public InMemoryTapeStore() {
        this(DEFAULT_MAX_RUNS, DEFAULT_MAX_STEPS_PER_RUN);
    }

    public InMemoryTapeStore(int maxRuns, int maxStepsPerRun) {
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("maxRuns must be > 0, got " + maxRuns);
        }
        if (maxStepsPerRun <= 0) {
            throw new IllegalArgumentException("maxStepsPerRun must be > 0, got " + maxStepsPerRun);
        }
        this.maxRuns = maxRuns;
        this.maxStepsPerRun = maxStepsPerRun;
    }

    @Override
    public void begin(TapeRun run) {
        var created = new boolean[]{false};
        var state = runs.computeIfAbsent(run.runId(), k -> {
            created[0] = true;
            return new RunState(run);
        });
        if (!created[0]) {
            // Idempotent upsert: refresh identity metadata, never regress
            // status / steps / counters (crash-resume re-begin contract).
            synchronized (state) {
                state.mergeIdentity(run);
            }
        }
        if (created[0]) {
            evictOldestTerminalIfOverCapacity();
        }
    }

    @Override
    public void append(String runId, List<TapeStep> steps) {
        var state = runs.get(runId);
        if (state == null) {
            logger.trace("append for unknown tape run {} — ignored", runId);
            return;
        }
        synchronized (state) {
            if (state.status.terminal()) {
                // Reject-or-ignore contract: never insert after terminal.
                return;
            }
            for (var step : steps) {
                if (state.steps.size() >= maxStepsPerRun) {
                    state.truncated = true;
                    return;
                }
                state.steps.add(step);
            }
        }
    }

    @Override
    public void markTerminal(String runId, TapeStatus status, Counters counters) {
        if (!status.terminal()) {
            throw new IllegalArgumentException("terminal status required, got " + status);
        }
        var state = runs.get(runId);
        if (state == null) {
            logger.trace("markTerminal for unknown tape run {} — ignored", runId);
            return;
        }
        synchronized (state) {
            if (state.status.terminal()) {
                // Write-once: first terminal wins, never a status flip.
                return;
            }
            state.status = status;
            state.endedAt = System.currentTimeMillis();
            state.droppedSteps = counters.droppedSteps();
            state.truncated = state.truncated || counters.truncated();
        }
        evictOldestTerminalIfOverCapacity();
    }

    @Override
    public List<TapeRun> listRuns(TapeQuery query) {
        var matched = new ArrayList<TapeRun>();
        for (var state : runs.values()) {
            TapeRun run;
            synchronized (state) {
                run = state.materialize();
            }
            if (query.tapeId() != null && !query.tapeId().equals(run.tapeId())) {
                continue;
            }
            if (query.status() != null && query.status() != run.status()) {
                continue;
            }
            matched.add(run);
        }
        matched.sort(Comparator.comparingLong(TapeRun::startedAt).reversed()
                .thenComparing(TapeRun::runId));
        if (query.limit() > 0 && matched.size() > query.limit()) {
            return List.copyOf(matched.subList(0, query.limit()));
        }
        return List.copyOf(matched);
    }

    @Override
    public List<TapeStep> readSteps(String runId, long fromSeq, int max) {
        var state = runs.get(runId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            var window = new ArrayList<TapeStep>();
            for (var step : state.steps) {
                if (step.seq() < fromSeq) {
                    continue;
                }
                window.add(step);
                if (max > 0 && window.size() >= max) {
                    break;
                }
            }
            return List.copyOf(window);
        }
    }

    @Override
    public Optional<String> fork(String runId) {
        var source = runs.get(runId);
        if (source == null) {
            return Optional.empty();
        }
        var forkId = "tape-" + UUID.randomUUID();
        RunState forked;
        synchronized (source) {
            var meta = source.materialize();
            forked = new RunState(new TapeRun(forkId, meta.tapeId(), meta.sessionId(),
                    meta.resourceUuid(), meta.userId(), meta.endpoint(), meta.model(),
                    meta.runtimeName(), System.currentTimeMillis(), TapeStatus.OPEN,
                    null, 0, 0, false, runId));
            for (var step : source.steps) {
                forked.steps.add(new TapeStep(forkId, step.seq(), step.kind(),
                        step.payload(), step.ts()));
            }
        }
        runs.put(forkId, forked);
        evictOldestTerminalIfOverCapacity();
        return Optional.of(forkId);
    }

    @Override
    public void removeRun(String runId) {
        runs.remove(runId);
    }

    @Override
    public int maxRuns() {
        return maxRuns;
    }

    @Override
    public int maxStepsPerRun() {
        return maxStepsPerRun;
    }

    @Override
    public boolean durable() {
        // In-memory: dies with the JVM. The SPI reference, not a durable tape.
        return false;
    }

    @Override
    public String name() {
        return "in-memory";
    }

    /** Visible for tests / admin: number of runs currently retained. */
    public int runCount() {
        return runs.size();
    }

    private void evictOldestTerminalIfOverCapacity() {
        while (runs.size() > maxRuns) {
            var oldestTerminal = runs.entrySet().stream()
                    .filter(e -> {
                        synchronized (e.getValue()) {
                            return e.getValue().status.terminal();
                        }
                    })
                    .min(Comparator.comparingLong(e -> e.getValue().startedAt))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestTerminal == null) {
                // All runs are in-flight; never evict an open run's tape.
                return;
            }
            runs.remove(oldestTerminal);
        }
    }

    /** Per-run row + steps, mutated under its own monitor. */
    private static final class RunState {
        private final String runId;
        private final long startedAt;
        private final String parentRunId;
        private final List<TapeStep> steps = new ArrayList<>();
        private String tapeId;
        private String sessionId;
        private String resourceUuid;
        private String userId;
        private String endpoint;
        private String model;
        private String runtimeName;
        private TapeStatus status;
        private Long endedAt;
        private long droppedSteps;
        private boolean truncated;

        private RunState(TapeRun run) {
            this.runId = run.runId();
            this.startedAt = run.startedAt();
            this.parentRunId = run.parentRunId();
            this.tapeId = run.tapeId();
            this.sessionId = run.sessionId();
            this.resourceUuid = run.resourceUuid();
            this.userId = run.userId();
            this.endpoint = run.endpoint();
            this.model = run.model();
            this.runtimeName = run.runtimeName();
            this.status = run.status();
            this.endedAt = run.endedAt();
            this.droppedSteps = run.droppedSteps();
            this.truncated = run.truncated();
        }

        private void mergeIdentity(TapeRun run) {
            if (run.tapeId() != null) {
                tapeId = run.tapeId();
            }
            if (run.sessionId() != null) {
                sessionId = run.sessionId();
            }
            if (run.resourceUuid() != null) {
                resourceUuid = run.resourceUuid();
            }
            if (run.userId() != null) {
                userId = run.userId();
            }
            if (run.endpoint() != null) {
                endpoint = run.endpoint();
            }
            if (run.model() != null) {
                model = run.model();
            }
            if (run.runtimeName() != null) {
                runtimeName = run.runtimeName();
            }
        }

        private TapeRun materialize() {
            // stepCount is the store's own row count — runtime truth, not the
            // writer's view of what it believes it appended.
            return new TapeRun(runId, tapeId, sessionId, resourceUuid, userId,
                    endpoint, model, runtimeName, startedAt, status, endedAt,
                    steps.size(), droppedSteps, truncated, parentRunId);
        }
    }
}
