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
package org.atmosphere.admin.evals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link EvalRunStore}. Bounded ring-buffer-style: holds
 * at most {@code maxRuns} per baseline so a long-running deployment does
 * not accumulate unbounded history (Correctness Invariant #3 — Backpressure:
 * every cache must declare a size bound). Production should swap in a
 * JDBC- or Elasticsearch-backed implementation.
 */
public final class InMemoryEvalRunStore implements EvalRunStore {

    private static final int DEFAULT_MAX_RUNS_PER_BASELINE = 500;

    private final ConcurrentHashMap<String, EvalRun> runs = new ConcurrentHashMap<>();
    private final int maxRunsPerBaseline;

    public InMemoryEvalRunStore() {
        this(DEFAULT_MAX_RUNS_PER_BASELINE);
    }

    public InMemoryEvalRunStore(int maxRunsPerBaseline) {
        if (maxRunsPerBaseline <= 0) {
            throw new IllegalArgumentException("maxRunsPerBaseline must be > 0");
        }
        this.maxRunsPerBaseline = maxRunsPerBaseline;
    }

    @Override
    public List<EvalRun> list() {
        var snapshot = new ArrayList<>(runs.values());
        snapshot.sort(Comparator.comparing(EvalRun::timestamp).reversed());
        return List.copyOf(snapshot);
    }

    @Override
    public List<EvalRun> listForBaseline(String baseline) {
        return list().stream()
                .filter(r -> r.baseline().equals(baseline))
                .toList();
    }

    @Override
    public Optional<EvalRun> findById(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public EvalRun save(EvalRun run) {
        if (runs.putIfAbsent(run.id(), run) != null) {
            throw new IllegalStateException(
                    "EvalRun id " + run.id() + " already exists; eval runs are immutable");
        }
        evictOldestForBaselineIfOverCap(run.baseline());
        return run;
    }

    @Override
    public void delete(String id) {
        runs.remove(id);
    }

    @Override
    public String name() {
        return "in-memory";
    }

    private void evictOldestForBaselineIfOverCap(String baseline) {
        var forBaseline = runs.values().stream()
                .filter(r -> r.baseline().equals(baseline))
                .sorted(Comparator.comparing(EvalRun::timestamp))
                .toList();
        int excess = forBaseline.size() - maxRunsPerBaseline;
        for (int i = 0; i < excess; i++) {
            runs.remove(forBaseline.get(i).id());
        }
    }
}
