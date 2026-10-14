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
package org.atmosphere.session;

import jakarta.servlet.ServletContext;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The interceptor's per-connection and per-token bookkeeping: what it keeps
 * for a resource must go away with the resource, what it keeps for a token
 * must go away when no thread works on that token, and a client that polls
 * without its token must not get a new session on every poll.
 */
class DurableSessionBookkeepingTest {

    /** Records every session token the interceptor saved. */
    static final class CountingStore extends InMemorySessionStore {

        final Set<String> tokens = ConcurrentHashMap.newKeySet();

        @Override
        public void save(DurableSession session) {
            tokens.add(session.token());
            super.save(session);
        }
    }

    private CountingStore store;
    private DurableSessionInterceptor interceptor;
    private AtmosphereConfig config;

    @BeforeEach
    void setUp() {
        store = new CountingStore();
        interceptor = new DurableSessionInterceptor(store, Duration.ofHours(24), Duration.ofMinutes(5));

        config = mock(AtmosphereConfig.class);
        var framework = mock(AtmosphereFramework.class);
        when(config.getBroadcasterFactory()).thenReturn(mock(BroadcasterFactory.class));
        when(config.framework()).thenReturn(framework);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.getServletContext()).thenReturn(mock(ServletContext.class));
        interceptor.configure(config);
    }

    @AfterEach
    void tearDown() {
        interceptor.destroy();
    }

    /** One HTTP request (a poll, a WebSocket open) of the connection {@code uuid}. */
    private static AtmosphereResource request(String uuid, String token) {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(mock(AtmosphereResponse.class));
        when(resource.uuid()).thenReturn(uuid);
        when(resource.broadcasters()).thenReturn(List.of());
        when(request.getHeader(DurableSessionInterceptor.SESSION_TOKEN_HEADER)).thenReturn(token);
        return resource;
    }

    private static Broadcaster broadcaster(String id) {
        var b = mock(Broadcaster.class);
        when(b.getID()).thenReturn(id);
        return b;
    }

    private static String tokenSentTo(AtmosphereResource resource) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource.getResponse()).setHeader(
                eq(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER), captor.capture());
        return captor.getValue();
    }

    private static AtmosphereResourceEventListener saveListener(AtmosphereResource resource) {
        var captor = ArgumentCaptor.forClass(AtmosphereResourceEventListener.class);
        verify(resource, atLeastOnce()).addEventListener(captor.capture());
        return captor.getValue();
    }

    private static AtmosphereResourceEvent eventFor(AtmosphereResource resource) {
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        return event;
    }

    @Test
    void aConnectionThatReconnectsWithItsTrackingIdIsSavedOnEveryDisconnect() {
        var first = request("tracking-1", null);
        interceptor.inspect(first);
        var firstToken = tokenSentTo(first);
        var firstListener = saveListener(first);

        var a = broadcaster("/a");
        when(first.broadcasters()).thenReturn(List.of(a));
        firstListener.onDisconnect(eventFor(first));
        assertEquals(Set.of("/a"), store.restore(firstToken).orElseThrow().broadcasters());

        // onClose after onDisconnect on the same connection must not save it
        // again, here with the state it has once out of its broadcasters.
        when(first.broadcasters()).thenReturn(List.of());
        firstListener.onClose(eventFor(first));
        assertEquals(Set.of("/a"), store.restore(firstToken).orElseThrow().broadcasters(),
                "a second disconnect callback must not save the connection twice");

        // The client reconnects with the same X-Atmosphere-tracking-id, so the
        // resource has the same uuid, and leaves again.
        var second = request("tracking-1", null);
        interceptor.inspect(second);
        var secondToken = tokenSentTo(second);
        var b = broadcaster("/b");
        when(second.broadcasters()).thenReturn(List.of(b));
        saveListener(second).onDisconnect(eventFor(second));

        assertEquals(Set.of("/b"), store.restore(secondToken).orElseThrow().broadcasters(),
                "the second disconnect of a reconnected uuid must be saved");
    }

    @Test
    void aSessionLockIsDroppedOnceNoThreadUsesIt() {
        store.save(DurableSession.create("token-1", "old-resource"));

        interceptor.inspect(request("tracking-1", "token-1"));
        interceptor.saveCurrentState(request("tracking-1", null), "token-1");
        interceptor.inspect(request("tracking-2", "unknown-token"));

        assertEquals(0, interceptor.sessionLockCount(),
                "no thread restores or saves a session: no session lock may be left");
    }

    @Test
    void twoThreadsOnOneTokenNeverHoldTheSessionAtTheSameTime() throws Exception {
        var inside = new AtomicInteger();
        var overlap = new AtomicInteger();
        var firstIn = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocking = new InMemorySessionStore() {
            @Override
            public Optional<DurableSession> restore(String token) {
                if (inside.incrementAndGet() > 1) {
                    overlap.incrementAndGet();
                }
                try {
                    firstIn.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return super.restore(token);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                } finally {
                    inside.decrementAndGet();
                }
            }
        };
        blocking.save(DurableSession.create("shared", "old-resource"));
        var locked = new DurableSessionInterceptor(blocking, Duration.ofHours(24), Duration.ofMinutes(5));
        locked.configure(config);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> locked.inspect(request("tracking-a", "shared")));
            assertTrue(firstIn.await(5, TimeUnit.SECONDS));
            var b = pool.submit(() -> locked.saveCurrentState(request("tracking-a", null), "shared"));
            // Give b the time to reach the lock while a holds it.
            Thread.sleep(100);
            release.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        } finally {
            locked.destroy();
        }
        assertEquals(0, overlap.get(), "two threads restored the same session concurrently");
        assertEquals(0, locked.sessionLockCount());
    }

    @Test
    void aPollWithoutTheTokenKeepsItsConnectionsSession() {
        // Long-polling: every poll, and every message POST, is a new request and
        // a new resource object, with the connection's tracking id as uuid.
        var firstPoll = request("tracking-1", null);
        interceptor.inspect(firstPoll);
        var token = tokenSentTo(firstPoll);
        var firstPollListener = saveListener(firstPoll);
        firstPollListener.onSuspend(eventFor(firstPoll));

        var post = request("tracking-1", null);
        interceptor.inspect(post);
        var secondPoll = request("tracking-1", null);
        interceptor.inspect(secondPoll);
        saveListener(secondPoll).onSuspend(eventFor(secondPoll));

        assertEquals(Set.of(token), store.tokens,
                "polls of one connection without the session token must keep one durable session");
        // The token is a secret: a request that did not present it is never sent it.
        verify(secondPoll.getResponse(), never())
                .setHeader(eq(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER), anyString());

        // Resources that do not hold the connection go: the POST, and the first
        // poll, closed after the second one took the connection over. Neither
        // saves the connection nor ends it.
        var stale = broadcaster("/stale");
        when(firstPoll.broadcasters()).thenReturn(List.of(stale));
        saveListener(post).onClose(eventFor(post));
        firstPollListener.onDisconnect(eventFor(firstPoll));
        assertEquals(Set.of(), store.restore(token).orElseThrow().broadcasters());
        var thirdPoll = request("tracking-1", null);
        interceptor.inspect(thirdPoll);
        assertEquals(Set.of(token), store.tokens);

        // The poll holding the connection sees the client leave: it saves the
        // connection's state into the connection's session.
        var chat = broadcaster("/chat");
        when(secondPoll.broadcasters()).thenReturn(List.of(chat));
        saveListener(secondPoll).onDisconnect(eventFor(secondPoll));
        assertEquals(Set.of("/chat"), store.restore(token).orElseThrow().broadcasters());

        // Another connection gets its own session.
        var other = request("tracking-2", null);
        interceptor.inspect(other);
        assertEquals(2, store.tokens.size());
    }

    @Test
    void anotherTokenPresentedForALiveConnectionDoesNotRebindIt() {
        var poll = request("tracking-1", null);
        interceptor.inspect(poll);
        var token = tokenSentTo(poll);
        saveListener(poll).onSuspend(eventFor(poll));

        // Requests naming the connection's tracking id with a session of their
        // own, restored or created, get that session, not the connection.
        store.save(DurableSession.create("other-token", "elsewhere"));
        var restoring = request("tracking-1", "other-token");
        interceptor.inspect(restoring);
        saveListener(restoring).onSuspend(eventFor(restoring));
        var creating = request("tracking-1", "unknown-token");
        interceptor.inspect(creating);
        var created = tokenSentTo(creating);
        saveListener(creating).onSuspend(eventFor(creating));

        // The connection's next poll without the token keeps its own session,
        // and only its own state is saved into it.
        var next = request("tracking-1", null);
        interceptor.inspect(next);
        saveListener(next).onSuspend(eventFor(next));
        verify(next.getResponse(), never())
                .setHeader(eq(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER), anyString());
        var chat = broadcaster("/chat");
        when(next.broadcasters()).thenReturn(List.of(chat));
        saveListener(next).onDisconnect(eventFor(next));

        assertEquals(Set.of("/chat"), store.restore(token).orElseThrow().broadcasters());
        assertEquals(Set.of(), store.restore("other-token").orElseThrow().broadcasters());
        assertEquals(Set.of(), store.restore(created).orElseThrow().broadcasters());
    }

    @Test
    void aConnectionThatNeverDisconnectsIsForgottenAfterTheSessionTtl() throws Exception {
        var shortLived = new DurableSessionInterceptor(store, Duration.ofMillis(1), Duration.ofMinutes(5));
        shortLived.configure(config);
        try {
            // A long-polling client that stops polling: its last poll resumes on
            // timeout and no disconnect callback ever runs.
            shortLived.inspect(request("tracking-1", null));
            assertEquals(1, shortLived.trackedConnectionCount());

            Thread.sleep(20);
            shortLived.sweep();

            assertEquals(0, shortLived.trackedConnectionCount(),
                    "a connection idle for longer than the session TTL must be forgotten");
        } finally {
            shortLived.destroy();
        }
    }
}
