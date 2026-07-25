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
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.DefaultAnnotationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus-specific {@link AtmosphereServlet} subclass that defers framework initialization
 * to support native image builds. Stores the pre-scanned annotation map and lazily signals
 * readiness to WebSocket endpoints.
 */
public class QuarkusAtmosphereServlet extends AtmosphereServlet {

    private static final Logger logger = LoggerFactory.getLogger(QuarkusAtmosphereServlet.class);

    private static volatile QuarkusAtmosphereServlet instance;

    private Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap;
    private transient volatile ServletConfig deferredConfig;

    public QuarkusAtmosphereServlet() {
        instance = this;
    }

    static QuarkusAtmosphereServlet getInstance() {
        return instance;
    }

    public void setAnnotationMap(Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap) {
        this.annotationMap = annotationMap;
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {
        if ("buildtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            // During native image build (STATIC_INIT phase), skip framework init to
            // avoid creating thread pools that would be captured in the image heap.
            // The actual init is deferred to RUNTIME_INIT via performDeferredInit().
            this.deferredConfig = sc;
            logger.info("QuarkusAtmosphereServlet: deferring init for native image build");
            return;
        }
        performInit(sc);
    }

    /**
     * Completes the deferred init at RUNTIME_INIT when running as a native image.
     * Called by the {@link AtmosphereRecorder} after the Undertow deployment is ready.
     */
    void performDeferredInit() throws ServletException {
        if (deferredConfig != null) {
            performInit(deferredConfig);
            deferredConfig = null;
        }
    }

    private void performInit(ServletConfig sc) throws ServletException {
        logger.info("QuarkusAtmosphereServlet.init() starting");

        if (annotationMap != null) {
            logger.debug("Setting Atmosphere annotation map on ServletContext with {} entries",
                    annotationMap.size());
            sc.getServletContext().setAttribute(
                    DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE, annotationMap);
        }
        // Create the framework WITHOUT initializing it so cross-cutting AI
        // wiring can seed framework properties before annotation processing
        // freezes each @AiEndpoint's guardrail chain (StartupEvent observers
        // fire after this init, i.e. too late). The initializer is idempotent:
        // super.init() below finds the framework already created and only
        // runs framework.init().
        configureFramework(sc, false);
        bridgeAiCostGuardrail();
        super.init(sc);

        // Make the framework available to the WebSocket endpoint configurator.
        // The JSR-356 endpoints were registered during deployment (before init),
        // so the configurator needs a way to obtain the framework lazily.
        // Uses setFramework() which also signals the CountDownLatch, unblocking
        // any WebSocket upgrade requests that arrived before init completed.
        LazyAtmosphereConfigurator.setFramework(framework());
        logger.info("QuarkusAtmosphereServlet.init() completed, framework available for WebSocket");
    }

    /**
     * Bridges the effective cost-ceiling guardrail into the framework
     * property bag before annotation processing runs, so it joins every
     * {@code @AiEndpoint} inspection chain — the Quarkus equivalent of the
     * Spring registrar's bean-list bridge. Guarded on {@code atmosphere-ai}
     * being present ({@code atmosphere-ai} is an optional dependency) before
     * any type from it is linked, and on the Arc container being up.
     */
    private void bridgeAiCostGuardrail() {
        try {
            Class.forName("org.atmosphere.ai.cost.CostAccountantHolder", false,
                    QuarkusAtmosphereServlet.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return; // atmosphere-ai absent — nothing to bridge
        }
        var container = io.quarkus.arc.Arc.container();
        if (container == null) {
            return;
        }
        doBridgeAiCostGuardrail(container);
    }

    /**
     * Kept as a separate method so {@code AtmosphereCostAccountantProducer}
     * (whose API surface references optional {@code atmosphere-ai} types) is
     * only linked after the classpath check in
     * {@link #bridgeAiCostGuardrail()} passed.
     *
     * @param container the running Arc container
     */
    private void doBridgeAiCostGuardrail(io.quarkus.arc.ArcContainer container) {
        var handle = container.instance(AtmosphereCostAccountantProducer.class);
        if (handle.isAvailable()) {
            handle.get().bridgeGuardrail(framework());
        }
    }
}
