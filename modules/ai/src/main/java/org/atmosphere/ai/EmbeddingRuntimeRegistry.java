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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The {@link EmbeddingRuntime}s of one {@link AiEnvironment}: its own
 * {@link ServiceLoader} instances, {@link EmbeddingRuntime#attach attached} to
 * it before their availability is checked. Unlike {@link AgentRuntimeRegistry}
 * the result may be empty, and an empty result is not cached: a runtime that
 * was not ready yet (no settings) is looked for again on the next call.
 *
 * <p>The registry owns the instances it loads: an instance it does not keep
 * (unavailable) is {@link EmbeddingRuntime#close() closed} right away, and
 * every instance it handed out, including the ones a {@link #reset()} dropped
 * that are still in use, is closed when the environment closes. Once the
 * environment is closed, nothing is loaded any more.</p>
 */
public final class EmbeddingRuntimeRegistry {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingRuntimeResolver.class);

    private final AiEnvironment environment;
    private final boolean attach;
    private volatile List<EmbeddingRuntime> cached;
    /**
     * The instances this registry handed out and has not closed yet, held
     * weakly: one a {@link #reset()} dropped and nobody uses any more is
     * collected with its resources instead of waiting for the close. Bounded
     * by the instances still referenced; guarded by {@code this}.
     */
    private final Set<EmbeddingRuntime> handedOut = Collections.newSetFromMap(new WeakHashMap<>());

    EmbeddingRuntimeRegistry(AiEnvironment environment, boolean attach) {
        this.environment = environment;
        this.attach = attach;
    }

    /**
     * The highest-priority available runtime.
     *
     * @return the runtime, or empty when none is available
     */
    public Optional<EmbeddingRuntime> resolve() {
        var all = resolveAll();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
    }

    /**
     * Every available runtime, highest priority first.
     *
     * @return the runtimes, possibly empty; empty once the environment is
     *         closed (an embedding consumer then takes its no-runtime path,
     *         where {@link AgentRuntimeRegistry#resolveAll()}, which never
     *         returns empty, throws)
     */
    public List<EmbeddingRuntime> resolveAll() {
        // Fast path: a non-empty result is already cached.
        var result = cached;
        if (result != null && !result.isEmpty()) {
            return result;
        }
        // Slow path under a lock so two early callers do not both run the
        // availability checks, which may touch the classpath or native resources.
        synchronized (this) {
            result = cached;
            if (result != null && !result.isEmpty()) {
                return result;
            }
            if (environment.isClosed()) {
                return List.of();
            }
            var binding = AiEnvironment.bind(environment);
            try {
                result = load();
            } finally {
                binding.close();
            }
            cached = result;
            handedOut.addAll(result);
            return result;
        }
    }

    private List<EmbeddingRuntime> load() {
        var all = new ArrayList<EmbeddingRuntime>();
        try {
            for (var runtime : ServiceLoader.load(EmbeddingRuntime.class)) {
                try {
                    if (attach) {
                        runtime.attach(environment);
                    }
                    if (runtime.isAvailable()) {
                        all.add(runtime);
                        logger.debug("EmbeddingRuntime discovered: {} (priority {})",
                                runtime.name(), runtime.priority());
                    } else {
                        close(runtime);
                    }
                } catch (Exception e) {
                    logger.warn("EmbeddingRuntime failed availability check: {}",
                            e.getMessage());
                    close(runtime);
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.debug("ServiceLoader<EmbeddingRuntime> error: {}", e.getMessage());
        }
        all.sort(Comparator.comparingInt(EmbeddingRuntime::priority).reversed());
        return List.copyOf(all);
    }

    /**
     * Drop the cached resolution so the next {@link #resolveAll()} rescans.
     * The instances handed out before keep working until the environment
     * closes.
     */
    public void reset() {
        cached = null;
    }

    /**
     * Drops the instances when the environment closes and closes every one
     * this registry handed out, once. Runs after the environment is marked
     * closed, so a resolution racing with it either finished first (and its
     * instances are closed here) or loads nothing.
     */
    void clear() {
        List<EmbeddingRuntime> toClose;
        synchronized (this) {
            cached = null;
            toClose = List.copyOf(handedOut);
            handedOut.clear();
        }
        toClose.forEach(EmbeddingRuntimeRegistry::close);
    }

    private static void close(EmbeddingRuntime runtime) {
        try {
            runtime.close();
        } catch (RuntimeException e) {
            logger.warn("EmbeddingRuntime {} failed to close: {}", runtime.name(), e.getMessage(), e);
        }
    }
}
