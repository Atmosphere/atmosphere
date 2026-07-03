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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.preset.DeepAgentPreset;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Resolves the framework's {@link LongTermMemory} store. Resolution order:
 * <ol>
 *   <li>Framework property {@link #STORE_PROPERTY} — the container bridge:
 *       Spring / Quarkus auto-config stashes an app-managed store here (the
 *       bridging container owns its lifecycle).</li>
 *   <li>{@link ServiceLoader}-discovered {@link LongTermMemoryProvider} —
 *       highest {@code priority()} among providers reporting
 *       {@code isAvailable()}.</li>
 *   <li>Zero-dep {@link InMemoryLongTermMemory} fallback (facts are
 *       JVM-lifetime only).</li>
 * </ol>
 *
 * <p>Callers that persist the result should report the resolved store class
 * as runtime state — the fallback silently losing facts on restart is exactly
 * the kind of difference an operator must be able to see.</p>
 */
public final class LongTermMemories {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemories.class);

    /** Property-bag key under which a container-managed store is bridged. */
    public static final String STORE_PROPERTY = "org.atmosphere.ai.memory.store";

    private LongTermMemories() {
    }

    /**
     * Resolve the store for the given framework. Never returns {@code null} —
     * the in-memory fallback closes the chain.
     *
     * @param framework the framework whose property bag may bridge a store
     * @return the resolved long-term memory store
     */
    public static LongTermMemory resolve(AtmosphereFramework framework) {
        if (framework != null) {
            var cfg = framework.getAtmosphereConfig();
            if (cfg != null
                    && cfg.properties().get(STORE_PROPERTY) instanceof LongTermMemory bridged) {
                logger.debug("LongTermMemory resolved from framework property: {}",
                        bridged.getClass().getName());
                return bridged;
            }
        }
        try {
            LongTermMemoryProvider best = null;
            for (var provider : ServiceLoader.load(LongTermMemoryProvider.class)) {
                try {
                    if (provider.isAvailable()
                            && (best == null || provider.priority() > best.priority())) {
                        best = provider;
                    }
                } catch (RuntimeException e) {
                    logger.warn("LongTermMemoryProvider {} failed availability check: {}",
                            provider.getClass().getName(), e.getMessage());
                }
            }
            if (best != null) {
                var store = best.get();
                if (store != null) {
                    logger.info("LongTermMemory resolved via provider {}: {}",
                            best.getClass().getName(), store.getClass().getName());
                    return store;
                }
                logger.warn("LongTermMemoryProvider {} returned null despite isAvailable() — "
                        + "falling back", best.getClass().getName());
            }
        } catch (ServiceConfigurationError e) {
            logger.warn("LongTermMemoryProvider ServiceLoader lookup failed: {}", e.getMessage());
        }
        return new InMemoryLongTermMemory();
    }

    /**
     * Deep-agent preset attach point, shared by the {@code @AiEndpoint},
     * {@code @Agent} and {@code @Coordinator} processors (Correctness
     * Invariant #7, Mode Parity). When the preset applies to {@code path} and
     * no {@link LongTermMemoryInterceptor} is already present, appends a
     * framework-built interceptor AFTER the existing ones so user preProcess
     * (FIFO) and postProcess (LIFO) ordering is preserved; a user-declared
     * interceptor is authoritative and suppresses the preset's. Updates the
     * preset runtime-state with the resolved store class either way the
     * attach happens.
     *
     * @param interceptors  the endpoint's interceptors so far (never mutated)
     * @param preset        the installed preset for this framework
     * @param presetApplies whether the preset applies to this endpoint — the
     *                      caller's effective decision (the global switch via
     *                      {@code preset.enabledFor(path)} OR a forced preset,
     *                      e.g. {@code @Agent(deepAgent=true)}); passed in rather
     *                      than re-derived so a forced preset attaches memory too
     * @param path          the endpoint path (for the runtime-state log)
     * @param framework     the framework whose property bag may bridge a store
     * @return the original list, or a copy with the memory interceptor appended
     */
    public static List<AiInterceptor> withPresetLongTermMemory(List<AiInterceptor> interceptors,
                                                               DeepAgentPreset preset,
                                                               boolean presetApplies,
                                                               String path,
                                                               AtmosphereFramework framework) {
        if (!presetApplies
                || interceptors.stream().anyMatch(i -> i instanceof LongTermMemoryInterceptor)) {
            return interceptors;
        }
        var store = resolve(framework);
        var extended = new ArrayList<>(interceptors);
        extended.add(new LongTermMemoryInterceptor(store,
                MemoryExtractionStrategy.onSessionClose(),
                AgentRuntimeResolver.resolve(), 20));
        preset.updateRuntimeState(DeepAgentPreset.PRIMITIVE_LONG_TERM_MEMORY,
                "ACTIVE(" + store.getClass().getName() + ")");
        logger.info("Deep-agent preset attached long-term memory to {} (store: {})",
                path, store.getClass().getName());
        return List.copyOf(extended);
    }
}
