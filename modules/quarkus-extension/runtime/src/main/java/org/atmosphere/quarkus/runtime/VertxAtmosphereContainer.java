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

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.DefaultAnnotationProcessor;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.quarkus.runtime.vertx.VertxAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Lifecycle of the Atmosphere framework in the Vert.x container mode
 * ({@code quarkus.atmosphere.container=vertx}). It creates and owns the
 * {@link AtmosphereFramework} and the virtual-thread executor serving requests,
 * publishes the framework through {@link LazyAtmosphereConfigurator} so the
 * health, metrics, tracing and console consumers find it exactly as in servlet
 * mode, and releases both on shutdown (Correctness Invariant #1).
 */
public final class VertxAtmosphereContainer {

    private static final Logger logger = LoggerFactory.getLogger(VertxAtmosphereContainer.class);

    private final AtmosphereFramework framework;
    private final ExecutorService executor;
    private final VertxAtmosphereHandler handler;

    private VertxAtmosphereContainer(AtmosphereFramework framework, ExecutorService executor,
                                     VertxAtmosphereHandler handler) {
        this.framework = framework;
        this.executor = executor;
        this.handler = handler;
    }

    /**
     * Initialise the framework with the same init-params the servlet would get and
     * the pre-scanned annotation map, and build the route handler.
     */
    static VertxAtmosphereContainer start(Map<String, String> initParams,
                                          Map<String, List<String>> annotationClassNames,
                                          String mapping, long maxBodyBytes, long drainTimeoutMs)
            throws ServletException {
        var framework = new AtmosphereFramework(false, false);
        var config = new RouteServletConfig(initParams);
        config.getServletContext().setAttribute(DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE,
                AtmosphereBootstrap.resolveAnnotationMap(annotationClassNames));
        // Seed the cost guardrail before init freezes each @AiEndpoint's chain,
        // exactly as QuarkusAtmosphereServlet does.
        AtmosphereBootstrap.bridgeAiCostGuardrail(framework);
        framework.init(config);

        ThreadFactory threads = Thread.ofVirtual().name("atmosphere-vertx-", 0).factory();
        var executor = Executors.newThreadPerTaskExecutor(threads);
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        var handler = new VertxAtmosphereHandler(framework, processor, executor, threads,
                servletPrefix(mapping), maxBodyBytes, drainTimeoutMs);
        LazyAtmosphereConfigurator.setFramework(framework);
        logger.info("Atmosphere serving {} on the Vert.x router ({})", mapping,
                framework.getAsyncSupport().getContainerName());
        return new VertxAtmosphereContainer(framework, executor, handler);
    }

    Handler<RoutingContext> handler() {
        return handler;
    }

    /** Stop accepting work, release parked requests, then destroy the framework. */
    void stop() {
        executor.shutdownNow();
        framework.destroy();
    }

    /** {@code /atmosphere/*} → {@code /atmosphere}; {@code /*} or {@code /} → {@code ""}. */
    static String servletPrefix(String mapping) {
        var prefix = mapping;
        if (prefix.endsWith("/*")) {
            prefix = prefix.substring(0, prefix.length() - 2);
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /**
     * The {@link ServletConfig} the framework is initialised with: the init-params,
     * and one {@link ServletContext} that keeps attributes (the annotation map is
     * handed over as one). Every other context method answers like an empty
     * container: {@code null}, {@code false}, {@code 0} or an empty enumeration.
     */
    private static final class RouteServletConfig implements ServletConfig {
        private final Map<String, String> initParams;
        private final ServletContext context;

        RouteServletConfig(Map<String, String> initParams) {
            this.initParams = Map.copyOf(initParams);
            var attributes = new ConcurrentHashMap<String, Object>();
            this.context = (ServletContext) Proxy.newProxyInstance(
                    VertxAtmosphereContainer.class.getClassLoader(), new Class<?>[]{ServletContext.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAttribute" -> attributes.get((String) args[0]);
                        case "setAttribute" -> {
                            if (args[1] == null) {
                                attributes.remove((String) args[0]);
                            } else {
                                attributes.put((String) args[0], args[1]);
                            }
                            yield null;
                        }
                        case "removeAttribute" -> attributes.remove((String) args[0]);
                        case "getAttributeNames" -> Collections.enumeration(attributes.keySet());
                        case "getInitParameterNames" -> Collections.emptyEnumeration();
                        case "getContextPath" -> "";
                        case "getServerInfo" -> "Quarkus Vert.x";
                        case "getMajorVersion" -> 6;
                        case "getMinorVersion" -> 0;
                        case "getServletContextName" -> "atmosphere-vertx";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "RouteServletContext";
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == Enumeration.class) {
                return Collections.emptyEnumeration();
            }
            return null;
        }

        @Override
        public String getServletName() {
            return "AtmosphereServlet";
        }

        @Override
        public ServletContext getServletContext() {
            return context;
        }

        @Override
        public String getInitParameter(String name) {
            return initParams.get(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.enumeration(initParams.keySet());
        }
    }
}
