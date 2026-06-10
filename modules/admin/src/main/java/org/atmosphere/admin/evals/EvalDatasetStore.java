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

import java.util.List;
import java.util.Optional;

/**
 * Persistence SPI for {@link EvalCase} dataset rows. Mirrors
 * {@link EvalRunStore}: an in-memory default ships
 * ({@link InMemoryEvalDatasetStore}); production swaps a durable backend so the
 * curated dataset survives restart.
 */
public interface EvalDatasetStore {

    /** List all cases, most recently captured first. */
    List<EvalCase> list();

    /** Single case by id. */
    Optional<EvalCase> findById(String id);

    /** Add a case. Duplicate ids are rejected with {@link IllegalStateException}. */
    EvalCase save(EvalCase evalCase);

    /** Delete a case by id. No-op when unknown. */
    void delete(String id);

    /** Optional descriptive name for the implementation. */
    default String name() {
        return getClass().getSimpleName();
    }
}
