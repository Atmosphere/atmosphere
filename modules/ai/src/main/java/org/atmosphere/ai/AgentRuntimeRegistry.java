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
 * The {@link AgentRuntime}s of one {@link AiEnvironment}: its own instances,
 * loaded with {@link ServiceLoader} and {@link AgentRuntime#attach attached} to
 * it before their availability is checked, so a runtime's native client,
 * settings and availability belong to that application only. Falls back to
 * {@link BuiltInAgentRuntime} when no runtime is available.
 *
 * <p>When the environment's {@link AiConfig#RUNTIME_PROPERTY} knob names a
 * runtime, the available runtime with that {@link AgentRuntime#name()} is the
 * only one resolved, whatever its priority; a name that matches no available
 * runtime fails with an {@link AgentRuntimeSelectionException}.</p>
 *
 * <p>The resolution is cached until {@link #reset()}: instances handed out
 * before keep working, the next resolution loads new ones.</p>
 */
public final class AgentRuntimeRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AgentRuntimeResolver.class);

    private final AiEnvironment environment;
    private final boolean attach;
    // Benign double-init on first access: both threads produce an equivalent
    // ServiceLoader result, and the last write wins.
    private volatile List<AgentRuntime> cached;

    AgentRuntimeRegistry(AiEnvironment environment, boolean attach) {
        this.environment = environment;
        this.attach = attach;
    }

    /**
     * The runtime {@link AiConfig#RUNTIME_PROPERTY} names, else the
     * highest-priority available one.
     *
     * @return the runtime, never {@code null}
     * @throws AgentRuntimeSelectionException when the knob names no available runtime
     */
    public AgentRuntime resolve() {
        return resolveAll().getFirst();
    }

    /**
     * Every available runtime, highest priority first, or only the one
     * {@link AiConfig#RUNTIME_PROPERTY} names.
     *
     * @return the runtimes, never empty
     * @throws AgentRuntimeSelectionException when the knob names no available runtime
     * @throws IllegalStateException          when the environment is closed
     */
    public List<AgentRuntime> resolveAll() {
        var result = cached;
        if (result != null) {
            return result;
        }
        if (environment.isClosed()) {
            throw new IllegalStateException("AI environment " + environment.name()
                    + " is closed: its application stopped");
        }
        // A runtime that reads the static API while its availability is
        // checked (a third-party one that ignores attach) reads this
        // environment's settings.
        var binding = AiEnvironment.bind(environment);
        try {
            result = load();
        } finally {
            binding.close();
        }
        cached = result;
        return result;
    }

    private List<AgentRuntime> load() {
        var all = new ArrayList<AgentRuntime>();
        var unavailable = new ArrayList<String>();
        try {
            for (var runtime : ServiceLoader.load(AgentRuntime.class)) {
                try {
                    if (attach) {
                        runtime.attach(environment);
                    }
                    if (runtime.isAvailable()) {
                        all.add(runtime);
                        logger.debug("AgentRuntime discovered: {} (priority {})",
                                runtime.name(), runtime.priority());
                    } else {
                        unavailable.add(runtime.name());
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
            var builtIn = new BuiltInAgentRuntime();
            if (attach) {
                builtIn.attach(environment);
            }
            all.add(builtIn);
            logger.debug("No AgentRuntime on classpath, using built-in");
        }

        var requested = environment.knob(AiConfig.RUNTIME_PROPERTY);
        if (requested.isPresent()) {
            var selected = select(all, requested.get(), unavailable);
            logger.info("AgentRuntime resolution: {} selected by {}={} (available: {})",
                    selected.name(), AiConfig.RUNTIME_PROPERTY, requested.get(),
                    all.stream().map(AgentRuntime::name).toList());
            return List.of(selected);
        }
        logger.info("AgentRuntime resolution: {} (explicitClientBinding={})",
                all.stream().map(AgentRuntime::name).toList(), environment.hasExplicitBinding());
        return List.copyOf(all);
    }

    /**
     * The runtimes resolved so far, without resolving.
     *
     * @return the cached resolution, empty when none is cached (or the
     *         environment closed)
     */
    public List<AgentRuntime> resolved() {
        var result = cached;
        return result != null ? result : List.of();
    }

    /**
     * Drop the cached resolution so the next {@link #resolveAll()} loads new
     * instances.
     */
    public void reset() {
        cached = null;
    }

    /** Drops the instances when the environment closes. */
    void clear() {
        cached = null;
    }

    /**
     * The available runtime whose name is {@code requested}, ignoring case and
     * surrounding blanks.
     *
     * @throws AgentRuntimeSelectionException when no available runtime has that name
     */
    private static AgentRuntime select(List<AgentRuntime> available, String requested,
                                       List<String> unavailable) {
        var name = requested.trim();
        for (var runtime : available) {
            if (name.equalsIgnoreCase(runtime.name())) {
                return runtime;
            }
        }
        var message = new StringBuilder()
                .append(AiConfig.RUNTIME_PROPERTY).append('=').append(name)
                .append(" names no available AgentRuntime. Available: ")
                .append(available.stream().map(AgentRuntime::name).toList());
        if (!unavailable.isEmpty()) {
            message.append("; on the classpath but not available: ").append(unavailable);
        }
        message.append(". Add the runtime's module to the application, or unset ")
                .append(AiConfig.RUNTIME_PROPERTY).append(" / ").append(AiConfig.RUNTIME_ENV)
                .append(" to run the highest-priority runtime.");
        throw new AgentRuntimeSelectionException(message.toString());
    }
}
