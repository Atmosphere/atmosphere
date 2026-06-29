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

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Proves the {@code @AiEndpoint(broadcastReply = true)} shared-room contract end
 * to end against a real {@link DefaultBroadcaster}: one prompt, dispatched to a
 * single {@code @Prompt} handler, produces exactly one reply that fans out to
 * <em>every</em> subscriber on the room broadcaster.
 *
 * <p>This is the observable proof behind the blog claim "for shared rooms you
 * opt into a broadcaster and one reply fans out to every subscriber":</p>
 * <ul>
 *   <li><b>Fan-out:</b> two resources subscribe to the same room broadcaster; a
 *       prompt arrives on one of them; both receive the same single reply.</li>
 *   <li><b>One model call:</b> the {@code @Prompt} method (the LLM-call site)
 *       runs exactly once — not once per subscriber. This guards the
 *       N-redundant-LLM-calls regression that targeted dispatch was built to
 *       prevent: the prompt is still dispatched to the originating resource
 *       only, broadcastReply changes only where the <em>reply</em> streams.</li>
 *   <li><b>Default unchanged:</b> with broadcastReply off (the default), the
 *       reply reaches only the originating subscriber — the bystander gets
 *       nothing.</li>
 * </ul>
 *
 * <p>The harness mirrors {@code BroadcasterTest}: a real {@code DefaultBroadcaster}
 * with real {@link AtmosphereResourceImpl} subscribers whose {@link AtmosphereHandler}
 * records every frame the broadcaster delivers to them. The reply is captured at
 * the real broadcaster's delivery boundary, so "both subscribers receive it" is a
 * genuine fan-out observation, not a verified mock overload.</p>
 */
// Constructs real AtmosphereResourceImpl subscribers via the deprecated 6-arg
// constructor — the same test harness BroadcasterTest uses to exercise real
// broadcaster delivery. No non-deprecated public constructor builds a standalone
// resource for an isolated unit test.
@SuppressWarnings("deprecation")
class AiEndpointHandlerBroadcastReplyTest {

    private static final String REPLY = "the-one-reply";

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private CapturingHandler capture;
    private Method promptMethod;

    @BeforeEach
    void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/atmosphere/room/general");
        capture = new CapturingHandler();
        promptMethod = StubEndpoint.class.getDeclaredMethod(
                "onPrompt", String.class, StreamingSession.class);
    }

    @AfterEach
    void tearDown() {
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void broadcastReplyTrue_oneReplyFansOutToAllSubscribers_andPromptRunsOnce() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();

        var endpoint = new StubEndpoint();
        var handler = newHandler(endpoint);
        handler.setBroadcastReply(true);

        // Expect the single reply to be delivered to BOTH subscribers.
        capture.expectReplies(2);
        handler.onStateChange(new AtmosphereResourceEventImpl(
                (AtmosphereResourceImpl) originating).setMessage("hello room"));

        assertTrue(capture.await(10, TimeUnit.SECONDS),
                "the one reply must reach both subscribers on the room broadcaster");

        // (a) Both subscribers received the SAME single reply.
        assertTrue(capture.received(originating.uuid(), REPLY),
                "originating subscriber must receive the reply");
        assertTrue(capture.received(bystander.uuid(), REPLY),
                "bystander subscriber in the same room must receive the same reply");

        // (b) The @Prompt method (the LLM-call site) ran exactly ONCE, not once
        // per subscriber — the N-redundant-call regression guard.
        assertEquals(1, endpoint.invocations.get(),
                "@Prompt must run exactly once regardless of room size");
    }

    @Test
    void broadcastReplyFalse_replyReachesOnlyOriginatingSubscriber() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();

        var endpoint = new StubEndpoint();
        // Default: no setBroadcastReply -> per-client behavior.
        var handler = newHandler(endpoint);

        capture.expectReplies(1);
        handler.onStateChange(new AtmosphereResourceEventImpl(
                (AtmosphereResourceImpl) originating).setMessage("hello room"));

        assertTrue(capture.await(10, TimeUnit.SECONDS),
                "the originating subscriber must receive its own reply");

        assertEquals(1, endpoint.invocations.get(),
                "@Prompt must run exactly once");
        assertTrue(capture.received(originating.uuid(), REPLY),
                "originating subscriber must receive the reply");

        // Give any errant cross-tab delivery a chance to arrive, then assert the
        // bystander saw nothing — the default per-client path is unchanged.
        Thread.sleep(300);
        assertFalse(capture.received(bystander.uuid(), REPLY),
                "with broadcastReply off, the bystander must NOT receive the reply");
        assertTrue(capture.framesFor(bystander.uuid()).isEmpty(),
                "with broadcastReply off, the bystander must receive no frames at all");
    }

    // Uses the path-aware constructor so the handler carries a non-null path
    // template; the run-registry dispatch derives a non-null agent id from it.
    private AiEndpointHandler newHandler(StubEndpoint endpoint) {
        return new AiEndpointHandler(endpoint, promptMethod, 30_000L, "",
                "/atmosphere/room/{room}",
                mock(AgentRuntime.class), List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(StubEndpoint.class));
    }

    private AtmosphereResource subscriber() {
        var r = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                capture);
        room.addAtmosphereResource(r);
        return r;
    }

    /**
     * Records every frame the broadcaster delivers to a subscribed resource,
     * keyed by resource UUID, and counts down a latch when the AI reply lands.
     */
    private static final class CapturingHandler implements AtmosphereHandler {

        private final Map<String, List<String>> framesByUuid = new ConcurrentHashMap<>();
        private volatile CountDownLatch replyLatch = new CountDownLatch(0);

        void expectReplies(int n) {
            replyLatch = new CountDownLatch(n);
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return replyLatch.await(timeout, unit);
        }

        @Override
        public void onRequest(AtmosphereResource r) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) {
            var msg = e.getMessage();
            // The reply is broadcast as a RawMessage carrying the JSON wire frame;
            // unwrap to the JSON payload (mirrors AiEndpointHandler.onStateChange).
            var json = msg instanceof RawMessage raw ? String.valueOf(raw.message()) : String.valueOf(msg);
            framesByUuid.computeIfAbsent(e.getResource().uuid(), k -> new CopyOnWriteArrayList<>()).add(json);
            if (json.contains(REPLY)) {
                replyLatch.countDown();
            }
        }

        @Override
        public void destroy() {
        }

        boolean received(String uuid, String text) {
            return framesByUuid.getOrDefault(uuid, List.of()).stream()
                    .anyMatch(frame -> frame.contains(text));
        }

        List<String> framesFor(String uuid) {
            return framesByUuid.getOrDefault(uuid, List.of());
        }
    }

    @AiEndpoint(path = "/atmosphere/room/{room}", broadcastReply = true)
    static final class StubEndpoint {

        private final AtomicInteger invocations = new AtomicInteger();

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            // The @Prompt body is the LLM-call site. It must run exactly once per
            // prompt regardless of how many subscribers share the room — if it
            // ran once per subscriber, this counter would exceed 1 and the model
            // would be billed N times for one question.
            invocations.incrementAndGet();
            session.send(REPLY);
            session.complete();
        }
    }
}
