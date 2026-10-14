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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
