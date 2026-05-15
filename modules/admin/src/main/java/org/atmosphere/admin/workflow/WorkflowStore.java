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
package org.atmosphere.admin.workflow;

import java.util.List;
import java.util.Optional;

/**
 * Persistence SPI for {@link WorkflowManifest} records authored through the
 * admin control plane. Implementations are discovered via
 * {@link java.util.ServiceLoader}; an in-memory implementation
 * ({@link InMemoryWorkflowStore}) ships as the default. Production
 * deployments override with a JDBC- or Redis-backed implementation.
 *
 * <p>Methods are total — they never throw checked exceptions; runtime
 * failures (DB down, network partition) surface as
 * {@link WorkflowStoreException} which the admin controller maps to HTTP
 * 5xx. {@link #findById(String)} returns {@link Optional#empty()} for
 * missing rows rather than null so callers can chain.</p>
 */
public interface WorkflowStore {

    /** List all workflows, ordered by name (case-insensitive). */
    List<WorkflowManifest> list();

    /** Look up a single workflow by id. */
    Optional<WorkflowManifest> findById(String id);

    /**
     * Save the manifest. If a manifest with the same id already exists, the
     * implementation MUST reject saves whose {@link WorkflowManifest#version}
     * is not exactly {@code existing.version + 1} (optimistic concurrency
     * control) and throw {@link WorkflowStoreException}.
     */
    WorkflowManifest save(WorkflowManifest manifest);

    /** Delete the workflow by id. No-op when the id is unknown. */
    void delete(String id);

    /** Optional descriptive name for the implementation (used in logs). */
    default String name() {
        return getClass().getSimpleName();
    }
}
