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
package org.atmosphere.session.frames;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.Deliver;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.session.DurableSession;
import org.atmosphere.session.DurableSessionInterceptor;
import org.atmosphere.session.InMemorySessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A long-polling client re-polls with its tracking id while the runtime still
 * holds its previous poll (a dead connection not detected yet). The fresh poll
 * replaces the older one, which the runtime closes, and a message the client
 * missed is answered straight from the {@code BroadcasterCache}: the fresh poll
 * never suspends. The client's next poll without the session token must keep
 * the connection's durable session rather than open a second one.
 */
class DurableSessionCachedPollTest {

    private static final long WAIT_SECONDS = 10;

    static final AtomicBoolean PARK_NEXT_WRITE = new AtomicBoolean();
    static volatile CountDownLatch writeParked;
    static volatile CountDownLatch releaseWrite;

    @ManagedService(path = "/durable-cached", broadcaster = ParkingBroadcaster.class)
    public static final class ChatService {

        @Message
        public String onMessage(String message) {
            return message;
        }
    }

    /**
     * Holds the delivery of a broadcast to the suspended poll after the message
     * was cached for the client and before it is written: the window in which
     * the client re-polls (a slow per-request filter, a busy write queue).
     */
    public static final class ParkingBroadcaster extends DefaultBroadcaster {

        @Override
        protected boolean perRequestFilter(AtmosphereResource r, Deliver msg) {
            if (PARK_NEXT_WRITE.compareAndSet(true, false)) {
                writeParked.countDown();
                try {
                    releaseWrite.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.perRequestFilter(r, msg);
        }
    }

    /** Records every session token the interceptor saved. */
    static final class CountingStore extends InMemorySessionStore {

        final Set<String> tokens = ConcurrentHashMap.newKeySet();

        @Override
        public void save(DurableSession session) {
            tokens.add(session.token());
            super.save(session);
        }
    }

    private AtmosphereFramework framework;
    private CountingStore store;

    @BeforeEach
    void setUp() throws Exception {
        PARK_NEXT_WRITE.set(false);
        writeParked = new CountDownLatch(1);
        releaseWrite = new CountDownLatch(1);
        store = new CountingStore();
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(ChatService.class);
        framework.addInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false");
        framework.addInitParameter(ApplicationConfig.BROADCASTER_CACHE, UUIDBroadcasterCache.class.getName());
        framework.interceptor(new DurableSessionInterceptor(store));
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res)
                    throws IOException, ServletException {
                return suspended(req, res);
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
    }

    @AfterEach
    void tearDown() {
        releaseWrite.countDown();
        framework.destroy();
    }

    /** One poll, with what the client wrote back and the request the runtime served. */
    private record Poll(AtmosphereRequest request, AtmosphereResponse response, StringBuilder body) {

        String header(String name) {
            return response.getHeader(name);
        }
    }

    private Poll poll(String trackingId, String token) throws Exception {
        var headers = new HashMap<String, String>();
        headers.put(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, trackingId);
        headers.put(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.LONG_POLLING_TRANSPORT);
        if (token != null) {
            headers.put(DurableSessionInterceptor.SESSION_TOKEN_HEADER, token);
        }
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false)
                .pathInfo("/durable-cached")
                .headers(Map.copyOf(headers))
                .build();
        var response = AtmosphereResponseImpl.newInstance(request);
        var body = new StringBuilder();
        response.asyncIOWriter(new AsyncIOWriterAdapter() {
            @Override
            public AsyncIOWriter write(AtmosphereResponse r, String data) {
                synchronized (body) {
                    body.append(data);
                }
                return this;
            }

            @Override
            public AsyncIOWriter write(AtmosphereResponse r, byte[] data) {
                return write(r, new String(data, StandardCharsets.UTF_8));
            }

            @Override
            public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) {
                return write(r, new String(data, offset, length, StandardCharsets.UTF_8));
            }
        });
        framework.doCometSupport(request, response);
        return new Poll(request, response, body);
    }

    /** The connection after a re-poll answered from the cache. */
    private record Cached(String trackingId, String token, String broadcasterId) {
    }

    private Cached answerAPollFromTheCache() throws Exception {
        // The client's first poll gets a session and its token.
        var first = poll("0", null);
        var trackingId = first.header(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        var token = first.header(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER);
        assertNotNull(trackingId, "the first poll is assigned a tracking id");
        assertNotNull(token, "the first poll is sent its session token");

        // It read the token: its next poll presents it and is suspended.
        var held = poll(trackingId, token);
        assertTrue(held.request().resource().isSuspended(), "the poll presenting the token is suspended");

        // A message is cached for the client, and its write to the held poll is in flight.
        var broadcaster = held.request().resource().getBroadcaster();
        PARK_NEXT_WRITE.set(true);
        broadcaster.broadcast("missed");
        assertTrue(writeParked.await(WAIT_SECONDS, TimeUnit.SECONDS), "the write must be in flight");

        // The client re-polls without the token: the runtime closes the held poll
        // and answers the fresh one from the cache, so the fresh poll never suspends.
        var cached = poll(trackingId, null);
        assertTrue(held.request().resource().isCancelled(), "precondition: the runtime closed the held poll");
        assertNotNull(cached.request().getAttribute(DefaultBroadcaster.CACHED),
                "precondition: the re-poll is answered from the BroadcasterCache");
        assertTrue(cached.body().toString().contains("missed"), "the re-poll receives the cached message");
        releaseWrite.countDown();
        return new Cached(trackingId, token, broadcaster.getID());
    }

    @Test
    void aPollAnsweredFromTheCacheKeepsTheConnectionsDurableSession() throws Exception {
        var cached = answerAPollFromTheCache();
        var token = cached.token();

        // The client polls again, still without the token.
        var next = poll(cached.trackingId(), null);

        assertEquals(Set.of(token), store.tokens,
                "polls of one connection must keep its durable session after a poll answered from the cache");

        // The client leaves: the poll holding the connection saves it into its session.
        assertTrue(next.request().resource().isSuspended(), "precondition: the next poll holds the connection");
        var asyncSupport = (AsynchronousProcessor) framework.getAsyncSupport();
        asyncSupport.endRequest((AtmosphereResourceImpl) next.request().resource(), true);
        assertEquals(Set.of(cached.broadcasterId()), store.restore(token).orElseThrow().broadcasters(),
                "the connection's state must be saved into its session when the client leaves");
    }

    @Test
    void aClientGoneAfterAPollAnsweredFromTheCacheHasItsStateSaved() throws Exception {
        var cached = answerAPollFromTheCache();
        var token = cached.token();

        // The poll answered from the cache never fires a disconnect: if the client
        // never polls again, the state saved when the runtime closed the replaced
        // poll is what the session keeps.
        assertEquals(Set.of(cached.broadcasterId()), store.restore(token).orElseThrow().broadcasters(),
                "closing the replaced poll must save the connection's state");

        // The client that does come back, without the token, keeps the same session.
        poll(cached.trackingId(), null);
        assertEquals(Set.of(token), store.tokens,
                "saving the replaced poll must not end the connection's durable session");
    }
}
