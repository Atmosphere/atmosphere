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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link WorkflowStore}. Adequate for development,
 * tests, and small single-node deployments where workflow definitions
 * are short-lived or can be reloaded from disk at boot. Production
 * deployments should override with a JDBC or Redis implementation; this
 * store loses all manifests on JVM restart and does not synchronize
 * across replicas.
 */
public final class InMemoryWorkflowStore implements WorkflowStore {

    private final ConcurrentHashMap<String, WorkflowManifest> manifests = new ConcurrentHashMap<>();

    @Override
    public List<WorkflowManifest> list() {
        var snapshot = new ArrayList<>(manifests.values());
        snapshot.sort(Comparator.comparing(m -> m.name().toLowerCase(java.util.Locale.ROOT)));
        return List.copyOf(snapshot);
    }

    @Override
    public Optional<WorkflowManifest> findById(String id) {
        return Optional.ofNullable(manifests.get(id));
    }

    @Override
    public WorkflowManifest save(WorkflowManifest manifest) {
        var saved = manifests.compute(manifest.id(), (id, existing) -> {
            if (existing != null && manifest.version() != existing.version() + 1) {
                throw new WorkflowStoreException(
                        WorkflowStoreException.Kind.VERSION_CONFLICT,
                        "workflow " + id + " version conflict: caller sent v"
                                + manifest.version() + ", server has v" + existing.version()
                                + " (expected v" + (existing.version() + 1) + ")");
            }
            return manifest;
        });
        return saved;
    }

    @Override
    public void delete(String id) {
        manifests.remove(id);
    }

    @Override
    public String name() {
        return "in-memory";
    }
}
