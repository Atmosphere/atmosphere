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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A WebSocket that reconnects with its run id is replayed the run, and on
 * Quarkus its open callback runs on a Vert.x event loop. The JSR 356 WebSocket
 * writes one message at a time: a write waits for the previous send to
 * complete, and that completion runs on the same event loop. Written from the
 * open callback, the replay's second frame waited for a completion the blocked
 * loop could not run, the loop stayed blocked for the write timeout, and the
 * replay failed. The reattach of a WebSocket is not written on the thread that
 * opened it.
 */
class AiEndpointHandlerReattachOffOpenThreadTest {

    private static final long WRITE_TIMEOUT_MS = 2_000;

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private AiEndpointHandler handler;
    private ExecutorService eventLoop;

    @BeforeEach
    void setUp() throws Exception {
        RunRegistryHolder.reset();
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/grace");
        eventLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, "event-loop"));
        handler = new AiEndpointHandler(new AiEndpointHandlerReattachGraceTest.HeldEndpoint(),
                AiEndpointHandlerReattachGraceTest.HeldEndpoint.class.getDeclaredMethod("onPrompt",
                        String.class, StreamingSession.class),
                30_000L, "", "/ai/grace", new AiEndpointHandlerReattachGraceTest.HeldRuntime(),
                List.<AiInterceptor>of(), null,
                AnnotatedLifecycle.scan(AiEndpointHandlerReattachGraceTest.HeldEndpoint.class));
    }

    @AfterEach
    void tearDown() {
        eventLoop.shutdownNow();
        handler.destroy();
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void aWebSocketReconnectOpenedOnTheEventLoopIsReplayedEveryFrame() throws Exception {
        var run = RunRegistryHolder.get().register("/ai/grace", "anonymous", "original",
                new ExecutionHandle.Settable(() -> { }));
        var buffer = run.replayBuffer();
        buffer.capture("streaming-text", "one ");
        buffer.capture("streaming-text", "two ");
        buffer.capture("streaming-text", "three");

        var frames = new CopyOnWriteArrayList<String>();
        var socket = new EventLoopSocket(eventLoop, frames);
        var resource = reconnect(run.runId(), socket);

        var started = System.nanoTime();
        eventLoop.submit(() -> {
            handler.onRequest(resource);
            return null;
        }).get(WRITE_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);
        var openMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(openMillis < WRITE_TIMEOUT_MS / 2,
                "the open callback must not wait on the replay's writes: it took " + openMillis + " ms");

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (frames.size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(3, frames.size(), "every replayed frame reaches the client: " + frames);
        assertTrue(frames.get(0).contains("\"data\":\"one \"") && frames.get(2).contains("\"data\":\"three\""),
                frames.toString());
        buffer.capture("complete", "");
    }

    /**
     * A WebSocket client back under its tracking id with the run id, its writes
     * going to {@code socket}.
     */
    private AtmosphereResource reconnect(String runId, EventLoopSocket socket) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.uuid()).thenReturn("reloaded");
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new PrintWriter(socket));
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(room);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.isSuspended()).thenReturn(true);
        when(resource.addEventListener(any())).thenReturn(resource);
        when(resource.suspend(30_000L)).thenAnswer(inv -> inv.getMock());
        when(request.getMethod()).thenReturn("GET");
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenReturn(runId);
        return resource;
    }

    /**
     * A WebSocket that sends one message at a time, as {@code JSR356WebSocket}
     * does: a write waits for the previous send to complete, which the
     * container reports on its event loop.
     */
    private static final class EventLoopSocket extends Writer {

        private final Semaphore sending = new Semaphore(1);
        private final ExecutorService eventLoop;
        private final List<String> frames;

        EventLoopSocket(ExecutorService eventLoop, List<String> frames) {
            this.eventLoop = eventLoop;
            this.frames = frames;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            try {
                if (!sending.tryAcquire(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IOException("the previous send never completed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            frames.add(new String(buffer, offset, length));
            // The send completes, and frees the next one, on the event loop.
            eventLoop.execute(sending::release);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
