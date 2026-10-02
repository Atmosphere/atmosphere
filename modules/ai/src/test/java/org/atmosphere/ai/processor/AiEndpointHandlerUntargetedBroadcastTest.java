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
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A message broadcast on an {@code @AiEndpoint} path without going through the
 * endpoint's own prompt dispatch — a gRPC {@code Send} on the topic, an
 * application or admin broadcast, or the copy a cluster filter relays from
 * another node — reaches every subscriber's handler. It used to be run as a
 * prompt in each of those subscribers' sessions, one user's text answered in
 * every user's conversation. These tests pin, against a real
 * {@link DefaultBroadcaster} whose subscribers are served by the endpoint
 * handler itself, that only a prompt dispatched to a connection runs, and only
 * on that connection, and that the dispatch never goes through the path's
 * filters, where a cluster filter would publish it to the other nodes.
 */
// Real AtmosphereResourceImpl subscribers through the deprecated 6-arg
// constructor, as AiEndpointHandlerBroadcastReplyTest and BroadcasterTest do:
// no other public constructor builds a standalone resource for a unit test.
@SuppressWarnings("deprecation")
class AiEndpointHandlerUntargetedBroadcastTest {

    private static final String PATH = "/atmosphere/ai-chat";

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster path;
    private StubEndpoint endpoint;
    private AiEndpointHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        path = factory.get(DefaultBroadcaster.class, PATH);
        endpoint = new StubEndpoint();
        var promptMethod = StubEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class,
                AtmosphereResource.class);
        handler = new AiEndpointHandler(endpoint, promptMethod, 30_000L, "", PATH,
                mock(AgentRuntime.class), List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(StubEndpoint.class));
    }

    @AfterEach
    void tearDown() {
        path.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    private AtmosphereResource subscriber() {
        var r = new AtmosphereResourceImpl(config, path,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                handler);
        path.addAtmosphereResource(r);
        return r;
    }

    /**
     * Dispatches a prompt to {@code sender} the way the endpoint does, and waits
     * for it to run: every delivery broadcast before it has been handled by then.
     */
    private void barrier(AtmosphereResource sender, String text) throws Exception {
        handler.dispatchPrompt(sender, text);
        awaitPrompt(sender, text);
    }

    private void awaitPrompt(AtmosphereResource sender, String text) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!endpoint.prompts.contains(sender.uuid() + ":" + text)) {
            assertTrue(System.nanoTime() < deadline, "the dispatched prompt never ran: " + endpoint.prompts);
            Thread.sleep(10);
        }
        // Room for a prompt started late by an earlier delivery to show up.
        Thread.sleep(300);
    }

    @Test
    void plainStringBroadcastOnThePathRunsNoPrompt() throws Exception {
        var alice = subscriber();
        subscriber();

        // What GrpcProcessor does with a Send on the topic, unauthenticated.
        path.broadcast("hello from grpc").get(5, TimeUnit.SECONDS);
        barrier(alice, "alice's own prompt");

        assertEquals(List.of(alice.uuid() + ":alice's own prompt"), endpoint.prompts,
                "a plain broadcast must not run as a prompt in any subscriber's session");
    }

    @Test
    void approvalShapedBroadcastIsNotTriedAgainstEverySubscriber() throws Exception {
        var alice = subscriber();
        subscriber();

        path.broadcast("/__approval/apr_1234/approve").get(5, TimeUnit.SECONDS);
        barrier(alice, "next");

        assertEquals(List.of(alice.uuid() + ":next"), endpoint.prompts);
    }

    @Test
    void promptPostedOnAClusteredPathIsNeverPublishedToTheOtherNodes() throws Exception {
        // A ClusterBroadcastFilter on the path publishes whatever the broadcaster
        // filters to every other node (RedisClusterBroadcastFilter: the String, or
        // toString of anything else), where it is broadcast to every subscriber.
        // A prompt is dispatched to its sender's connection without a broadcast,
        // so neither its text nor the sender's tracking id (which would let anyone
        // post prompts as the sender) reaches the filter.
        var cluster = new RecordingClusterFilter();
        path.getBroadcasterConfig().addFilter(cluster);
        var alice = subscriber();
        var bob = subscriber();
        config.resourcesFactory().registerUuidForFindCandidate(alice);
        config.resourcesFactory().registerUuidForFindCandidate(bob);

        var post = new AtmosphereResourceImpl(config, path,
                new AtmosphereRequestImpl.Builder()
                        .method("POST")
                        .requestURI(PATH)
                        .headers(Map.of(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, alice.uuid()))
                        .body("alice's private question")
                        .build(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                handler);
        handler.onRequest(post);
        awaitPrompt(alice, "alice's private question");

        assertEquals(List.of(alice.uuid() + ":alice's private question"), endpoint.prompts,
                "the prompt runs once, on its sender's connection");
        for (var published : cluster.published) {
            assertFalse(published.contains(alice.uuid()),
                    "the sender's tracking id must not be published cluster-wide: " + published);
            assertFalse(published.contains("alice's private question"),
                    "the prompt must not be published cluster-wide: " + published);
        }
    }

    /** What a ClusterBroadcastFilter would publish to the other nodes, recorded instead. */
    private static final class RecordingClusterFilter implements ClusterBroadcastFilter {

        private final List<String> published = new CopyOnWriteArrayList<>();
        private Broadcaster broadcaster;

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            published.add(String.valueOf(message));
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
        }

        @Override
        public void init(AtmosphereConfig config) {
        }

        @Override
        public void destroy() {
        }

        @Override
        public void setUri(String name) {
        }

        @Override
        public void setBroadcaster(Broadcaster bc) {
            this.broadcaster = bc;
        }

        @Override
        public Broadcaster getBroadcaster() {
            return broadcaster;
        }
    }

    @AiEndpoint(path = PATH)
    static final class StubEndpoint {

        /** {@code <uuid of the connection it ran for>:<prompt>}, in run order. */
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        @Prompt
        public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
            prompts.add(resource.uuid() + ":" + message);
            session.complete();
        }
    }
}
