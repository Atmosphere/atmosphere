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
package org.atmosphere.samples.springboot.passivation;

import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the durable snapshot store and the deterministic runtime the sample
 * passivates and resumes.
 *
 * <p>The default {@link InMemoryCheckpointStore} keeps the sample dependency-
 * free and offline — snapshots survive a pause/resume within a single JVM,
 * which is all this proof needs. Swapping in {@code SqliteCheckpointStore}
 * (from the same {@code atmosphere-checkpoint} module) or a Postgres store
 * makes the snapshots survive a full restart with no change to
 * {@link PassivationService}.</p>
 */
@Configuration
public class PassivationConfig {

    /**
     * The checkpoint store. {@code stop()} is the destroy method so Spring
     * releases the store on context shutdown (the creator-owns-lifecycle
     * contract — Atmosphere does not start or stop a store it did not create).
     */
    @Bean(destroyMethod = "stop")
    public CheckpointStore checkpointStore() {
        var store = new InMemoryCheckpointStore();
        store.start();
        return store;
    }

    @Bean
    public DemoContinuationRuntime demoContinuationRuntime() {
        return new DemoContinuationRuntime();
    }
}
