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
package org.atmosphere.ai;

import org.atmosphere.ai.resume.RunEventCapturingSession;
import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistry;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A page reloaded early in a turn reattaches to the run of the agent it
 * prompted; that agent then hands the turn off and leaves its own run open, as
 * a {@code @Prompt} that calls {@code session.handoff(...)} does. The page must
 * follow the turn to the run it was handed to and get its answer, although the
 * run it follows never ends.
 */
class HandoffWhileReattachedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        DefaultStreamingSession.cleanupResource("client-1");
        RunRegistryHolder.reset();
    }

    @Test
    void aClientFollowingARunFollowsTheTurnItHandsOffAlthoughTheRunStaysOpen() throws Exception {
        var registry = new RunRegistry();
        RunRegistryHolder.install(registry);
        var support = registry.register("support", "anonymous", "client-1", new ExecutionHandle.Settable(() -> { }),
                RunEventReplayBuffer.forRun(null, null), null);
        var supportSession = new RunEventCapturingSession(
                new DefaultStreamingSession("support-session", client(), false), support.replayBuffer());
        DefaultStreamingSession.cleanupResource("client-1");

        // The reloaded page reattaches while the support agent's turn goes on.
        var replay = new ArrayList<String>();
        RunReattachSupport.replayPendingRun(reconnected(support.runId(), replay), registry);

        // The support agent hands the turn off; its own run is never ended.
        supportSession.emit(new AiEvent.Handoff("support", "billing", "User request routed to billing"));
        var billingExecution = new ExecutionHandle.Settable(() -> { });
        var billing = registry.register("billing", "anonymous", "client-1", billingExecution,
                RunEventReplayBuffer.forRun(null, null), null);
        support.replayBuffer().handedOffTo(billing.runId());
        var billingSession = new RunEventCapturingSession(
                new DefaultStreamingSession("billing-session", client(), false), billing.replayBuffer());
        billingSession.emit(new AiEvent.ToolStart("process_refund", Map.of("invoice", "INV-1")));
        billingSession.send("Refunded.");
        billingSession.complete();
        billingExecution.complete();

        var kinds = replay.stream().map(json -> {
            var frame = MAPPER.readTree(json);
            return frame.has("event") ? frame.path("event").asString() : frame.path("type").asString();
        }).toList();
        assertEquals(List.of("handoff", "tool-start", "streaming-text", "complete"), kinds,
                "the handoff, then the turn of the run it was handed to: " + replay);
    }

    @SuppressWarnings("unchecked")
    private static AtmosphereResource client() {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.uuid()).thenReturn("client-1");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(RawMessage.class), anySet())).thenReturn(null);
        return resource;
    }

    /** The client's page reloaded: a WebSocket under a new tracking id carrying the run id. */
    private static AtmosphereResource reconnected(String runId, List<String> writes) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        var attributes = new ConcurrentHashMap<String, Object>();
        attributes.put(RunReattachSupport.RUN_ID_ATTRIBUTE, runId);
        when(request.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.<String>getArgument(0)));
        doAnswer(i -> attributes.put(i.getArgument(0), i.getArgument(1)))
                .when(request).setAttribute(anyString(), any());
        doAnswer(i -> attributes.remove(i.<String>getArgument(0))).when(request).removeAttribute(anyString());
        var sink = new StringWriter();
        var writer = new PrintWriter(sink) {
            @Override
            public void flush() {
                super.flush();
                if (!sink.toString().isEmpty()) {
                    writes.add(sink.toString());
                    sink.getBuffer().setLength(0);
                }
            }
        };
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("reloaded");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(writer);
        return resource;
    }
}
