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
package org.atmosphere.cpr;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A broadcast future must complete when a resource it targets is removed
 * while the delivery is in flight (Invariant #2): a caller waiting on
 * {@code broadcast(...).get()} otherwise blocks until its own timeout.
 */
class BroadcasterFutureResourceRemovalTest {

    private static final long WAIT_SECONDS = 5;

    private AtmosphereConfig config;
    private GateBroadcaster broadcaster;
    private BlockingHandler handler;

    /** Parks the delivery thread before it walks the subscribed resources. */
    public static class GateBroadcaster extends DefaultBroadcaster {

        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        protected void push(Deliver deliver) {
            if (armed.compareAndSet(true, false)) {
                parked.countDown();
                try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            super.push(deliver);
        }
    }

    /** Holds the first write inside the handler, so later writes queue behind it. */
    static final class BlockingHandler implements AtmosphereHandler {

        final AtomicBoolean blockNext = new AtomicBoolean();
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void onRequest(AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) {
            if (blockNext.compareAndSet(true, false)) {
                writing.countDown();
                try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void destroy() {
        }
    }

    @BeforeEach
    void setUp() {
        handler = new BlockingHandler();
    }

    private void start(String backpressurePolicy) {
        var framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        if (backpressurePolicy != null) {
            framework.addInitParameter(ApplicationConfig.BROADCASTER_BACKPRESSURE_POLICY, backpressurePolicy);
            framework.addInitParameter(ApplicationConfig.BROADCASTER_BACKPRESSURE_HIGH_WATER_MARK, "1");
        }
        config = framework.init().getAtmosphereConfig();
        broadcaster = config.getBroadcasterFactory().get(GateBroadcaster.class, "future-removal");
    }

    @AfterEach
    void tearDown() {
        handler.release.countDown();
        if (broadcaster != null) {
            broadcaster.release.countDown();
            broadcaster.destroy();
        }
        if (config != null) {
            config.framework().destroy();
            ExecutorsFactory.reset(config);
        }
    }

    private AtmosphereResource subscribe() {
        var resource = new AtmosphereResourceImpl();
        resource.initialize(config, broadcaster,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), handler);
        broadcaster.addAtmosphereResource(resource);
        return resource;
    }

    @Test
    void writeQueuedForARemovedResourceCompletesItsFuture() throws Exception {
        start(null);
        var resource = subscribe();

        var second = queueBehindAnInFlightWrite(resource);

        // The resource closes mid-delivery: its pending writes are dropped.
        broadcaster.removeAtmosphereResource(resource);
        handler.release.countDown();

        assertEquals("second", second.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void resourceRemovedBeforeTheDeliveryStartsCompletesTheFuture() throws Exception {
        start(null);
        subscribe();
        var leaving = subscribe();

        broadcaster.armed.set(true);
        // Counted for both subscribed resources.
        var future = broadcaster.broadcast("hello");
        assertTrue(broadcaster.parked.await(WAIT_SECONDS, TimeUnit.SECONDS), "the delivery must be parked");

        broadcaster.removeAtmosphereResource(leaving);
        broadcaster.release.countDown();

        assertEquals("hello", future.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void writeQueuedOnADestroyedBroadcasterCompletesItsFuture() throws Exception {
        start(null);
        var resource = subscribe();
        var queued = queueBehindAnInFlightWrite(resource);

        broadcaster.destroy();
        handler.release.countDown();

        assertEquals("second", queued.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void writeRejectedByDropNewestCompletesItsFuture() throws Exception {
        start("drop-newest");
        var resource = subscribe();
        var queued = queueBehindAnInFlightWrite(resource);

        // Over the high water mark: this one is rejected.
        var rejected = broadcaster.broadcast("third");
        assertEquals("third", rejected.get(WAIT_SECONDS, TimeUnit.SECONDS));

        handler.release.countDown();
        assertEquals("second", queued.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void writeEvictedByDropOldestCompletesItsFuture() throws Exception {
        start("drop-oldest");
        var resource = subscribe();
        var evicted = queueBehindAnInFlightWrite(resource);

        // Over the high water mark: the queued "second" is evicted for this one.
        var newest = broadcaster.broadcast("third");
        assertEquals("second", evicted.get(WAIT_SECONDS, TimeUnit.SECONDS));

        handler.release.countDown();
        assertEquals("third", newest.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    /** Parks a first write in the handler and returns the future of a second one queued behind it. */
    private Future<Object> queueBehindAnInFlightWrite(AtmosphereResource resource)
            throws InterruptedException {
        handler.blockNext.set(true);
        broadcaster.broadcast("first");
        assertTrue(handler.writing.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first write must be in flight");

        var second = broadcaster.broadcast("second");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (queuedFor(resource) == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, queuedFor(resource), "precondition: the second write is queued");
        return second;
    }

    private int queuedFor(AtmosphereResource resource) {
        var queue = broadcaster.writeQueues().get(resource.uuid());
        return queue == null ? 0 : queue.queue.size();
    }
}
