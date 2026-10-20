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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A page whose connection went away mid-turn reloads and must get the whole
 * turn: metadata sent while it was gone (the model's name the stats line shows,
 * token counts) and a handoff are kept with the run and replayed, not dropped.
 */
class ReattachReplaysMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        DefaultStreamingSession.cleanupResource("client-1");
        RunRegistryHolder.reset();
    }

    @Test
    void metadataSentWhileTheClientWasGoneReachesItsReattach() throws Exception {
        var registry = new RunRegistry();
        var execution = new ExecutionHandle.Settable(() -> { });
        var run = registry.register("ai-tools", "anonymous", "client-1", execution,
                RunEventReplayBuffer.forRun(null, null), null);
        var live = new CopyOnWriteArrayList<String>();
        var leaf = new DefaultStreamingSession("session-1", client(live), false);
        var session = new RunEventCapturingSession(leaf, run.replayBuffer());

        session.sendMetadata("ai.model", "qwen2.5:7b");
        session.send("Paris is ");
        DefaultStreamingSession.cleanupResource("client-1");
        session.send("lovely.");
        session.sendMetadata("ai.tokens.total", 42);
        session.complete();
        execution.complete();

        var replay = new ArrayList<String>();
        RunReattachSupport.replayPendingRun(reconnected(run.runId(), replay), registry);
        var frames = replay.stream().map(ReattachReplaysMetadataTest::parse).toList();
        assertEquals(List.of("metadata:ai.model=qwen2.5:7b", "streaming-text", "streaming-text",
                "metadata:ai.tokens.total=42", "complete"), frames.stream().map(ReattachReplaysMetadataTest::kind)
                .toList());
        assertEquals(42, frames.get(3).path("value").intValue(), "a value keeps its JSON type");

        // A frame the client received live is replayed under the same (sessionId, seq): it is dropped.
        var liveModel = parse(live.get(0));
        assertEquals("metadata", liveModel.path("type").asString());
        assertEquals(liveModel.path("sessionId").asString(), frames.get(0).path("sessionId").asString());
        assertEquals(liveModel.path("seq").longValue(), frames.get(0).path("seq").longValue());
    }

    @Test
    void aHandoffMadeWhileTheClientWasGoneReachesItsReattach() throws Exception {
        var registry = new RunRegistry();
        var run = registry.register("support", "anonymous", "client-1", new ExecutionHandle.Settable(() -> { }),
                RunEventReplayBuffer.forRun(null, null), null);
        var leaf = new DefaultStreamingSession("support-session", client(new ArrayList<>()), false);
        var session = new RunEventCapturingSession(leaf, run.replayBuffer());

        DefaultStreamingSession.cleanupResource("client-1");
        session.emit(new AiEvent.Handoff("support", "billing", "User request routed to billing"));

        var replay = new ArrayList<String>();
        RunReattachSupport.replayPendingRun(reconnected(run.runId(), replay), registry);
        assertEquals(1, replay.size(), "the handoff: " + replay);
        var frame = parse(replay.get(0));
        assertEquals("handoff", frame.path("event").asString());
        assertEquals("billing", frame.path("data").path("toAgent").asString());
        assertEquals("support-session", frame.path("sessionId").asString());
    }

    private static String kind(JsonNode frame) {
        if (frame.has("event")) {
            return frame.path("event").asString();
        }
        var type = frame.path("type").asString();
        if ("metadata".equals(type)) {
            return "metadata:" + frame.path("key").asString() + "=" + frame.path("value").asString();
        }
        return type;
    }

    private static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    @SuppressWarnings("unchecked")
    private static AtmosphereResource client(List<String> written) {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.uuid()).thenReturn("client-1");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(RawMessage.class), anySet())).thenAnswer(i -> {
            written.add((String) i.<RawMessage>getArgument(0).message());
            return null;
        });
        return resource;
    }

    /** The client's page reloaded: a WebSocket under a new tracking id carrying the run id. */
    private static AtmosphereResource reconnected(String runId, List<String> writes) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
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
        when(request.getAttribute(anyString())).thenAnswer(i ->
                RunReattachSupport.RUN_ID_ATTRIBUTE.equals(i.getArgument(0)) ? runId : null);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(writer);
        return resource;
    }
}
