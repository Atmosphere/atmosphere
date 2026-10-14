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
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Long-term memory must write and read under one identity. Recall keys on
 * {@code ai.userId}, which is null for an unidentified visitor, but the
 * connection owner recorded at dispatch was the run owner — the
 * {@code "anonymous"} marker — so on-close extraction saved every anonymous
 * visitor's facts into one shared bucket that nothing ever read back (and that
 * any future recall-by-run-owner would have leaked across visitors).
 */
class AiEndpointHandlerAnonymousMemoryOwnerTest {

    static class Endpoint {
        @Prompt
        public void onPrompt(String message) {
            // Not exercised: only the owner bookkeeping and the disconnect path are.
        }
    }

    static final class OwnerCapturingInterceptor implements AiInterceptor {
        final List<String> owners = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onDisconnect(String userId, String conversationId, List<ChatMessage> history) {
            owners.add(userId);
        }
    }

    private AiEndpointHandler handler;
    private OwnerCapturingInterceptor interceptor;
    private final List<String> uuids = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new OwnerCapturingInterceptor();
        handler = new AiEndpointHandler(new Endpoint(),
                Endpoint.class.getMethod("onPrompt", String.class), 30_000L, "",
                mock(AgentRuntime.class), List.<AiInterceptor>of(interceptor));
    }

    @AfterEach
    void forgetOwners() {
        uuids.forEach(AiEndpointHandler::forgetRunOwner);
    }

    private AtmosphereResourceImpl resource(String uuid, String aiUserId) {
        uuids.add(uuid);
        var request = mock(AtmosphereRequest.class);
        when(request.getAttribute("ai.userId")).thenReturn(aiUserId);
        var resource = mock(AtmosphereResourceImpl.class);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getBroadcaster()).thenReturn(mock(Broadcaster.class));
        return resource;
    }

    /**
     * Drives the fix's call site: the owner a prompt dispatch records for its
     * connection, then the recycled-request disconnect that reads it back.
     * The other tests call the recording helper directly, so reverting the
     * dispatch to record the run owner would leave them green.
     */
    @Test
    // A real AtmosphereResourceImpl through the deprecated 6-arg constructor —
    // the harness AiEndpointHandlerFactsCachePrefixTest uses for a dispatch.
    @SuppressWarnings("deprecation")
    void anAnonymousPromptDispatchLeavesItsDisconnectWithoutAMemoryOwner() throws Exception {
        var config = new AtmosphereFramework().getAtmosphereConfig();
        var factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        var room = factory.get(DefaultBroadcaster.class, "/ai/anonymous-owner");
        try {
            var dispatching = new AiEndpointHandler(new Endpoint(),
                    Endpoint.class.getMethod("onPrompt", String.class), 30_000L, "",
                    "/ai/anonymous-owner", mock(AgentRuntime.class), List.<AiInterceptor>of(interceptor),
                    null, AnnotatedLifecycle.scan(Endpoint.class));
            var visitor = new AtmosphereResourceImpl(config, room,
                    AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                    mock(BlockingIOCometSupport.class),
                    new org.atmosphere.handler.AbstractReflectorAtmosphereHandler.Default());
            room.addAtmosphereResource(visitor);
            uuids.add(visitor.uuid());

            assertTrue(dispatching.dispatchPrompt(visitor, "hello"), "the prompt is dispatched");
            assertNull(AiEndpointHandler.recallRunOwner(visitor.uuid()),
                    "an anonymous dispatch records no owner, never the 'anonymous' run-owner marker");

            var event = new AtmosphereResourceEventImpl(visitor, true, false);
            event.destroy(); // Tomcat recycled the request: only the uuid is left
            dispatching.onStateChange(event);

            assertEquals(Arrays.asList((String) null), interceptor.owners,
                    "the disconnect extracts under no identity, not a shared 'anonymous' bucket");
        } finally {
            RunRegistryHolder.reset();
            room.destroy();
            factory.destroy();
            ExecutorsFactory.reset(config);
        }
    }

    @Test
    void anAnonymousVisitorsDisconnectCarriesNoMemoryOwner() throws Exception {
        var visitorA = resource("uuid-anon-A", null);
        var visitorB = resource("uuid-anon-B", null);
        // Precondition: the run registry owner of an unidentified caller is the marker.
        assertEquals("anonymous", AiEndpointHandler.resolveRunOwner(visitorA));

        AiEndpointHandler.recordConnectionOwner(visitorA);
        AiEndpointHandler.recordConnectionOwner(visitorB);
        handler.onStateChange(new AtmosphereResourceEventImpl(visitorA, true, false));
        handler.onStateChange(new AtmosphereResourceEventImpl(visitorB, true, false));

        assertEquals(Arrays.asList(null, null), interceptor.owners,
                "no identity means no extraction — never a shared 'anonymous' memory key");
    }

    @Test
    void anAnonymousVisitorStaysOwnerlessOnTheRecycledRequestPath() throws Exception {
        var visitor = resource("uuid-anon-recycled", null);
        AiEndpointHandler.recordConnectionOwner(visitor);
        assertNull(AiEndpointHandler.recallRunOwner("uuid-anon-recycled"));

        var event = new AtmosphereResourceEventImpl(visitor, true, false);
        event.destroy(); // Tomcat recycled the request: only the uuid is left
        handler.onStateChange(event);

        assertEquals(Arrays.asList((String) null), interceptor.owners);
    }

    @Test
    void anIdentifiedVisitorKeepsTheIdentityRecallReads() throws Exception {
        var visitor = resource("uuid-alice", "alice");
        AiEndpointHandler.recordConnectionOwner(visitor);

        var event = new AtmosphereResourceEventImpl(visitor, true, false);
        event.destroy();
        handler.onStateChange(event);

        assertEquals(List.of("alice"), interceptor.owners,
                "the recycled path must still extract under the ai.userId recall reads");
    }
}
