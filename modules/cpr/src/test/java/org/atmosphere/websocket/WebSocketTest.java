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
package org.atmosphere.websocket;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AsyncIOInterceptor;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 *
 */
public class WebSocketTest {
    private static final byte[] TEST_DATA = "Hello Atmosphere!".getBytes();
    private AtmosphereFramework framework;

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
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
    }

    @Test
    public void testTransformWithConcurrency() throws Exception {
        WebSocket ws = new WebSocket(framework.getAtmosphereConfig()) {

            @Override
            public boolean isOpen() {
                return false;
            }

            @Override
            public WebSocket write(String s) throws IOException {
                return null;
            }

            @Override
            public WebSocket write(byte[] b, int offset, int length) throws IOException {
                return null;
            }

            @Override
            public void close() {
            }
        };

        ws.interceptor(new DummyInterceptor(500));
        ws.interceptor(new DummyInterceptor(10));
        
        AtmosphereResponse response1 = AtmosphereResponseImpl.newInstance();
        AtmosphereResponse response2 = AtmosphereResponseImpl.newInstance();
        
        Worker worker1 = new Worker(ws, response1);
        Worker worker2 = new Worker(ws, response2);
        Thread t1 = new Thread(worker1);
        Thread t2 = new Thread(worker2);
        t1.start();
        t2.start();
        
        t1.join(2000);
        t2.join(2000);

        assertFalse(worker1.isCorrupted(), "corrupted");
        assertFalse(worker2.isCorrupted(), "corrupted");
    }

    /**
     * Two threads writing to one connection's response at once each reach the
     * socket once, alone: a write swaps the response's writer for a buffer of
     * its own while the interceptors run, and two writes interleaved there
     * swapped each other's buffer, one frame then lost and an empty one sent.
     */
    @Test
    public void concurrentWritesToOneResponseReachTheSocketOnceEach() throws Exception {
        List<String> socket = Collections.synchronizedList(new ArrayList<>());
        WebSocket ws = new WebSocket(framework.getAtmosphereConfig()) {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public WebSocket write(String s) {
                socket.add(s);
                return this;
            }

            @Override
            public WebSocket write(byte[] b, int offset, int length) {
                socket.add(new String(b, offset, length));
                return this;
            }

            @Override
            public void close() {
            }
        };
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance();
        response.asyncIOWriter(ws);
        var resource = mock(AtmosphereResource.class);
        when(resource.getResponse()).thenReturn(response);
        ws.resource(resource);

        var firstIn = new CountDownLatch(1);
        var firstGo = new CountDownLatch(1);
        var secondIn = new CountDownLatch(1);
        var secondGo = new CountDownLatch(1);
        var paused = ConcurrentHashMap.<Thread>newKeySet();
        // Holds each writer, once, while it runs the interceptors.
        ws.interceptor(new AsyncIOInterceptorAdapter() {
            @Override
            public byte[] transformPayload(AtmosphereResponse r, byte[] draft, byte[] data) {
                var name = Thread.currentThread().getName();
                if (name.startsWith("writer-") && paused.add(Thread.currentThread())) {
                    var first = name.equals("writer-first");
                    (first ? firstIn : secondIn).countDown();
                    try {
                        (first ? firstGo : secondGo).await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return draft;
            }
        });

        var failure = new AtomicReference<Throwable>();
        var firstWriter = Thread.ofPlatform().name("writer-first").start(() -> write(ws, response, "first", failure));
        assertTrue(firstIn.await(5, TimeUnit.SECONDS));
        var secondWriter = Thread.ofPlatform().name("writer-second")
                .start(() -> write(ws, response, "second", failure));
        // Unserialized, the second write swaps in its buffer while the first waits.
        secondIn.await(300, TimeUnit.MILLISECONDS);
        firstGo.countDown();
        firstWriter.join(5000);
        secondGo.countDown();
        secondWriter.join(5000);
        ws.write(response, "next");

        assertNull(failure.get());
        assertEquals(List.of("first", "second", "next"), socket);
    }

    /**
     * A frame written through the response's writer while another thread's
     * write runs the interceptors reaches the socket on its own, not into the
     * buffer that write collects the interceptors' output in.
     */
    @Test
    public void aFrameWrittenThroughTheResponseMeanwhileReachesTheSocketAlone() throws Exception {
        List<String> socket = Collections.synchronizedList(new ArrayList<>());
        WebSocket ws = new WebSocket(framework.getAtmosphereConfig()) {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public WebSocket write(String s) {
                socket.add(s);
                return this;
            }

            @Override
            public WebSocket write(byte[] b, int offset, int length) {
                socket.add(new String(b, offset, length));
                return this;
            }

            @Override
            public void close() {
            }
        };
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance();
        response.asyncIOWriter(ws);
        var resource = mock(AtmosphereResource.class);
        when(resource.getResponse()).thenReturn(response);
        ws.resource(resource);

        var liveIn = new CountDownLatch(1);
        var liveGo = new CountDownLatch(1);
        var paused = ConcurrentHashMap.<Thread>newKeySet();
        ws.interceptor(new AsyncIOInterceptorAdapter() {
            @Override
            public byte[] transformPayload(AtmosphereResponse r, byte[] draft, byte[] data) {
                if (Thread.currentThread().getName().equals("writer-live") && paused.add(Thread.currentThread())) {
                    liveIn.countDown();
                    try {
                        liveGo.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return draft;
            }
        });

        var failure = new AtomicReference<Throwable>();
        var liveWriter = Thread.ofPlatform().name("writer-live").start(() -> write(ws, response, "live", failure));
        assertTrue(liveIn.await(5, TimeUnit.SECONDS));
        var ackWriter = Thread.ofPlatform().name("writer-ack").start(() -> {
            try {
                var writer = response.getWriter();
                writer.write("ack");
                writer.flush();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        // Before the fix the frame lands in the live write's buffer at once.
        ackWriter.join(300);
        liveGo.countDown();
        liveWriter.join(5000);
        ackWriter.join(5000);
        ws.write(response, "next");

        assertNull(failure.get());
        assertEquals(List.of("live", "ack", "next"), socket);
    }

    private static void write(WebSocket ws, AtmosphereResponse response, String frame,
                              AtomicReference<Throwable> failure) {
        try {
            ws.write(response, frame);
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static class Worker implements Runnable {
        private boolean corrupted;
        private WebSocket ws;
        private AtmosphereResponse response;
        
        public Worker(WebSocket ws, AtmosphereResponse response) {
            this.ws = ws;
            this.response = response;
        }

        @Override
        public void run() {
            try {
                byte[] b = ws.transform(response, TEST_DATA, 0, TEST_DATA.length);
                corrupted |= !Arrays.equals(TEST_DATA, b);
                b = ws.transform(response, TEST_DATA, 0, TEST_DATA.length);
                corrupted |= !Arrays.equals(TEST_DATA, b);
            } catch (IOException e) {
                // ignore;
            }
        }
        
        public boolean isCorrupted() {
            return corrupted;
        }
    }

    private static class DummyInterceptor implements AsyncIOInterceptor {
        private long time;

        public DummyInterceptor(long time) {
            this.time = time;
        }

        @Override
        public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
        }

        @Override
        public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
            try {
                Thread.sleep(time);
            } catch (InterruptedException e) {
                // ignore
            }
            return responseDraft;
        }

        @Override
        public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
        }

        @Override
        public byte[] error(AtmosphereResponse response, int statusCode, String reasonPhrase) {
            return null;
        }

        @Override
        public void redirect(AtmosphereResponse response, String location) {
        }
    }
}
