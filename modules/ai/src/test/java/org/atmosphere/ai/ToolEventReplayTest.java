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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A page reloaded after a tool ran showed the answer again but not the tool's
 * card: the run's replay kept its text, prompt and terminal frames only. The
 * tool events a run's session writes are recorded with the run and replayed as
 * the frames the client received: the same {@code event} frame, under the same
 * {@code (sessionId, seq)}.
 */
class ToolEventReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        DefaultStreamingSession.cleanupResource("client-1");
    }

    @Test
    void theToolEventsOfARunAreReplayedAsTheFramesTheClientReceived() throws Exception {
        var written = new CopyOnWriteArrayList<String>();
        var leaf = new DefaultStreamingSession("session-1", client(written), false);
        var registry = new RunRegistry();
        var execution = new ExecutionHandle.Settable(() -> { });
        var run = registry.register("agent", "anonymous", "client-1", execution,
                RunEventReplayBuffer.forRun(null, null), null);
        var session = new RunEventCapturingSession(leaf, run.replayBuffer());

        session.emit(new AiEvent.ToolStart("get_weather", Map.of("city", "Paris")));
        session.emit(new AiEvent.ToolResult("get_weather", Map.of("temp", 21)));
        session.emit(new AiEvent.ToolError("get_time", "unavailable"));
        session.send("It is 21 degrees.");
        session.complete();
        execution.complete();

        var live = written.stream().map(ToolEventReplayTest::parse).filter(f -> !f.has("type")
                || !"complete".equals(f.path("type").asString())).toList();
        var replay = new ArrayList<String>();
        RunReattachSupport.replayPendingRun(reconnected(run.runId(), replay), registry);
        var replayed = replay.stream().map(ToolEventReplayTest::parse).filter(f -> !f.has("type")
                || !"complete".equals(f.path("type").asString())).toList();

        assertEquals(live, replayed, "the replay writes each frame the client received, tool events included");
        assertEquals(List.of("tool-start", "tool-result", "tool-error"), replayed.stream()
                .filter(f -> f.has("event")).map(f -> f.path("event").asString()).toList());
        assertEquals("Paris", replayed.get(0).path("data").path("arguments").path("city").asString());
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
