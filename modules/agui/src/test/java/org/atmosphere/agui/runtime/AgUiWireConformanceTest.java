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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    public static class ToolStepReasoningEndpoint {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.send("Checking");
            session.emit(new AiEvent.AgentStep("plan", "Planning", Map.of()));
            session.emit(new AiEvent.ReasoningDelta("think", false));
            session.emit(new AiEvent.ReasoningComplete(null));
            session.emit(new AiEvent.ToolStart("get_weather", Map.of("city", "Paris")));
            session.emit(new AiEvent.ToolResult("get_weather", Map.of("tempC", 22)));
            session.complete();
        }
    }

    public static class FailingEndpoint {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.error(new IllegalStateException());
        }
    }

    /**
     * Field names and types of the events whose payloads the AG-UI 1.0 schema
     * ({@code @ag-ui/core} 1.0.2 {@code EventSchema}) fixes: a spec-strict
     * client rejects a TOOL_CALL_START without {@code toolCallName}, a
     * TOOL_CALL_RESULT without {@code messageId} and {@code content}, a step
     * event without {@code stepName}, a numeric RUN_ERROR {@code code}, a
     * snapshot message without {@code id}, and reasoning events without the
     * {@code messageId} / {@code role} the schema requires.
     */
    @Test
    void eventPayloadsUseTheAgUiSchemaFieldNames() throws Exception {
        var frames = run(ToolStepReasoningEndpoint.class, "{\"threadId\":\"t1\",\"runId\":\"r1\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Weather?\"},"
                + "{\"id\":\"a1\",\"role\":\"assistant\",\"content\":\"Hi\"}]}");

        var snapshot = frame(frames, "MESSAGES_SNAPSHOT").get("messages");
        for (var message : snapshot) {
            assertTrue(message.get("id") != null && message.get("id").isString()
                    && !message.get("id").stringValue().isEmpty(),
                    "every snapshot message needs a string id: " + snapshot);
        }
        assertEquals("a1", snapshot.get(1).get("id").stringValue(), "a client id is kept: " + snapshot);

        var toolStart = frame(frames, "TOOL_CALL_START");
        assertEquals("get_weather", text(toolStart, "toolCallName"), "TOOL_CALL_START: " + toolStart);
        assertFalse(toolStart.has("name"), "TOOL_CALL_START has no name field: " + toolStart);

        var toolResult = frame(frames, "TOOL_CALL_RESULT");
        assertEquals(text(toolStart, "toolCallId"), text(toolResult, "toolCallId"),
                "TOOL_CALL_RESULT: " + toolResult);
        assertTrue(toolResult.get("content") != null && toolResult.get("content").isString(),
                "TOOL_CALL_RESULT.content must be a string: " + toolResult);
        assertTrue(toolResult.get("content").stringValue().contains("22"), "content: " + toolResult);
        var resultMessageId = text(toolResult, "messageId");
        assertFalse(resultMessageId.isEmpty(), "TOOL_CALL_RESULT.messageId: " + toolResult);
        assertFalse(toolResult.has("result"), "TOOL_CALL_RESULT has no result field: " + toolResult);
        var textMessageIds = frames.stream().filter(f -> "TEXT_MESSAGE_START".equals(f.type()))
                .map(f -> text(f.data(), "messageId")).toList();
        assertFalse(textMessageIds.contains(resultMessageId),
                "the tool message id must not collide with a text message: " + toolResult);

        for (var type : List.of("STEP_STARTED", "STEP_FINISHED")) {
            var step = frame(frames, type);
            assertEquals("plan", text(step, "stepName"), type + ": " + step);
            assertFalse(step.has("stepId") || step.has("name"), type + " carries stepName only: " + step);
        }

        var reasoningStart = frame(frames, "REASONING_START");
        var reasoningEnd = frame(frames, "REASONING_END");
        assertFalse(text(reasoningStart, "messageId").isEmpty(), "REASONING_START: " + reasoningStart);
        assertEquals(text(reasoningStart, "messageId"), text(reasoningEnd, "messageId"),
                "REASONING_END closes the span REASONING_START opened: " + reasoningEnd);
        var reasoningMessage = frame(frames, "REASONING_MESSAGE_START");
        assertEquals("reasoning", text(reasoningMessage, "role"), "REASONING_MESSAGE_START: " + reasoningMessage);

        var error = frame(run(FailingEndpoint.class, "{\"threadId\":\"t2\",\"runId\":\"r2\"}"), "RUN_ERROR");
        assertTrue(error.get("message") != null && error.get("message").isString(),
                "RUN_ERROR.message is required even when the exception has none: " + error);
        assertTrue(!error.has("code") || error.get("code").isString(),
                "RUN_ERROR.code is an optional string: " + error);
    }

    @Test
    void runErrorCodeIsTheStringCode() throws Exception {
        var mapper = new org.atmosphere.agui.event.AgUiEventMapper();
        var json = MAPPER.readTree(MAPPER.writeValueAsString(
                mapper.toAgUi(new AiEvent.Error("slow down", "rate_limit", true)).get(0)));
        assertEquals("rate_limit", text(json, "code"), "RUN_ERROR.code: " + json);
    }

    private static JsonNode frame(List<Frame> frames, String type) {
        return frames.stream().filter(f -> type.equals(f.type())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " frame: "
                        + frames.stream().map(Frame::type).toList()))
                .data();
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isString()) {
            throw new AssertionError(field + " must be a string: " + node);
        }
        return value.stringValue();
    }

    @Test
    void everyStructuredPayloadMatchesTheAgUiSchemaShape() throws Exception {
        var frames = run(StructuredEndpoint.class, "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":["
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

    private static List<Frame> run(Class<?> endpointType, String body) throws Exception {
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

        var endpoint = endpointType.getDeclaredConstructor().newInstance();
        var handler = new AgUiHandler(endpoint,
                endpointType.getMethod("onRun", RunContext.class, StreamingSession.class));
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
