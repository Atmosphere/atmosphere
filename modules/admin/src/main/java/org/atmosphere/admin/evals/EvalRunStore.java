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
 * Persistence SPI for {@link EvalRun} records. Implementations are
 * discovered via {@link java.util.ServiceLoader}; an in-memory
 * implementation ({@link InMemoryEvalRunStore}) ships as the default.
 *
 * <p>Production deployments override with a JDBC- or Elasticsearch-backed
 * implementation so the eval history survives JVM restart and spans
 * replicas.</p>
 */
public interface EvalRunStore {

    /** List all runs, most recent first. */
    List<EvalRun> list();

    /** List runs for a specific baseline, most recent first. */
    List<EvalRun> listForBaseline(String baseline);

    /** Single run by id. */
    Optional<EvalRun> findById(String id);

    /** Record a new run. Duplicate ids are rejected with {@link IllegalStateException}. */
    EvalRun save(EvalRun run);

    /** Delete a run by id. No-op when unknown. */
    void delete(String id);

    /** Optional descriptive name for the implementation. */
    default String name() {
        return getClass().getSimpleName();
    }
}
