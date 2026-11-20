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
package org.atmosphere.ai.llm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link CancellableExchange}: an abort closes what is registered, a
 * target registered after the abort is closed on arrival (the
 * publish-after-cancel race), the send and the body never displace each
 * other, and each target is closed once. Also pins
 * {@link ActivityInputStream}, the body wrapper that reports reads.
 */
class CancellableExchangeTest {

    @Test
    void aTargetRegisteredAfterTheAbortIsClosedAtOnce() {
        var exchange = new CancellableExchange();
        exchange.abort();
        var closes = new AtomicInteger();

        exchange.registerBody(closes::incrementAndGet);

        assertEquals(1, closes.get(), "a body published after the cancel must not stay open");
        exchange.abort();
        assertEquals(1, closes.get(), "closed once");
    }

    @Test
    void aTargetRegisteredAfterAPlainSetIsClosedAtOnce() {
        var exchange = new CancellableExchange();
        exchange.set(true);
        var closes = new AtomicInteger();
        exchange.registerSend(closes::incrementAndGet);
        assertEquals(1, closes.get());
    }

    @Test
    void abortSetsTheFlagAndClosesBothTargetsOnce() {
        var exchange = new CancellableExchange();
        var future = new CompletableFuture<Void>();
        var body = new AtomicInteger();
        exchange.registerSend(() -> future.cancel(true));
        assertFalse(exchange.isAborted());
        exchange.registerBody(body::incrementAndGet);

        assertFalse(future.isCancelled(), "registering the body does not close the future's target");
        exchange.abort();
        exchange.abort();

        assertTrue(exchange.get());
        assertTrue(exchange.isAborted());
        assertEquals(1, body.get());
        assertTrue(future.isCancelled());
    }

    @Test
    void aBodyRegisteredBeforeTheFutureIsStillClosedByTheAbort() {
        // The body comes from a sendAsync callback on the HttpClient executor,
        // which can run before the calling thread registers the future.
        var exchange = new CancellableExchange();
        var body = new AtomicInteger();
        var future = new AtomicInteger();
        exchange.registerBody(body::incrementAndGet);
        exchange.registerSend(future::incrementAndGet);

        exchange.abort();

        assertEquals(1, body.get(), "registering the future must not drop the body");
        assertEquals(1, future.get());
    }

    @Test
    void aNewRequestReplacesTheSlotsPreviousTargetWithoutClosingIt() {
        var exchange = new CancellableExchange();
        var first = new AtomicInteger();
        var second = new AtomicInteger();
        exchange.registerBody(first::incrementAndGet);
        exchange.registerBody(second::incrementAndGet);
        exchange.abort();
        assertEquals(0, first.get(), "the previous request's exchange is over");
        assertEquals(1, second.get());
    }

    @Test
    void abortCancelsAnInFlightSend() {
        var exchange = new CancellableExchange();
        var future = new CompletableFuture<Void>();
        exchange.registerSend(() -> future.cancel(true));
        exchange.abort();
        assertTrue(future.isCancelled());
    }

    @Test
    void aFailingCloseIsNotThrown() {
        var exchange = new CancellableExchange();
        exchange.registerSend(() -> {
            throw new IOException("already reset");
        });
        exchange.abort();
        exchange.registerBody(() -> {
            throw new IllegalStateException("closed twice");
        });
        assertTrue(exchange.isAborted());
    }

    @Test
    void aRacingAbortAndRegisterCloseTheTargetExactlyOnce() throws Exception {
        for (int i = 0; i < 500; i++) {
            var exchange = new CancellableExchange();
            var closes = new AtomicInteger();
            var barrier = new CyclicBarrier(2);
            var aborter = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                exchange.abort();
            });
            barrier.await();
            exchange.registerBody(closes::incrementAndGet);
            assertTrue(aborter.join(Duration.ofSeconds(20)));
            assertEquals(1, closes.get(), "iteration " + i);
        }
    }

    @Test
    void anAbortRacingTwoRegisteringThreadsClosesEachTargetExactlyOnce() throws Exception {
        for (int i = 0; i < 500; i++) {
            var exchange = new CancellableExchange();
            var body = new AtomicInteger();
            var future = new AtomicInteger();
            var barrier = new CyclicBarrier(3);
            var aborter = Thread.ofPlatform().start(() -> {
                await(barrier);
                exchange.abort();
            });
            var callback = Thread.ofPlatform().start(() -> {
                await(barrier);
                exchange.registerBody(body::incrementAndGet);
            });
            barrier.await();
            exchange.registerSend(future::incrementAndGet);
            assertTrue(aborter.join(Duration.ofSeconds(20)));
            assertTrue(callback.join(Duration.ofSeconds(20)));
            assertEquals(1, body.get(), "iteration " + i + ": the body");
            assertEquals(1, future.get(), "iteration " + i + ": the future");
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void itIsTheAtomicBooleanTheClientsAlreadyTake() {
        AtomicBoolean cancelled = new CancellableExchange();
        assertInstanceOf(CancellableExchange.class, cancelled);
        assertFalse(cancelled.get());
    }

    // -- ActivityInputStream --

    @Test
    void everyReadThatIsNotEndOfStreamIsActivity() throws IOException {
        var activity = new AtomicInteger();
        var bytes = ": keep-alive\n\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);
        try (var body = new ActivityInputStream(new ByteArrayInputStream(bytes), activity::incrementAndGet)) {
            assertEquals(':', body.read());
            assertEquals(1, activity.get());
            var buffer = new byte[4];
            assertEquals(4, body.read(buffer));
            assertEquals(2, activity.get());
            assertEquals(0, body.read(buffer, 0, 0), "a read of zero bytes still counts");
            assertEquals(3, activity.get());
            var rest = body.readAllBytes();
            assertEquals(bytes.length - 5, rest.length);
            var afterBulk = activity.get();
            assertTrue(afterBulk > 3, "bulk reads report too");
            assertEquals(-1, body.read());
            assertEquals(-1, body.read(buffer));
            assertEquals(afterBulk, activity.get(), "end of stream is not activity");
        }
    }

    @Test
    void closeClosesTheWrappedBody() throws IOException {
        var closed = new AtomicBoolean();
        var inner = new InputStream() {
            @Override public int read() {
                return -1;
            }

            @Override public void close() {
                closed.set(true);
            }
        };
        var exchange = new CancellableExchange();
        var body = new ActivityInputStream(inner, () -> { });
        exchange.registerBody(body);
        exchange.abort();
        assertTrue(closed.get(), "the wrapper is the abort target: closing it closes the body");
    }
}
