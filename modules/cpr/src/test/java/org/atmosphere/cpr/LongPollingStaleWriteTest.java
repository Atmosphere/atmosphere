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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Every poll of a long-polling client is a resource of its own under the
 * client's one uuid, and {@link AtmosphereResourceImpl} equality is by uuid. A
 * write addressed to a poll that already returned must leave the client's next
 * poll alone: neither its membership nor the writes queued for it.
 */
class LongPollingStaleWriteTest {

    private static final long WAIT_SECONDS = 5;
    private static final String TRACKING_ID = "5b7e1c3a-2f4d-4c8e-9a1b-6d0f2e3c4b5a";

    private AtmosphereConfig config;
    private DefaultBroadcaster broadcaster;
    private RecordingHandler handler;

    private record Write(AtmosphereResource resource, Object message) {
    }

    /** Records each write with the resource it reached; can hold the next one. */
    static final class RecordingHandler implements AtmosphereHandler {

        final List<Write> writes = new CopyOnWriteArrayList<>();
        final AtomicBoolean blockNext = new AtomicBoolean();
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void onRequest(AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) {
            writes.add(new Write(event.getResource(), event.getMessage()));
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
        handler = new RecordingHandler();
    }

    private void start(boolean backwardCompatible) {
        var framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        if (backwardCompatible) {
            // Skips the duplicate check, so two resources of one uuid can be members at once.
            framework.addInitParameter(ApplicationConfig.BACKWARD_COMPATIBLE_WEBSOCKET_BEHAVIOR, "true");
        }
        config = framework.init().getAtmosphereConfig();
        broadcaster = config.getBroadcasterFactory().get(DefaultBroadcaster.class, "long-polling-stale-write");
    }

    @AfterEach
    void tearDown() {
        handler.release.countDown();
        if (broadcaster != null) {
            broadcaster.destroy();
        }
        if (config != null) {
            config.framework().destroy();
            ExecutorsFactory.reset(config);
        }
    }

    /** A long-polling GET of the client, suspended on the broadcaster like a real poll. */
    private AtmosphereResourceImpl poll() {
        var request = new AtmosphereRequestImpl.Builder()
                .headers(Map.of(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, TRACKING_ID,
                        HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.LONG_POLLING_TRANSPORT))
                .build();
        var resource = new AtmosphereResourceImpl();
        resource.initialize(config, broadcaster, request, AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), handler);
        resource.suspend();
        return resource;
    }

    private boolean isMember(AtmosphereResource resource) {
        return broadcaster.getAtmosphereResources().stream().anyMatch(r -> r == resource);
    }

    private void awaitQueued(int size) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            var queue = broadcaster.writeQueues().get(TRACKING_ID);
            if (queue != null && queue.queue.size() >= size) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("write queue of " + TRACKING_ID + " never held " + size + " writes");
    }

    @Test
    void aWriteToAPollThatReturnedLeavesTheNextPollSubscribed() throws Exception {
        start(false);
        var returned = poll();
        returned.resume();
        var next = poll();
        assertEquals(returned, next, "both polls are the client's uuid");
        assertNotSame(returned, next);
        assertTrue(isMember(next), "the next poll is suspended on the broadcaster");

        // The next poll is being written to while a write for the poll that
        // returned, then another one for the next poll, queue behind it.
        handler.blockNext.set(true);
        var first = broadcaster.broadcast("first", next);
        assertTrue(handler.writing.await(WAIT_SECONDS, TimeUnit.SECONDS));
        var stale = broadcaster.broadcast("stale", returned);
        awaitQueued(1);
        var second = broadcaster.broadcast("second", next);
        awaitQueued(2);
        handler.release.countDown();

        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        stale.get(WAIT_SECONDS, TimeUnit.SECONDS);
        second.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertTrue(isMember(next), "the write for the poll that returned removed the next poll");
        assertEquals(List.of("first", "second"), handler.writes.stream().map(Write::message).toList(),
                "the next poll receives the write queued behind the stale one");
        // Equality is by uuid: check which of the two resources each write reached.
        handler.writes.forEach(w -> assertSame(next, w.resource(), "nothing reaches the poll that returned"));

        // Still subscribed: a broadcast to everyone reaches it.
        broadcaster.broadcast("everyone").get(WAIT_SECONDS, TimeUnit.SECONDS);
        var last = handler.writes.get(handler.writes.size() - 1);
        assertEquals("everyone", last.message());
        assertSame(next, last.resource());
    }

    @Test
    void removingAClosingPollKeepsTheWritesQueuedForTheNextPoll() throws Exception {
        start(true);
        var closing = poll();
        var next = poll();
        assertTrue(isMember(closing) && isMember(next), "both polls of the client are members");
        // The client closed this poll: still a member until its lifecycle completes.
        closing.getAtmosphereResourceEvent().setCancelled(true);

        handler.blockNext.set(true);
        var first = broadcaster.broadcast("first", next);
        assertTrue(handler.writing.await(WAIT_SECONDS, TimeUnit.SECONDS));
        var stale = broadcaster.broadcast("stale", closing);
        awaitQueued(1);
        var second = broadcaster.broadcast("second", next);
        awaitQueued(2);
        handler.release.countDown();

        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        stale.get(WAIT_SECONDS, TimeUnit.SECONDS);
        second.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertFalse(isMember(closing), "the write found the closing poll and removed it");
        assertTrue(isMember(next));
        assertEquals(List.of("first", "second"), handler.writes.stream().map(Write::message).toList(),
                "removing the closing poll dropped the write queued for the next poll");
        handler.writes.forEach(w -> assertSame(next, w.resource()));
    }
}
