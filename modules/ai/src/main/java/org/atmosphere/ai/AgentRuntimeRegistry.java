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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.WeakHashMap;

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
 *
 * <p>The registry owns the instances it loads: an instance it does not keep
 * (unavailable, or not the one the knob selects) gets
 * {@link AgentRuntime#onEnvironmentClose()} right away, and every instance it
 * handed out, including the ones a {@link #reset()} dropped that are still in
 * use, gets it when the environment closes, once. A {@code close()} an
 * implementation declares itself is never called.</p>
 */
public final class AgentRuntimeRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AgentRuntimeResolver.class);

    private final AiEnvironment environment;
    private final boolean attach;
    // Benign double-init on first access: both threads produce an equivalent
    // ServiceLoader result, and the last write wins.
    private volatile List<AgentRuntime> cached;
    /**
     * The instances this registry handed out and has not closed yet, held
     * weakly: one a {@link #reset()} dropped and nobody uses any more is
     * collected with its resources instead of waiting for the close. Bounded
     * by the instances still referenced; guarded by {@code this}.
     */
    private final Set<AgentRuntime> handedOut = Collections.newSetFromMap(new WeakHashMap<>());

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
        var loaded = new ArrayList<AgentRuntime>();
        try {
            result = load(loaded);
        } catch (RuntimeException e) {
            loaded.forEach(AgentRuntimeRegistry::notifyEnvironmentClose);
            throw e;
        } finally {
            binding.close();
        }
        for (var runtime : loaded) {
            if (!containsInstance(result, runtime)) {
                notifyEnvironmentClose(runtime);
            }
        }
        synchronized (this) {
            // The environment closed while this resolution loaded: clear()
            // ran before these instances were handed out, so notify them here.
            if (environment.isClosed()) {
                result.forEach(AgentRuntimeRegistry::notifyEnvironmentClose);
                throw new IllegalStateException("AI environment " + environment.name()
                        + " is closed: its application stopped");
            }
            handedOut.addAll(result);
            cached = result;
        }
        return result;
    }

    /**
     * @param loaded receives every instance created, kept or not, so the
     *               caller notifies the ones it does not hand out
     */
    private List<AgentRuntime> load(List<AgentRuntime> loaded) {
        var all = new ArrayList<AgentRuntime>();
        var unavailable = new ArrayList<String>();
        try {
            for (var runtime : ServiceLoader.load(AgentRuntime.class)) {
                loaded.add(runtime);
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
            loaded.add(builtIn);
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

    /**
     * Drops the instances when the environment closes and calls
     * {@link AgentRuntime#onEnvironmentClose()} on every one this registry
     * handed out, once. Runs after the environment is marked closed, so a
     * resolution racing with it either handed its instances out first (and
     * they are notified here) or notifies them itself.
     */
    void clear() {
        List<AgentRuntime> toClose;
        synchronized (this) {
            cached = null;
            toClose = List.copyOf(handedOut);
            handedOut.clear();
        }
        // A failed hook is logged; an Error still lets the others run.
        Error failure = null;
        for (var runtime : toClose) {
            try {
                notifyEnvironmentClose(runtime);
            } catch (Error e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static boolean containsInstance(List<AgentRuntime> runtimes, AgentRuntime runtime) {
        for (var candidate : runtimes) {
            if (candidate == runtime) {
                return true;
            }
        }
        return false;
    }

    private static void notifyEnvironmentClose(AgentRuntime runtime) {
        try {
            runtime.onEnvironmentClose();
        } catch (Exception | LinkageError e) {
            // Exception: an implementation in a language without checked
            // exceptions (Kotlin) may throw a checked one from the hook;
            // LinkageError: one compiled against another version of a class
            // the hook needs.
            logger.warn("AgentRuntime {} failed to release its resources on environment close: {}",
                    runtime.name(), e.getMessage(), e);
        }
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
