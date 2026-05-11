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
package org.atmosphere.spring.boot.webtransport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency / multi-session correctness for {@link ReactorNettyWebTransportSession}.
 *
 * <p>Goes beyond the single-session contract pinned by
 * {@link ReactorNettyWebTransportSessionTest} to verify the per-session state
 * (closed flag, lastWrite timestamp, channel reference) does not leak across
 * concurrent sessions sharing the same JVM. Each session gets its own
 * {@link EmbeddedChannel} and concurrent virtual threads exercise the
 * write / close / isOpen surface in parallel — closes a class of regression
 * where session state would accidentally become static or shared.</p>
 *
 * <p>This is server-side concurrency-correctness testing — a real HTTP/3
 * multi-stream stress (concurrent QUIC streams + datagram fan-out) needs a
 * real WebTransport client and is exercised by the Playwright E2E lane.</p>
 */
class ReactorNettyWebTransportSessionConcurrencyTest {

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() {
        framework = new AtmosphereFramework(true, false);
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    @Test
    void hundredSessionsWriteConcurrentlyWithoutCrossTalk() throws Exception {
        // Each session writes its own unique payload. After all writes
        // complete, every channel must hold ONLY its own payload — no
        // cross-contamination. Catches a class of bug where session state
        // (channel, closed flag) becomes accidentally shared via static.
        final int sessionCount = 100;
        var sessions = new ArrayList<ReactorNettyWebTransportSession>(sessionCount);
        var channels = new ArrayList<EmbeddedChannel>(sessionCount);
        try {
            for (int i = 0; i < sessionCount; i++) {
                var ch = new EmbeddedChannel();
                channels.add(ch);
                sessions.add(new ReactorNettyWebTransportSession(framework.getAtmosphereConfig(), ch));
            }

            var startGate = new CountDownLatch(1);
            var doneGate = new CountDownLatch(sessionCount);
            var failures = new AtomicInteger();

            for (int i = 0; i < sessionCount; i++) {
                final int idx = i;
                Thread.startVirtualThread(() -> {
                    try {
                        startGate.await();
                        sessions.get(idx).write("session-" + idx);
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(doneGate.await(15, TimeUnit.SECONDS),
                    "all 100 concurrent writes must complete inside 15s");
            assertEquals(0, failures.get(), "no concurrent write should throw");

            // Verify each channel got ONLY its own payload — no leakage.
            for (int i = 0; i < sessionCount; i++) {
                var frame = channels.get(i).<ByteBuf>readOutbound();
                assertNotNull(frame, "session " + i + " channel must have a frame");
                var bytes = new byte[frame.readableBytes()];
                frame.readBytes(bytes);
                assertEquals("session-" + i + "\n", new String(bytes, StandardCharsets.UTF_8),
                        "session " + i + ": payload must match its own id (no cross-contamination)");
                frame.release();
                assertNull(channels.get(i).<ByteBuf>readOutbound(),
                        "session " + i + ": MUST have exactly one outbound frame, not extras from peers");
            }
        } finally {
            for (var ch : channels) {
                try {
                    ch.finishAndReleaseAll();
                } catch (Exception ignore) {
                    // best-effort cleanup
                }
            }
        }
    }

    @Test
    void closeOnOneSessionDoesNotAffectOthers() throws IOException {
        // Three sessions, three channels. Closing #1 must NOT cause #0 or #2
        // to flip to !isOpen — proves the closed AtomicBoolean is per-instance,
        // not shared, and that channel.close() in one doesn't bleed.
        var ch0 = new EmbeddedChannel();
        var ch1 = new EmbeddedChannel();
        var ch2 = new EmbeddedChannel();
        try {
            var s0 = new ReactorNettyWebTransportSession(framework.getAtmosphereConfig(), ch0);
            var s1 = new ReactorNettyWebTransportSession(framework.getAtmosphereConfig(), ch1);
            var s2 = new ReactorNettyWebTransportSession(framework.getAtmosphereConfig(), ch2);

            s1.close();

            assertTrue(s0.isOpen());
            assertFalse(s1.isOpen());
            assertTrue(s2.isOpen());

            // Confirm the open sessions still write fine after a sibling closed.
            s0.write("still alive");
            s2.write("still alive");
            for (var ch : List.of(ch0, ch2)) {
                var frame = ch.<ByteBuf>readOutbound();
                assertNotNull(frame);
                frame.release();
            }
        } finally {
            try { ch0.finishAndReleaseAll(); } catch (Exception ignore) { /* */ }
            try { ch1.finishAndReleaseAll(); } catch (Exception ignore) { /* */ }
            try { ch2.finishAndReleaseAll(); } catch (Exception ignore) { /* */ }
        }
    }

    @Test
    void concurrentClosesAreIdempotentAcrossThreads() throws Exception {
        // Many threads racing to close the same session. The CAS in close()
        // must guarantee the underlying channel.close() fires exactly once
        // — even if 32 threads call close() concurrently, the channel
        // shouldn't see 32 close attempts (which Netty would flag as
        // "already closed" warnings).
        var ch = new EmbeddedChannel();
        try {
            var session = new ReactorNettyWebTransportSession(framework.getAtmosphereConfig(), ch);
            final int closers = 32;
            var startGate = new CountDownLatch(1);
            var doneGate = new CountDownLatch(closers);
            for (int i = 0; i < closers; i++) {
                Thread.startVirtualThread(() -> {
                    try {
                        startGate.await();
                        session.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS),
                    "concurrent closes must converge inside 5s");
            assertFalse(session.isOpen());
            // Subsequent close() from the test thread MUST also be a no-op.
            assertDoesNotThrow(() -> session.close());
        } finally {
            try { ch.finishAndReleaseAll(); } catch (Exception ignore) { /* */ }
        }
    }

    @Test
    void serialBinaryWritesPreserveAllPayloads() throws IOException {
        // Sequential 50-write stress to verify no write is silently dropped
        // or truncated. We run serially because EmbeddedChannel is not
        // thread-safe for writes (Netty's documented contract: outbound
        // writes go through the event loop). Real production traffic is
        // already serialized through QuicStreamChannel's event loop, so the
        // session-level guarantee under test here is "every write emits one
        // ByteBuf with exactly the requested bytes."
        var ch = new EmbeddedChannel();
        try {
            var session = new ReactorNettyWebTransportSession(framework.getAtmosphereConfig(), ch);
            final int writes = 50;
            int expectedTotalBytes = 0;
            for (int i = 1; i <= writes; i++) {
                var payload = new byte[i];
                for (int b = 0; b < i; b++) payload[b] = (byte) (i & 0xFF);
                session.write(payload, 0, i);
                expectedTotalBytes += i;
            }

            int totalBytes = 0;
            int framesObserved = 0;
            ByteBuf frame;
            while ((frame = ch.readOutbound()) != null) {
                totalBytes += frame.readableBytes();
                framesObserved++;
                frame.release();
            }
            assertEquals(expectedTotalBytes, totalBytes,
                    "sequential binary writes MUST preserve every input byte (no drops, no truncation)");
            assertEquals(writes, framesObserved,
                    "every write MUST produce its own outbound frame — no coalescing or batching");
        } finally {
            try { ch.finishAndReleaseAll(); } catch (Exception ignore) { /* */ }
        }
    }
}
