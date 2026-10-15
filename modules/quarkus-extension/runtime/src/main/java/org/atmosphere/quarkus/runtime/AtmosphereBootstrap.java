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
package org.atmosphere.quarkus.runtime;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletException;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework bootstrap steps used by {@link QuarkusAtmosphereServlet} and its
 * {@link AtmosphereServletInstanceFactory}, kept free of servlet types so any
 * container that boots an {@link AtmosphereFramework} can share them.
 */
public final class AtmosphereBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereBootstrap.class);

    private AtmosphereBootstrap() {
    }

    /**
     * Loads the build-time Jandex scan result (annotation name → annotated class
     * names) into the class map {@code DefaultAnnotationProcessor} reads from the
     * servlet context.
     *
     * @param annotationClassNames the build-time scan result
     * @return the resolved annotation map; unloadable entries are skipped
     */
    @SuppressWarnings("unchecked")
    public static Map<Class<? extends Annotation>, Set<Class<?>>> resolveAnnotationMap(
            Map<String, List<String>> annotationClassNames) {
        Map<Class<? extends Annotation>, Set<Class<?>>> resolved = new HashMap<>();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();

        for (Map.Entry<String, List<String>> entry : annotationClassNames.entrySet()) {
            try {
                Class<? extends Annotation> annotationClass =
                        (Class<? extends Annotation>) tccl.loadClass(entry.getKey());
                Set<Class<?>> classes = new HashSet<>();
                for (String className : entry.getValue()) {
                    try {
                        classes.add(tccl.loadClass(className));
                    } catch (ClassNotFoundException e) {
                        logger.warn("Could not load annotated class: {}", className, e);
                    }
                }
                if (!classes.isEmpty()) {
                    resolved.put(annotationClass, classes);
                }
            } catch (ClassNotFoundException e) {
                logger.warn("Could not load annotation class: {}", entry.getKey(), e);
            }
        }

        return resolved;
    }

    /**
     * Bridges the effective cost-ceiling guardrail into the framework
     * property bag before annotation processing runs, so it joins every
     * {@code @AiEndpoint} inspection chain — the Quarkus equivalent of the
     * Spring registrar's bean-list bridge. Guarded on {@code atmosphere-ai}
     * being present ({@code atmosphere-ai} is an optional dependency) before
     * any type from it is linked, and on the Arc container being up.
     *
     * @param framework the framework, created but not yet initialized
     */
    public static void bridgeAiCostGuardrail(AtmosphereFramework framework) {
        try {
            Class.forName("org.atmosphere.ai.cost.CostAccountantHolder", false,
                    AtmosphereBootstrap.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return; // atmosphere-ai absent — nothing to bridge
        }
        var container = io.quarkus.arc.Arc.container();
        if (container == null) {
            return;
        }
        doBridgeAiCostGuardrail(container, framework);
    }

    /**
     * Creates the application's {@code AiEnvironment} from the
     * {@code atmosphere.ai.*} knobs of the Quarkus configuration and attaches it
     * to the framework before the framework initializes, so the runtime
     * resolved while the {@code @AiEndpoint} and {@code @Agent} classes
     * register is the one {@code atmosphere.ai.runtime} names. A no-op when {@code atmosphere-ai} (an optional dependency) is
     * absent; no type from it is linked before that check.
     *
     * @param framework the framework, created but not yet initialized
     */
    public static void bridgeAiKnobs(AtmosphereFramework framework) {
        if (hasAiConfig()) {
            AiKnobBridge.install(framework);
        }
    }

    /**
     * Runs {@code init}, which bridges the AI configuration
     * ({@link #bridgeAiKnobs}, {@link #bridgeAiCostGuardrail}) and initializes
     * the framework, then checks the AI runtime selection
     * ({@link #verifyAiRuntimeSelection(AtmosphereFramework)}). When any of them
     * fails, the framework is destroyed before the failure is rethrown
     * ({@link #destroyAfterFailedStart}): its shutdown hooks run (closing the
     * AI environment among them) and its threads stop, since no shutdown task
     * was registered for a container that did not start, and in dev mode the
     * JVM outlives the failed start (Correctness Invariant #2).
     *
     * @param framework the framework {@code init} initializes
     * @param init      bridges the AI configuration and initializes the framework
     * @throws ServletException when the initialization fails
     */
    public static void initFramework(AtmosphereFramework framework, FrameworkInit init)
            throws ServletException {
        try {
            init.run();
            verifyAiRuntimeSelection(framework);
        } catch (ServletException | RuntimeException | Error e) {
            destroyAfterFailedStart(framework, e);
            throw e;
        }
    }

    /**
     * Destroys a framework whose start failed with {@code failure}. A failure
     * of the destroy itself, an {@link Error} from a shutdown hook included, is
     * added to {@code failure} as suppressed, so {@code failure} stays the one
     * reported.
     *
     * @param framework the framework to destroy
     * @param failure   the failure of the start, rethrown by the caller
     */
    static void destroyAfterFailedStart(AtmosphereFramework framework, Throwable failure) {
        try {
            framework.destroy();
        } catch (RuntimeException | Error destroyFailure) {
            failure.addSuppressed(destroyFailure);
        }
    }

    /** Initializes an {@link AtmosphereFramework}: {@code framework.init(config)} or the servlet's {@code init}. */
    @FunctionalInterface
    public interface FrameworkInit {
        void run() throws ServletException;
    }

    /**
     * Fails startup when {@code atmosphere.ai.runtime} names no available
     * {@code AgentRuntime}. Called once the framework initialized. A no-op when
     * {@code atmosphere-ai} is absent or the property is unset.
     *
     * @throws IllegalStateException when the name matches no available runtime
     */
    public static void verifyAiRuntimeSelection() {
        if (hasAiConfig()) {
            AiKnobBridge.verifyRuntimeSelection();
        }
    }

    /**
     * As {@link #verifyAiRuntimeSelection()}, for the application of
     * {@code framework}: the knob and runtimes of its AI environment.
     *
     * @param framework the initialized framework
     * @throws IllegalStateException when the name matches no available runtime
     */
    public static void verifyAiRuntimeSelection(AtmosphereFramework framework) {
        if (hasAiConfig()) {
            AiKnobBridge.verifyRuntimeSelection(framework);
        }
    }

    private static boolean hasAiConfig() {
        try {
            Class.forName("org.atmosphere.ai.AiConfig", false,
                    AtmosphereBootstrap.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false; // atmosphere-ai absent — no knobs to bridge
        }
    }

    /**
     * Kept as a separate method so {@code AtmosphereCostAccountantProducer}
     * (whose API surface references optional {@code atmosphere-ai} types) is
     * only linked after the classpath check in
     * {@link #bridgeAiCostGuardrail(AtmosphereFramework)} passed.
     */
    private static void doBridgeAiCostGuardrail(io.quarkus.arc.ArcContainer container,
                                                AtmosphereFramework framework) {
        var handle = container.instance(AtmosphereCostAccountantProducer.class);
        if (handle.isAvailable()) {
            handle.get().bridgeGuardrail(framework);
        }
    }
}
