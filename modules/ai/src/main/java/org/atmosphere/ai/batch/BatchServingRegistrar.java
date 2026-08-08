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
package org.atmosphere.ai.batch;

import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Opt-in wiring for the durable batch endpoint. Annotation processors
 * ({@code AgentProcessor} for {@code @Agent}, {@code AiEndpointProcessor} for
 * {@code @AiEndpoint}) call {@link #registerAgent} for every pipeline they
 * build; the first call on an enabled framework creates the job store
 * ({@link SqliteBatchJobStore} when {@code atmosphere.ai.batch.db} is set,
 * bounded in-memory otherwise), the {@link BatchExecutor} (which runs the
 * restart-recovery sweep), and a single shared {@link BatchHandler} at
 * {@link BatchServing#BATCHES_PATH}; every call adds the pipeline to the
 * handler's serving registry.
 *
 * <p>When {@code atmosphere.ai.batch.enabled} is unset or {@code false}
 * (the default), this is a no-op: no handler, no store, no new inbound
 * surface (Correctness Invariant #6).</p>
 */
public final class BatchServingRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(BatchServingRegistrar.class);

    /** Framework-properties key holding the per-framework shared handler. */
    static final String HANDLER_PROPERTY = "org.atmosphere.ai.batch.handler";

    private BatchServingRegistrar() {
    }

    /**
     * Register a pipeline as a batch-servable target named {@code name}.
     *
     * @param framework the framework the agent belongs to
     * @param name      the serving name (agent name, or the last segment of an
     *                  {@code @AiEndpoint} path)
     * @param pipeline  the governed dispatch pipeline for the agent
     * @param memory    the agent's conversation memory (may be {@code null});
     *                  batch items dispatch under unique per-item keys that
     *                  the executor clears afterwards
     * @return {@code true} when the batch surface is enabled and the pipeline
     *         was registered, {@code false} when the endpoint is disabled
     */
    public static synchronized boolean registerAgent(AtmosphereFramework framework, String name,
                                                     AiPipeline pipeline,
                                                     AiConversationMemory memory) {
        var config = framework.getAtmosphereConfig();
        var serving = BatchServing.from(config);
        if (!serving.enabled()) {
            return false;
        }
        var properties = config.properties();
        var handler = (BatchHandler) properties.get(HANDLER_PROPERTY);
        if (handler == null) {
            handler = createHandler(framework, serving);
            properties.put(HANDLER_PROPERTY, handler);
        }
        handler.register(name, pipeline, memory);
        logger.debug("Agent '{}' registered with the batch endpoint", name);
        return true;
    }

    /**
     * Whether batch serving is enabled for the given framework — lets callers
     * skip building a pipeline entirely when the endpoint is off.
     */
    public static boolean enabled(AtmosphereFramework framework) {
        return BatchServing.from(framework.getAtmosphereConfig()).enabled();
    }

    /**
     * The executor backing the framework's batch surface, when one has been
     * registered — the programmatic seam for in-process consumers such as the
     * admin eval dataset runner. Empty while the surface is disabled or no
     * agent has registered yet (runtime truth, Invariant #5). Callers must
     * never close the returned executor (Invariant #1).
     */
    public static Optional<BatchExecutor> executor(AtmosphereFramework framework) {
        var handler = (BatchHandler) framework.getAtmosphereConfig().properties()
                .get(HANDLER_PROPERTY);
        return handler == null ? Optional.empty() : Optional.of(handler.executor());
    }

    private static BatchHandler createHandler(AtmosphereFramework framework,
                                              BatchServing serving) {
        var store = serving.dbPath() != null
                ? (BatchJobStore) new SqliteBatchJobStore(Path.of(serving.dbPath()),
                        serving.retainedTerminalJobs())
                : new InMemoryBatchJobStore(serving.retainedTerminalJobs());
        BatchExecutor executor = null;
        try {
            executor = new BatchExecutor(store, serving.maxOpenJobs(),
                    serving.maxItemsPerJob(), serving.itemConcurrency(),
                    Duration.ofMillis(serving.itemTimeoutMs()));
            var handler = new BatchHandler(serving, executor, store);
            framework.addAtmosphereHandler(BatchServing.BATCHES_PATH, handler,
                    new ArrayList<>());
            logger.info("Batch endpoint enabled at {} (store: {}, max open jobs: {}, max items "
                            + "per job: {}, item concurrency: {}, item timeout: {}ms, retained "
                            + "terminal jobs: {})",
                    BatchServing.BATCHES_PATH, store.name(), serving.maxOpenJobs(),
                    serving.maxItemsPerJob(), serving.itemConcurrency(),
                    serving.itemTimeoutMs(), serving.retainedTerminalJobs());
            if (serving.apiKey() == null) {
                logger.warn("Batch endpoint has no atmosphere.ai.batch.api-key configured — the "
                        + "endpoint performs no authentication of its own and relies on "
                        + "framework-level interceptors (e.g. AuthInterceptor). Do not expose it "
                        + "publicly without one of the two.");
            }
            return handler;
        } catch (RuntimeException e) {
            // The registrar created these on this failed path, so the
            // registrar releases them (Invariant #1).
            if (executor != null) {
                executor.close();
            }
            store.close();
            throw e;
        }
    }
}
