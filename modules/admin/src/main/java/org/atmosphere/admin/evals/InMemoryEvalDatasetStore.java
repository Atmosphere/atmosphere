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
 * Default in-memory {@link EvalDatasetStore}. Bounded (Correctness Invariant #3
 * — Backpressure: every cache declares a size bound): holds at most
 * {@code maxCases}, evicting the oldest. Production should swap a durable
 * backend so curated cases survive restart.
 */
public final class InMemoryEvalDatasetStore implements EvalDatasetStore {

    private static final int DEFAULT_MAX_CASES = 2000;

    private final ConcurrentHashMap<String, EvalCase> cases = new ConcurrentHashMap<>();
    private final int maxCases;

    public InMemoryEvalDatasetStore() {
        this(DEFAULT_MAX_CASES);
    }

    public InMemoryEvalDatasetStore(int maxCases) {
        if (maxCases <= 0) {
            throw new IllegalArgumentException("maxCases must be > 0");
        }
        this.maxCases = maxCases;
    }

    @Override
    public List<EvalCase> list() {
        var snapshot = new ArrayList<>(cases.values());
        snapshot.sort(Comparator.comparing(EvalCase::capturedAt).reversed());
        return List.copyOf(snapshot);
    }

    @Override
    public Optional<EvalCase> findById(String id) {
        return Optional.ofNullable(cases.get(id));
    }

    @Override
    public EvalCase save(EvalCase evalCase) {
        if (cases.putIfAbsent(evalCase.id(), evalCase) != null) {
            throw new IllegalStateException(
                    "EvalCase id " + evalCase.id() + " already exists");
        }
        evictOldestIfOverCap();
        return evalCase;
    }

    @Override
    public void delete(String id) {
        cases.remove(id);
    }

    @Override
    public String name() {
        return "in-memory";
    }

    private void evictOldestIfOverCap() {
        var excess = cases.size() - maxCases;
        if (excess <= 0) {
            return;
        }
        cases.values().stream()
                .sorted(Comparator.comparing(EvalCase::capturedAt))
                .limit(excess)
                .map(EvalCase::id)
                .forEach(cases::remove);
    }
}
