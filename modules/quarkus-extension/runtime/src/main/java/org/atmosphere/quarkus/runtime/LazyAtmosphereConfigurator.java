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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import org.atmosphere.container.JSR356Endpoint;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configurator that lazily obtains the AtmosphereFramework reference.
 * The framework is set by {@link QuarkusAtmosphereServlet#init} after the servlet
 * is initialized, which happens after deployment completes.
 * <p>
 * Handles both call orderings (modifyHandshake-first and getEndpointInstance-first)
 * using ThreadLocal, matching the pattern in JSR356AsyncSupport.AtmosphereConfigurator.
 * In Quarkus's Vert.x WebSocket handler, both calls happen on the same thread.
 * <p>
 * Uses a {@link CountDownLatch} to block WebSocket upgrade requests until the
 * servlet has been initialized. This handles the case where a WebSocket connection
 * arrives before the servlet's {@code init()} has completed.
 * <p>
 * The framework reference is held in a resettable {@link FrameworkHolder} so that
 * Quarkus dev mode live reloads get a fresh latch and framework reference each cycle.
 * Without this, the {@code CountDownLatch} from the previous run would already be
 * counted down, and {@code frameworkRef} would hold a stale (destroyed) framework,
 * causing WebSocket connections after reload to use a dead framework.
 *
 * @see #reset()
 */
public class LazyAtmosphereConfigurator extends ServerEndpointConfig.Configurator {

    private static final Logger logger = LoggerFactory.getLogger(LazyAtmosphereConfigurator.class);
    private static final int FRAMEWORK_WAIT_SECONDS = 30;

    /**
     * Holds an {@link AtmosphereFramework} reference together with a
     * {@link CountDownLatch} that signals when the framework becomes available.
     * A new holder is created on each {@link #reset()} call, ensuring that
     * Quarkus dev mode live reloads start with a fresh, un-signalled latch.
     */
    static final class FrameworkHolder {
        final AtomicReference<AtmosphereFramework> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
    }

    /**
     * The current holder. Swapped atomically by {@link #reset()}.
     */
    static final AtomicReference<FrameworkHolder> holder =
            new AtomicReference<>(new FrameworkHolder());

    private final ThreadLocal<JSR356Endpoint> endPoint = new ThreadLocal<>();
    private final ThreadLocal<HandshakeRequest> hRequest = new ThreadLocal<>();

    /**
     * Called by {@link QuarkusAtmosphereServlet#init} after the framework is initialized.
     */
    static void setFramework(AtmosphereFramework framework) {
        FrameworkHolder h = holder.get();
        h.ref.set(framework);
        h.latch.countDown();
        logger.debug("AtmosphereFramework reference set in configurator");
    }

    /**
     * Resets the configurator for a new lifecycle, clearing the stale framework
     * reference and creating a fresh latch. This must be called during shutdown
     * (before Quarkus dev mode live reload re-initializes the servlet) so that
     * the next startup cycle blocks WebSocket upgrades until the new framework
     * is ready.
     */
    static void reset() {
        holder.set(new FrameworkHolder());
        logger.debug("LazyAtmosphereConfigurator reset for new lifecycle");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (JSR356Endpoint.class.isAssignableFrom(endpointClass)) {
            FrameworkHolder h = holder.get();
            AtmosphereFramework framework = h.ref.get();
            if (framework == null) {
                logger.debug("Framework not yet available, waiting up to {}s...", FRAMEWORK_WAIT_SECONDS);
                try {
                    if (!h.latch.await(FRAMEWORK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new InstantiationException(
                                "Timed out waiting for AtmosphereFramework initialization (" +
                                FRAMEWORK_WAIT_SECONDS + "s)");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InstantiationException("Interrupted waiting for AtmosphereFramework");
                }
                framework = h.ref.get();
                if (framework == null) {
                    throw new InstantiationException("AtmosphereFramework not available after latch release");
                }
            }
            JSR356Endpoint e = new JSR356Endpoint(framework,
                    WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework));
            if (hRequest.get() != null) {
                e.handshakeRequest(hRequest.get());
                hRequest.set(null);
            } else {
                endPoint.set(e);
            }
            return (T) e;
        }
        return super.getEndpointInstance(endpointClass);
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request,
                                HandshakeResponse response) {
        if (endPoint.get() == null) {
            hRequest.set(request);
        } else {
            endPoint.get().handshakeRequest(request);
            endPoint.set(null);
        }
    }
}
