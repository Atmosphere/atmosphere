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

import java.util.List;
import java.util.Map;

import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerEndpointConfig;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.websockets.ServerWebSocketContainer;
import org.atmosphere.container.JSR356Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Recorder
public class AtmosphereRecorder {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereRecorder.class);
    private static final String PATH = "/{path";

    public InstanceFactory<QuarkusAtmosphereServlet> createInstanceFactory(
            Map<String, List<String>> annotationClassNames) {
        return new AtmosphereServletInstanceFactory(annotationClassNames);
    }

    /**
     * Registers a shutdown hook that resets the {@link LazyAtmosphereConfigurator}
     * so that Quarkus dev mode live reloads get a fresh latch and framework reference.
     * Without this, the {@code CountDownLatch} from the previous run would already be
     * counted down, and the framework reference would be stale.
     * <p>
     * The {@code ShutdownContext} is automatically injected by Quarkus into recorder
     * methods that declare it as a parameter.
     */
    public void registerShutdownHook(ShutdownContext shutdownContext) {
        shutdownContext.addShutdownTask(LazyAtmosphereConfigurator::reset);
    }

    /**
     * Completes the deferred servlet initialization for native image builds.
     * During STATIC_INIT the servlet skips framework init to avoid creating threads
     * that would be captured in the image heap. This method triggers the actual init
     * at RUNTIME_INIT, after the Undertow deployment is ready.
     */
    public void performDeferredInit() {
        QuarkusAtmosphereServlet servlet = QuarkusAtmosphereServlet.getInstance();
        if (servlet != null) {
            try {
                servlet.performDeferredInit();
            } catch (jakarta.servlet.ServletException e) {
                throw new RuntimeException("Failed to initialize Atmosphere framework at runtime", e);
            }
        }
    }

    public void registerWebSocketEndpoints(RuntimeValue<ServerWebSocketContainer> container) {
        ServerWebSocketContainer wsContainer = container.getValue();
        LazyAtmosphereConfigurator configurator = new LazyAtmosphereConfigurator();

        int pathLength = 5;
        String servletPath = PATH + "}";
        StringBuilder b = new StringBuilder(servletPath);
        for (int i = 0; i < pathLength; i++) {
            try {
                wsContainer.addEndpoint(ServerEndpointConfig.Builder
                        .create(JSR356Endpoint.class, b.toString())
                        .configurator(configurator)
                        .build());
            } catch (DeploymentException e) {
                logger.warn("Failed to register WebSocket endpoint path {}: {}", b, e.getMessage());
            }
            b.append(PATH).append(i).append("}");
        }

        logger.info("Registered {} JSR-356 WebSocket endpoint paths for Atmosphere", pathLength);
    }
}
