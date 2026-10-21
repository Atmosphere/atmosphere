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
package org.atmosphere.webtransport;

import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two threads writing to one connection at once (a room's live frame from the
 * broadcaster's write thread, and a frame the server writes to that member
 * itself, such as the run-id acknowledgement a reattach writes right after the
 * catch-up of a reloaded page) each reach the stream once, alone.
 *
 * <p>A write runs the interceptors (the heartbeat's, the message-size one)
 * through the response, whose writer it swaps for a buffer of its own until
 * they ran. Two writes interleaved there swapped each other's buffer: a frame
 * landed in the other write's buffer, or in none, and the connection was
 * written an empty line in its place: a reloaded classroom page showed
 * {@code x2−56=0} for {@code x2−5x+6=0}, one streamed token lost.</p>
 */
class WebTransportSessionConcurrentWriteTest {

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() {
        framework = new AtmosphereFramework();
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    /** A session recording what it writes to its stream. */
    private static final class RecordingSession extends WebTransportSession {
        final List<String> stream = Collections.synchronizedList(new ArrayList<>());

        RecordingSession(AtmosphereConfig config) {
            super(config);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebTransportSession write(String s) {
            stream.add(s);
            return this;
        }

        @Override
        public WebTransportSession write(byte[] b, int offset, int length) {
            stream.add(new String(b, offset, length));
            return this;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void concurrentWritesToOneResponseReachTheStreamOnceEach() throws Exception {
        var session = new RecordingSession(framework.getAtmosphereConfig());
        var response = AtmosphereResponseImpl.newInstance();
        response.asyncIOWriter(session);
        var resource = mock(AtmosphereResource.class);
        when(resource.getResponse()).thenReturn(response);
        session.resource(resource);

        var liveIn = new CountDownLatch(1);
        var liveGo = new CountDownLatch(1);
        var otherIn = new CountDownLatch(1);
        var otherGo = new CountDownLatch(1);
        var paused = ConcurrentHashMap.<Thread>newKeySet();
        // Holds each writer, once, while it runs the interceptors: the live
        // frame's writer first, then the other one while the first still waits.
        session.interceptor(new AsyncIOInterceptorAdapter() {
            @Override
            public byte[] transformPayload(AtmosphereResponse r, byte[] draft, byte[] data) {
                var name = Thread.currentThread().getName();
                if (name.startsWith("writer-") && paused.add(Thread.currentThread())) {
                    var live = name.equals("writer-live");
                    (live ? liveIn : otherIn).countDown();
                    try {
                        (live ? liveGo : otherGo).await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return draft;
            }
        });

        var failure = new AtomicReference<Throwable>();
        var liveWriter = Thread.ofPlatform().name("writer-live").start(() -> write(session, response, "live", failure));
        assertTrue(liveIn.await(5, TimeUnit.SECONDS), "the live frame's write reached the interceptors");
        var otherWriter = Thread.ofPlatform().name("writer-other").start(() -> write(session, response, "ack",
                failure));
        // Before the fix the second write swaps in its buffer while the first
        // one waits; serialized, it waits for the first one to end.
        otherIn.await(300, TimeUnit.MILLISECONDS);
        liveGo.countDown();
        liveWriter.join(5000);
        otherGo.countDown();
        otherWriter.join(5000);

        // A write afterwards reaches the stream too: no write left the response
        // writing into a buffer.
        session.write(response, "next");

        assertNull(failure.get());
        assertEquals(List.of("live", "ack", "next"), session.stream);
    }

    private static void write(WebTransportSession session, AtmosphereResponse response, String frame,
                              AtomicReference<Throwable> failure) {
        try {
            session.write(response, frame);
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
