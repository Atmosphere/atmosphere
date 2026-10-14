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
package org.atmosphere.quarkus.runtime.vertx;

import io.vertx.core.http.ServerWebSocket;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A connection whose worker thread is interrupted is stopped by the server,
 * and is closed with 1001 (going away). The processor reads a 1000/1001 close
 * as the client leaving, so the connection must mark the event cancelled
 * before it closes: the client did not leave (Invariant #5).
 */
class VertxWebSocketConnectionInterruptTest {

    private AtmosphereFramework framework;

    static final class NoopHandler implements AtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) {
        }

        @Override
        public void destroy() {
        }
    }

    @AfterEach
    void tearDown() {
        if (framework != null) {
            framework.destroy();
        }
    }

    @Test
    void interruptedWorkerClosesAsCancelled() throws Exception {
        framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        var config = framework.init().getAtmosphereConfig();

        var resource = new AtmosphereResourceImpl();
        resource.initialize(config, config.getBroadcasterFactory().lookup("/vertx-interrupt", true),
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                framework.getAsyncSupport(), new NoopHandler());

        var socket = (ServerWebSocket) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServerWebSocket.class},
                (proxy, method, args) -> method.getReturnType().isInstance(proxy) ? proxy : null);
        var webSocket = new VertxWebSocket(config, socket, 0);
        webSocket.resource(resource);

        var opened = new CountDownLatch(1);
        var closeCode = new AtomicReference<Integer>();
        var cancelledAtClose = new AtomicReference<Boolean>();
        var processor = (WebSocketProcessor) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketProcessor.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "open" -> opened.countDown();
                        case "close" -> {
                            var ws = (WebSocket) args[0];
                            var event = ((AtmosphereResourceImpl) ws.resource()).getAtmosphereResourceEvent();
                            cancelledAtClose.set(event.isCancelled());
                            closeCode.set((Integer) args[1]);
                        }
                        default -> {
                        }
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });

        var worker = new AtomicReference<Thread>();
        var connection = new VertxWebSocketConnection(socket, webSocket, processor,
                AtmosphereRequestImpl.newInstance());
        connection.start(task -> {
            var thread = new Thread(task, "vertx-interrupt-worker");
            worker.set(thread);
            return thread;
        });
        assertTrue(opened.await(5, TimeUnit.SECONDS), "the worker must open the WebSocket");

        // The server stops the connection: its worker is interrupted.
        worker.get().interrupt();
        worker.get().join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(1001, closeCode.get(), "an interrupted worker closes with 1001");
        assertNotNull(cancelledAtClose.get());
        assertTrue(cancelledAtClose.get(), "the server stopped the connection: the close is cancelled");
    }
}
