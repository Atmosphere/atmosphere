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
package org.atmosphere.agui.runtime;

import org.atmosphere.agui.annotation.AgUiAction;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks one full AG-UI run on the wire against the structural shapes the
 * AG-UI 1.0 schema ({@code spec/1.0/schema.json} in ag-ui-protocol/ag-ui)
 * defines: {@code MESSAGES_SNAPSHOT.messages} is an array of messages,
 * {@code STATE_DELTA.delta} and {@code ACTIVITY_DELTA.patch} are RFC 6902
 * JSON Patch arrays, {@code ACTIVITY_SNAPSHOT.content} is an object,
 * {@code CUSTOM.value} is any JSON value, and an activity delta patches an
 * activity a prior snapshot established. A spec-strict client (CopilotKit's
 * {@code @ag-ui/core} schemas) rejects a JSON string where these structures
 * belong.
 */
class AgUiWireConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class StructuredEndpoint {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.progress("Searching");
            session.emit(new AiEvent.Progress("Ranking", 0.5));
            session.emit(new AiEvent.EntityStart("Trip", "{}"));
            session.emit(new AiEvent.StructuredField("city/name", "Paris", "string"));
            session.emit(new AiEvent.EntityComplete("Trip", Map.of("city/name", "Paris")));
            session.emit(new AiEvent.Citation("Paris", 0, 5, List.of("https://example.org")));
            session.sendMetadata("routing.decision", Map.of("model", "m1", "cost", 2));
            session.send("Done");
            session.complete("Done");
        }
    }

    @Test
    void everyStructuredPayloadMatchesTheAgUiSchemaShape() throws Exception {
        var frames = run("{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":["
                + "{\"id\":\"u1\",\"role\":\"user\",\"content\":\"Hi\"},"
                + "{\"id\":\"a1\",\"role\":\"assistant\",\"content\":\"Hello\"}]}");

        var types = frames.stream().map(Frame::type).toList();
        for (var required : List.of("MESSAGES_SNAPSHOT", "ACTIVITY_SNAPSHOT", "ACTIVITY_DELTA",
                "STATE_DELTA", "STATE_SNAPSHOT", "CUSTOM")) {
            assertTrue(types.contains(required), required + " must be on the wire: " + types);
        }

        var activities = new HashSet<String>();
        for (var frame : frames) {
            var data = frame.data();
            switch (frame.type()) {
                case "MESSAGES_SNAPSHOT" -> {
                    var messages = data.get("messages");
                    assertTrue(messages.isArray(), "messages must be an array: " + data);
                    assertEquals(2, messages.size());
                    assertEquals("user", messages.get(0).get("role").stringValue());
                    assertEquals("Hello", messages.get(1).get("content").stringValue());
                }
                case "STATE_SNAPSHOT" -> {
                    var snapshot = data.get("snapshot");
                    assertTrue(snapshot != null && snapshot.isObject(),
                            "snapshot must carry the state structure: " + data);
                    assertEquals("Paris", snapshot.get("entity").get("city/name").stringValue());
                }
                case "STATE_DELTA" -> assertJsonPatch(data.get("delta"), data);
                case "ACTIVITY_SNAPSHOT" -> {
                    assertTrue(data.get("messageId").isString(), "messageId: " + data);
                    assertTrue(data.get("activityType").isString(), "activityType: " + data);
                    assertTrue(data.get("content").isObject(), "content must be an object: " + data);
                    activities.add(data.get("messageId").stringValue());
                }
                case "ACTIVITY_DELTA" -> {
                    assertTrue(data.get("activityType").isString(), "activityType: " + data);
                    assertTrue(activities.contains(data.get("messageId").stringValue()),
                            "a delta must patch an activity a prior snapshot established: " + data);
                    assertJsonPatch(data.get("patch"), data);
                }
                case "CUSTOM" -> {
                    assertTrue(data.get("name").isString(), "name: " + data);
                    assertTrue(data.has("value"), "value is required: " + data);
                }
                default -> {
                    // Other events carry scalar fields only.
                }
            }
        }

        var citation = custom(frames, "citation");
        assertTrue(citation.isObject(), "citation value must be structured: " + citation);
        assertEquals("https://example.org", citation.get("sources").get(0).stringValue());
        var routing = custom(frames, "routing.decision");
        assertTrue(routing.isObject(), "structured metadata must stay structured: " + routing);
        assertEquals(2, routing.get("cost").intValue());
    }

    private static void assertJsonPatch(JsonNode patch, JsonNode event) {
        assertTrue(patch != null && patch.isArray() && !patch.isEmpty(),
                "a JSON Patch array is required: " + event);
        for (var op : patch) {
            assertTrue(op.isObject() && op.get("op").isString() && op.get("path").isString(),
                    "each operation needs op and path: " + event);
            var path = op.get("path").stringValue();
            assertTrue(path.isEmpty() || path.startsWith("/"), "JSON Pointer path: " + event);
        }
    }

    private static JsonNode custom(List<Frame> frames, String name) {
        return frames.stream()
                .filter(f -> "CUSTOM".equals(f.type()) && name.equals(f.data().get("name").stringValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CUSTOM " + name))
                .data().get("value");
    }

    private record Frame(String type, JsonNode data) {
    }

    private static List<Frame> run(String body) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(request.getMethod()).thenReturn("POST");
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        var output = new StringWriter();
        var servletResponse = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(response.getResponse()).thenReturn(servletResponse);
        when(servletResponse.getWriter()).thenReturn(new PrintWriter(output));

        var endpoint = new StructuredEndpoint();
        var handler = new AgUiHandler(endpoint,
                StructuredEndpoint.class.getMethod("onRun", RunContext.class, StreamingSession.class));
        // handlePost joins the run thread, so the stream is complete on return.
        handler.onRequest(resource);

        var frames = new ArrayList<Frame>();
        String type = null;
        for (var line : output.toString().split("\n")) {
            if (line.startsWith("event: ")) {
                type = line.substring(7).trim();
            } else if (line.startsWith("data: ") && type != null) {
                frames.add(new Frame(type, MAPPER.readTree(line.substring(6))));
                type = null;
            }
        }
        return frames;
    }
}
