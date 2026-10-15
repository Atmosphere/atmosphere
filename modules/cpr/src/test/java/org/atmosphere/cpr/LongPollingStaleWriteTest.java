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

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * Records each write with the resource it reached; can hold the next one,
     * and fails the writes to one resource like a connection the client dropped.
     */
    static final class RecordingHandler implements AtmosphereHandler {

        final List<Write> writes = new CopyOnWriteArrayList<>();
        final AtomicBoolean blockNext = new AtomicBoolean();
        final AtomicReference<AtmosphereResource> failOn = new AtomicReference<>();
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void onRequest(AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            // Read before blocking: cancelling the resource clears its event.
            var resource = event.getResource();
            writes.add(new Write(resource, event.getMessage()));
            if (blockNext.compareAndSet(true, false)) {
                writing.countDown();
                try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (resource == failOn.get()) {
                throw new IOException("Broken pipe");
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
        start(backwardCompatible, false);
    }

    private void start(boolean backwardCompatible, boolean uuidCache) {
        var framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        if (uuidCache) {
            framework.addInitParameter(ApplicationConfig.BROADCASTER_CACHE, UUIDBroadcasterCache.class.getName());
        }
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

    /**
     * Lets the test interleave: the first lookup the armed thread makes, of one
     * resource or of the map of them, returns what it found, then waits until
     * the test lets it go on.
     */
    static final class PausingResourceFactory extends DefaultAtmosphereResourceFactory {

        final AtomicReference<Thread> pauseOn = new AtomicReference<>();
        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        @Override
        public Optional<AtmosphereResource> findResource(String uuid) {
            var found = super.findResource(uuid);
            pauseIfArmed();
            return found;
        }

        @Override
        public ConcurrentMap<String, AtmosphereResource> resources() {
            var resources = super.resources();
            pauseIfArmed();
            return resources;
        }

        private void pauseIfArmed() {
            if (pauseOn.compareAndSet(Thread.currentThread(), null)) {
                paused.countDown();
                try {
                    proceed.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** A long-polling GET of the client, suspended on the broadcaster like a real poll. */
    private AtmosphereResourceImpl poll() {
        return poll(null);
    }

    /** A poll whose response writes to {@code container}, when not null, like a servlet poll. */
    private AtmosphereResourceImpl poll(HttpServletResponse container) {
        var request = new AtmosphereRequestImpl.Builder()
                .headers(Map.of(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, TRACKING_ID,
                        HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.LONG_POLLING_TRANSPORT))
                .build();
        var response = container == null
                ? AtmosphereResponseImpl.newInstance()
                : new AtmosphereResponseImpl.Builder().request(request).response(container).build();
        // Set by the framework on every response: the response's uuid is the client's.
        response.setHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, TRACKING_ID);
        var resource = new AtmosphereResourceImpl();
        resource.initialize(config, broadcaster, request, response,
                mock(BlockingIOCometSupport.class), handler);
        // Set by the AsynchronousProcessor for a real request: how the response finds its resource.
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);
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

    @Test
    void aWriteThatFailsOnAPollTheClientGaveUpOnLeavesTheNextPollSubscribed() throws Exception {
        start(false);
        var gaveUp = poll();

        // The write is still running on the poll when the client gives up on it and
        // polls again: the next poll replaces it on the broadcaster.
        handler.blockNext.set(true);
        var slow = broadcaster.broadcast("slow", gaveUp);
        assertTrue(handler.writing.await(WAIT_SECONDS, TimeUnit.SECONDS));
        var next = poll();
        assertNotSame(gaveUp, next);
        assertTrue(isMember(next), "the next poll replaced the one the client gave up on");
        assertFalse(isMember(gaveUp));

        // The write then fails on the connection the client dropped.
        handler.failOn.set(gaveUp);
        handler.release.countDown();
        slow.get(WAIT_SECONDS, TimeUnit.SECONDS);
        // The broadcaster closes the failed poll on its write executor; the close
        // unregisters it, and must leave the next poll registered and subscribed.
        awaitClosedByApplication(gaveUp);
        assertStaysMember(next, "the write that failed on the poll the client gave up on removed the next poll");
        assertSame(next, config.resourcesFactory().findResource(TRACKING_ID).orElse(null),
                "closing the poll the client gave up on unregistered the next poll");

        broadcaster.broadcast("everyone").get(WAIT_SECONDS, TimeUnit.SECONDS);
        var last = handler.writes.get(handler.writes.size() - 1);
        assertEquals("everyone", last.message(), "a broadcast to everyone reaches the next poll");
        assertSame(next, last.resource());
    }

    @Test
    void removingAClosingPollKeepsCachingForTheClientBetweenItsPolls() throws Exception {
        start(true, true);
        var closing = poll();
        var next = poll();
        assertTrue(isMember(closing) && isMember(next), "both polls of the client are members");
        closing.getAtmosphereResourceEvent().setCancelled(true);

        // The write finds the closing poll, still suspended, and removes it.
        broadcaster.broadcast("stale", closing).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertFalse(isMember(closing));
        assertTrue(isMember(next));

        // The next poll returns; a broadcast lands while the client has no poll.
        next.resume();
        broadcaster.broadcast("between").get(WAIT_SECONDS, TimeUnit.SECONDS);
        handler.writes.clear();

        var third = poll();
        var received = handler.writes.stream()
                .filter(w -> w.resource() == third)
                .map(Write::message)
                .flatMap(m -> m instanceof List<?> list ? list.stream() : Stream.of(m))
                .toList();
        assertTrue(received.contains("between"),
                "the client's poll gets the message broadcast between its polls, got " + received);
    }

    @Test
    void theNextPollRegisteringWhileTheReturnedOneUnregistersStaysRegisteredAndSubscribed() throws Exception {
        start(false);
        var factory = new PausingResourceFactory();
        config.framework().setAndConfigureAtmosphereResourceFactory(factory);
        var returned = poll();
        assertSame(returned, factory.findResource(TRACKING_ID).orElse(null));

        // The poll returns on another thread; while it unregisters, the client's
        // next poll arrives and registers under the same uuid.
        var resuming = Thread.ofPlatform().unstarted(returned::resume);
        factory.pauseOn.set(resuming);
        resuming.start();
        assertTrue(factory.paused.await(WAIT_SECONDS, TimeUnit.SECONDS), "the poll never unregistered");
        var next = poll();
        factory.proceed.countDown();
        resuming.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertFalse(resuming.isAlive(), "the poll never finished returning");

        assertNotSame(returned, next);
        assertTrue(isMember(next), "the poll that returned removed the next poll from the broadcaster");
        assertSame(next, factory.findResource(TRACKING_ID).orElse(null),
                "the poll that returned unregistered the next poll");
    }

    @Test
    void aWriteThatFailsOnTheResponseOfAReturnedPollLeavesTheNextPollRegisteredAndSubscribed() throws Exception {
        start(false);
        // The client dropped the connection the poll answered on: writes to it fail.
        var dropped = mock(HttpServletResponse.class);
        when(dropped.getWriter()).thenThrow(new IOException("Broken pipe"));
        when(dropped.getOutputStream()).thenThrow(new IOException("Broken pipe"));
        var returned = poll(dropped);
        var held = returned.getResponse();
        returned.resume();
        var next = poll();
        assertTrue(isMember(next));
        assertSame(next, config.resourcesFactory().findResource(TRACKING_ID).orElse(null));

        // Code that kept the returned poll's response writes to it.
        assertThrows(RuntimeException.class, () -> held.write("late"));

        assertTrue(isMember(next), "the failed write on the returned poll's response removed the next poll");
        assertSame(next, config.resourcesFactory().findResource(TRACKING_ID).orElse(null),
                "the failed write on the returned poll's response unregistered the next poll");
    }

    /** Checks for a while: the close of a failed poll finishes after it is flagged. */
    private void assertStaysMember(AtmosphereResource resource, String message) throws InterruptedException {
        var until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        do {
            assertTrue(isMember(resource), message);
            Thread.sleep(10);
        } while (System.nanoTime() < until);
    }

    private static void awaitClosedByApplication(AtmosphereResourceImpl resource) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!resource.getAtmosphereResourceEvent().isClosedByApplication()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the failed poll was never closed");
            }
            Thread.sleep(5);
        }
    }
}
