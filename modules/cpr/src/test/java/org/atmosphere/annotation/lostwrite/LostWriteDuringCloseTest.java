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
package org.atmosphere.annotation.lostwrite;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A WebSocket whose TCP connection is reset while the room streams to it is
 * lost twice at once: the container closes it (1006) and a broadcast write
 * fails on it. When the container's close has begun completing the
 * resource's lifecycle, the failed write's forced close must not cancel the
 * resource under it: the close then found the resource out of scope and the
 * member never reached {@code @Disconnect}.
 */
class LostWriteDuringCloseTest {

    static final List<String> DISCONNECTS = new CopyOnWriteArrayList<>();

    @ManagedService(path = "/lost-write")
    public static final class RoomService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) {
            var resource = event.getResource();
            DISCONNECTS.add(resource == null ? "null" : resource.uuid());
        }
    }

    /** The container's close stops here, having begun completing the lifecycle. */
    private final CountDownLatch completing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile Thread held;

    private AtmosphereFramework framework;
    private ExecutorService lostWrites;
    private volatile Thread lostWriteThread;

    @BeforeEach
    void setUp() throws Exception {
        DISCONNECTS.clear();
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(RoomService.class);
        framework.addInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false");
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res)
                    throws IOException, ServletException {
                return suspended(req, res);
            }

            @Override
            protected void invokeAtmosphereHandler(AtmosphereResourceImpl r) {
                if (Thread.currentThread() == held) {
                    completing.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                super.invokeAtmosphereHandler(r);
            }
        });
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
        lostWrites = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "lost-write");
            lostWriteThread = thread;
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        framework.destroy();
        lostWrites.shutdownNow();
    }

    @Test
    void aWriteFailingWhileTheContainerClosesTheSocketLeavesItsDisconnectToTheClose() throws Exception {
        var webSocket = new ResetWebSocket(framework);
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo("/lost-write").build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        var resource = (AtmosphereResourceImpl) webSocket.resource();
        var member = resource.uuid();
        var broadcaster = (DefaultBroadcaster) resource.getBroadcaster();
        broadcaster.getBroadcasterConfig().setAsyncWriteService(lostWrites, true);

        // The container sees the reset and closes the connection (1006).
        var close = new Thread(() -> processor.close(webSocket, 1006), "container-close");
        held = close;
        close.start();
        assertTrue(completing.await(5, TimeUnit.SECONDS), "the close never began completing the lifecycle");

        // Meanwhile a broadcast write fails on the reset connection.
        webSocket.reset = true;
        broadcaster.onException(new IOException("Connection reset"), resource);

        // The failed write's forced close runs on its own thread: let it either
        // finish (tearing the resource down) or wait for the close.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var thread = lostWriteThread;
            if (thread != null && (thread.getState() == Thread.State.TIMED_WAITING
                    || thread.getState() == Thread.State.WAITING)) {
                break;
            }
            Thread.sleep(1);
        }

        release.countDown();
        close.join(10_000);

        assertEquals(List.of(member), DISCONNECTS,
                "the member must reach @Disconnect exactly once, with its resource");
    }

    private static final class ResetWebSocket extends WebSocket {

        volatile boolean reset;

        ResetWebSocket(AtmosphereFramework framework) {
            super(framework.getAtmosphereConfig());
        }

        @Override
        public boolean isOpen() {
            return !reset;
        }

        @Override
        public WebSocket write(String s) throws IOException {
            if (reset) {
                throw new IOException("Connection reset");
            }
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) throws IOException {
            if (reset) {
                throw new IOException("Connection reset");
            }
            return this;
        }

        @Override
        public void close() {
        }
    }
}
