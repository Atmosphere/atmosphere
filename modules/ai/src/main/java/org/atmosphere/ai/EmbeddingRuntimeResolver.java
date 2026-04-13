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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.BuiltInEmbeddingRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Resolves {@link EmbeddingRuntime} implementations from the classpath via
 * {@link ServiceLoader}. Mirrors the resolver pattern used for
 * {@link AgentRuntime} so callers get the same priority + availability
 * semantics for embedding adapters.
 *
 * <p>Results are cached after first resolution — the classpath does not
 * change at runtime so the discovered runtimes are stable.</p>
 */
public final class EmbeddingRuntimeResolver {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingRuntimeResolver.class);

    private static volatile List<EmbeddingRuntime> cachedAll;

    private EmbeddingRuntimeResolver() {}

    /**
     * Returns the highest-priority available runtime, or
     * {@link Optional#empty()} when no runtime has a wired native
     * {@code EmbeddingModel} (Built-in is reported only when
     * {@link AiConfig} carries a baseUrl + apiKey; otherwise it is
     * excluded from the result).
     */
    public static Optional<EmbeddingRuntime> resolve() {
        var all = resolveAll();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
    }

    /**
     * Returns all available runtimes sorted by priority (highest first).
     * Unlike {@link AgentRuntimeResolver#resolveAll()}, this may return an
     * empty list when no runtime is ready — there is no always-available
     * fallback because {@link BuiltInEmbeddingRuntime} depends on
     * {@link AiConfig} being configured.
     */
    public static List<EmbeddingRuntime> resolveAll() {
        // Fast path: a non-empty result is already cached. Volatile read is
        // sufficient — the contents are an immutable List.copyOf and
        // publication is safe.
        var result = cachedAll;
        if (result != null && !result.isEmpty()) {
            return result;
        }
        // Slow path: scan under a lock so two early callers don't race into
        // duplicate ServiceLoader.load()+availability checks, which can be
        // expensive (each provider's isAvailable() might touch the classpath
        // or initialise native resources). The empty-result re-scan behavior
        // is preserved: if resolution fires before AiConfig.set() (startup
        // race), Built-in reports unavailable and the result is not cached,
        // so the next call rescans. DCL-safe: we re-check cachedAll inside
        // the lock before spending work.
        synchronized (EmbeddingRuntimeResolver.class) {
            result = cachedAll;
            if (result != null && !result.isEmpty()) {
                return result;
            }
            var all = new ArrayList<EmbeddingRuntime>();
            try {
                for (var runtime : ServiceLoader.load(EmbeddingRuntime.class)) {
                    try {
                        if (runtime.isAvailable()) {
                            all.add(runtime);
                            logger.debug("EmbeddingRuntime discovered: {} (priority {})",
                                    runtime.name(), runtime.priority());
                        }
                    } catch (Exception e) {
                        logger.warn("EmbeddingRuntime failed availability check: {}",
                                e.getMessage());
                    }
                }
            } catch (ServiceConfigurationError e) {
                logger.debug("ServiceLoader<EmbeddingRuntime> error: {}", e.getMessage());
            }
            all.sort(Comparator.comparingInt(EmbeddingRuntime::priority).reversed());
            result = List.copyOf(all);
            cachedAll = result;
            return result;
        }
    }

    /**
     * Clear the resolver's cached {@code ServiceLoader} result so the next
     * {@link #resolveAll()} call rescans the classpath. Public but test-only
     * — production code MUST NOT call this because the classpath does not
     * change at runtime and clearing the cache forces redundant
     * {@code ServiceLoader.load()} work.
     *
     * <p>The intended callers are test harnesses that install or uninstall
     * a runtime via a {@code ServiceLoader} stub between assertions. The
     * method is public rather than package-private so tests in other modules
     * (e.g. the cross-module TCK subclasses) can reach it without a
     * module-info export hack.</p>
     */
    public static void resetCache() {
        cachedAll = null;
    }
}
