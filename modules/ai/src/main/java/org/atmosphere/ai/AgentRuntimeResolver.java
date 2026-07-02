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

import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Resolves {@link AgentRuntime} implementations from the classpath via
 * {@link ServiceLoader}. Falls back to {@link BuiltInAgentRuntime} if no
 * higher-priority runtime is available.
 *
 * @see AgentRuntime
 */
public final class AgentRuntimeResolver {

    private static final Logger logger = LoggerFactory.getLogger(AgentRuntimeResolver.class);

    // Cached after first resolution. ServiceLoader results don't change at
    // runtime, so resolve once and return the immutable snapshot thereafter.
    // Volatile ensures visibility; benign double-init on first access is
    // acceptable (both threads produce the same ServiceLoader result).
    private static volatile List<AgentRuntime> cachedAll;

    // True once application code has explicitly bound a native client to a
    // runtime adapter (SpringAiAgentRuntime.setChatClient,
    // LangChain4jAgentRuntime.setModel, ...). The demo fallback keys its
    // availability off "no API key configured"; a bound client means a real
    // runtime can serve without any key, so demo must yield.
    private static volatile boolean explicitClientBinding;

    private AgentRuntimeResolver() {}

    /**
     * Records that application code explicitly bound a native client to a
     * runtime adapter, and drops the cached resolution so the next
     * {@link #resolveAll()} re-evaluates availability. Called by the runtime
     * adapters' static binder methods — never by auto-configuration paths,
     * which must offer rather than bind.
     */
    public static void markExplicitClientBinding() {
        explicitClientBinding = true;
        reset();
    }

    /**
     * Whether application code has explicitly bound a native client to any
     * runtime adapter in this JVM.
     *
     * @return {@code true} once {@link #markExplicitClientBinding()} ran
     */
    public static boolean hasExplicitClientBinding() {
        return explicitClientBinding;
    }

    /**
     * Test hook: clears the {@link #markExplicitClientBinding()} mark and the
     * cached resolution, restoring the pristine no-binding state.
     */
    public static void clearExplicitClientBinding() {
        explicitClientBinding = false;
        reset();
    }

    /**
     * Returns the highest-priority available runtime, or
     * {@link BuiltInAgentRuntime} as fallback.
     */
    public static AgentRuntime resolve() {
        return resolveAll().getFirst();
    }

    /**
     * Drop the cached runtime list so the next {@link #resolveAll()} call
     * rescans the ServiceLoader. Visible for tests that install a custom
     * runtime before the first resolution — without this, the cache freezes
     * at the first call and subsequent {@code configure(...)} changes stay
     * invisible for the rest of the JVM.
     */
    public static void reset() {
        cachedAll = null;
    }

    /**
     * Returns all available runtimes sorted by priority (highest first).
     * Falls back to a singleton list with {@link BuiltInAgentRuntime} if
     * no implementations are discovered.
     *
     * <p>Results are cached after first resolution — the classpath does not
     * change at runtime so the discovered runtimes are stable.</p>
     */
    public static List<AgentRuntime> resolveAll() {
        var result = cachedAll;
        if (result != null) {
            return result;
        }

        var all = new ArrayList<AgentRuntime>();

        try {
            for (var runtime : ServiceLoader.load(AgentRuntime.class)) {
                try {
                    if (runtime.isAvailable()) {
                        all.add(runtime);
                        logger.debug("AgentRuntime discovered: {} (priority {})",
                                runtime.name(), runtime.priority());
                    }
                } catch (Exception e) {
                    logger.warn("AgentRuntime failed availability check: {}",
                            e.getMessage());
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.debug("ServiceLoader<AgentRuntime> error: {}", e.getMessage());
        }

        all.sort(Comparator.comparingInt(AgentRuntime::priority).reversed());

        if (all.isEmpty()) {
            all.add(new BuiltInAgentRuntime());
            logger.debug("No AgentRuntime on classpath, using built-in");
        }

        logger.info("AgentRuntime resolution: {} (explicitClientBinding={})",
                all.stream().map(AgentRuntime::name).toList(), explicitClientBinding);

        result = List.copyOf(all);
        cachedAll = result;
        return result;
    }
}
