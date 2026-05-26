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
package org.atmosphere.quarkus.grpc.runtime;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.grpc.AtmosphereGrpcServer;
import org.atmosphere.grpc.GrpcHandler;
import org.atmosphere.grpc.GrpcHandlerAdapter;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CDI-managed bean that owns the standalone {@link AtmosphereGrpcServer}
 * lifecycle, mirroring the {@code SmartLifecycle} used by the Spring Boot
 * starter.
 *
 * <p>The bean starts the Netty-backed gRPC server during {@code @Observes
 * StartupEvent} and stops it during {@code @Observes ShutdownEvent}. The
 * {@link Priority} on the startup observer is set to the lowest value
 * ({@link Interceptor.Priority#LIBRARY_AFTER} + 1) so that the
 * {@link AtmosphereFramework} owned by the core extension's
 * {@code QuarkusAtmosphereServlet} has already finished {@code init()}
 * before we wire it into the gRPC processor. Shutdown observes the highest
 * priority so the gRPC server stops <em>before</em> the Atmosphere servlet
 * is destroyed.
 *
 * <p>Ownership invariant (Correctness Invariant #1): this bean only creates
 * and owns the {@code io.grpc.Server} via {@code AtmosphereGrpcServer}; the
 * {@link AtmosphereFramework} is looked up via
 * {@link LazyAtmosphereConfigurator#getFramework()} but never stopped here —
 * the core Quarkus extension owns the framework lifecycle.</p>
 */
@ApplicationScoped
public class AtmosphereQuarkusGrpcLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereQuarkusGrpcLifecycle.class);

    /**
     * Maximum time we wait for {@code LazyAtmosphereConfigurator.getFramework()}
     * to surface a non-null framework during startup. The Atmosphere servlet
     * initialises during Undertow deployment which has already completed by
     * the time {@code StartupEvent} fires, so this should never trip in
     * practice. Bounded so we never block startup forever.
     */
    private static final int FRAMEWORK_WAIT_SECONDS = 30;

    private final AtomicReference<AtmosphereGrpcServer> serverRef = new AtomicReference<>();

    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_AFTER + 1) StartupEvent event,
                 AtmosphereQuarkusGrpcConfig config) {
        if (!config.enabled()) {
            logger.debug("Atmosphere gRPC extension present but quarkus.atmosphere.grpc.enabled=false");
            return;
        }

        AtmosphereFramework framework = waitForFramework();
        if (framework == null) {
            // Runtime Truth (Invariant #5): if the framework is not ready we
            // refuse to advertise the gRPC port. Log loudly and stop — the
            // application boots, the port just stays closed.
            logger.error("Atmosphere gRPC extension enabled but AtmosphereFramework "
                    + "was not available within {}s — gRPC port will NOT be opened",
                    FRAMEWORK_WAIT_SECONDS);
            return;
        }

        GrpcHandler handler = resolveHandler();

        try {
            AtmosphereGrpcServer server = AtmosphereGrpcServer.builder()
                    .framework(framework)
                    .port(config.port())
                    .handler(handler)
                    .enableReflection(config.enableReflection())
                    .build();
            server.start();
            serverRef.set(server);
            logger.info("Atmosphere gRPC server listening on port {} (reflection={})",
                    server.port(), config.enableReflection());
        } catch (IOException e) {
            // Terminal Path Completeness (Invariant #2): start() failed → no
            // server reference is stored, no leak. Rethrow so Quarkus startup
            // fails loudly instead of silently masking a bind error.
            throw new IllegalStateException(
                    "Failed to start Atmosphere gRPC server on port " + config.port(), e);
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        AtmosphereGrpcServer server = serverRef.getAndSet(null);
        if (server == null) {
            return;
        }
        try {
            server.stop();
            logger.info("Atmosphere gRPC server stopped");
        } catch (Exception e) {
            // Swallow into log so Quarkus shutdown isn't blocked, but record
            // at WARN level so the failure is investigable.
            logger.warn("Error stopping Atmosphere gRPC server", e);
        }
    }

    /**
     * Test-only accessor for the bound port — used by {@code @QuarkusTest}
     * cases that request an ephemeral port via {@code port=0} and need to
     * route a client to whatever port the OS handed back.
     *
     * @return the bound port, or {@code -1} if the server is not running.
     */
    public int port() {
        AtmosphereGrpcServer server = serverRef.get();
        return server == null ? -1 : server.port();
    }

    private AtmosphereFramework waitForFramework() {
        // Pool the static accessor instead of busy-waiting on the configurator's
        // latch directly — the configurator's latch is an internal symbol and
        // the public surface is getFramework(). 100ms granularity keeps us
        // responsive without hot-spinning.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FRAMEWORK_WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            AtmosphereFramework framework = LazyAtmosphereConfigurator.getFramework();
            if (framework != null) {
                return framework;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return LazyAtmosphereConfigurator.getFramework();
    }

    /**
     * Resolves the user-supplied {@link GrpcHandler} CDI bean if one exists,
     * otherwise returns the default {@link GrpcHandlerAdapter}. We do not
     * declare a constructor-injection point on the handler because most
     * apps do not need to customise it; making it a CDI lookup keeps the
     * handler optional without forcing a "no bean of type GrpcHandler"
     * deployment failure.
     */
    private GrpcHandler resolveHandler() {
        try {
            jakarta.enterprise.inject.Instance<GrpcHandler> instances =
                    jakarta.enterprise.inject.spi.CDI.current().select(GrpcHandler.class);
            if (!instances.isUnsatisfied() && !instances.isAmbiguous()) {
                return instances.get();
            }
        } catch (IllegalStateException ignored) {
            // CDI not ready — fall through to default. Should not happen at
            // @Observes StartupEvent time, but defensive.
        }
        return new GrpcHandlerAdapter();
    }
}
