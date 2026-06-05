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
package org.atmosphere.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.protocol.McpInputContext;
import org.atmosphere.mcp.protocol.McpInputRequiredException;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for SEP-2322 multi-round-trip on the stateless dialect: a tool that
 * injects {@link McpInputContext} and throws {@link McpInputRequiredException}
 * yields an {@code InputRequiredResult}; the client retries with
 * {@code inputResponses} + the opaque {@code requestState} and the tool resumes.
 * {@code requestState} accumulates responses across rounds with no server state.
 */
public class McpMrtrTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private McpProtocolHandler handler;
    private AtmosphereResource resource;

    public static class MrtrServer {
        @McpTool(name = "confirm_book", description = "Book with confirmation")
        public String book(@McpParam(name = "date") String date, McpInputContext input) {
            if (!input.has("confirm")) {
                throw new McpInputRequiredException(Map.of(
                        "confirm", Map.of("method", "elicitation/create",
                                "params", Map.of("message", "Confirm booking for " + date + "?"))));
            }
            return "Booked " + date + " confirm=" + input.get("confirm");
        }

        @McpTool(name = "two_step", description = "Requests two inputs in turn")
        public String twoStep(McpInputContext input) {
            if (!input.has("a")) {
                throw new McpInputRequiredException(Map.of("a",
                        Map.of("method", "elicitation/create", "params", Map.of("message", "a?"))));
            }
            if (!input.has("b")) {
                throw new McpInputRequiredException(Map.of("b",
                        Map.of("method", "elicitation/create", "params", Map.of("message", "b?"))));
            }
            return "done a=" + input.get("a") + " b=" + input.get("b");
        }
    }

    @BeforeEach
    public void setUp() {
        var registry = new McpRegistry();
        registry.scan(new MrtrServer());
        handler = new McpProtocolHandler("test-server", "1.0.0", registry, mock(AtmosphereConfig.class));
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("mrtr-uuid");
    }

    private static String meta() {
        return """
                "_meta":{
                    "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                    "io.modelcontextprotocol/clientInfo":{"name":"c","version":"1"},
                    "io.modelcontextprotocol/clientCapabilities":{}
                }""";
    }

    private JsonNode call(String json) {
        return mapper.readTree(handler.handleMessage(resource, json));
    }

    @Test
    public void testToolPausesForInput() {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"confirm_book\",\"arguments\":{\"date\":\"2026-07-01\"}," + meta() + "}}";
        var result = call(req).get("result");
        assertNotNull(result);
        assertEquals("input_required", result.get("resultType").stringValue());
        assertTrue(result.get("inputRequests").has("confirm"), "carries the handler's input request");
        assertEquals("elicitation/create",
                result.get("inputRequests").get("confirm").get("method").stringValue());
        assertNotNull(result.get("requestState"), "opaque requestState must be present");
    }

    @Test
    public void testResumeCompletesCall() {
        var first = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"confirm_book\",\"arguments\":{\"date\":\"2026-07-01\"}," + meta() + "}}");
        var state = first.get("result").get("requestState").stringValue();

        var retry = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"confirm_book\",\"arguments\":{\"date\":\"2026-07-01\"},"
                + "\"inputResponses\":{\"confirm\":\"yes\"},\"requestState\":\"" + state + "\"," + meta() + "}}");
        var result = retry.get("result");
        assertEquals("complete", result.get("resultType").stringValue());
        assertFalse(result.get("isError").asBoolean());
        assertEquals("Booked 2026-07-01 confirm=yes", result.get("content").get(0).get("text").stringValue());
    }

    @Test
    public void testMultiRoundAccumulationViaRequestState() {
        // Round 1: no input → asks for "a".
        var r1 = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"two_step\"," + meta() + "}}").get("result");
        assertEquals("input_required", r1.get("resultType").stringValue());
        assertTrue(r1.get("inputRequests").has("a"));
        var state1 = r1.get("requestState").stringValue();

        // Round 2: supply "a" → asks for "b". requestState must now carry "a".
        var r2 = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"two_step\",\"inputResponses\":{\"a\":\"alpha\"},\"requestState\":\""
                + state1 + "\"," + meta() + "}}").get("result");
        assertEquals("input_required", r2.get("resultType").stringValue());
        assertTrue(r2.get("inputRequests").has("b"));
        var state2 = r2.get("requestState").stringValue();

        // Round 3: supply "b"; the accumulated "a" comes from requestState, "b"
        // from this request → the tool sees both and completes.
        var r3 = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"two_step\",\"inputResponses\":{\"b\":\"beta\"},\"requestState\":\""
                + state2 + "\"," + meta() + "}}").get("result");
        assertEquals("complete", r3.get("resultType").stringValue());
        assertEquals("done a=alpha b=beta", r3.get("content").get(0).get("text").stringValue());
    }

    @Test
    public void testForgedRequestStateIsIgnoredNotFatal() {
        // A garbage (client-controlled) requestState must not crash the call; the
        // tool simply re-requests input (boundary safety).
        var result = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"confirm_book\",\"arguments\":{\"date\":\"x\"},"
                + "\"requestState\":\"!!!not-base64!!!\"," + meta() + "}}").get("result");
        assertEquals("input_required", result.get("resultType").stringValue());
        assertTrue(result.get("inputRequests").has("confirm"));
    }
}
