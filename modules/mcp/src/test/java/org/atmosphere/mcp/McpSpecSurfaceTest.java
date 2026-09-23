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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpComplete;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.protocol.McpMessage;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the 2025-06-18+ server surface that used to be missing: tool
 * {@code annotations}, {@code outputSchema}/{@code structuredContent},
 * {@code resources/templates/list} with template-bound reads, and
 * {@code completion/complete} — on both the session and the stateless dialect.
 */
public class McpSpecSurfaceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    public enum Tone { FORMAL, FRIENDLY, FUNNY }

    public record Weather(String city, int celsius) { }

    public static class Server {

        @McpTool(name = "lookup", description = "Read-only lookup",
                readOnlyHint = true, idempotentHint = true, openWorldHint = false)
        public String lookup(@McpParam(name = "key") String key) {
            return "v:" + key;
        }

        @McpTool(name = "plain", description = "No hints")
        public String plain() {
            return "p";
        }

        @McpTool(name = "weather", description = "Structured weather", outputType = Weather.class)
        public Weather weather(@McpParam(name = "city") String city) {
            return new Weather(city, 21);
        }

        @McpTool(name = "broken", description = "Violates its schema", outputType = Weather.class)
        public Map<String, Object> broken() {
            return Map.of("city", "Paris");
        }

        @McpTool(name = "jsonText", description = "Schema-typed JSON string", outputType = Weather.class)
        public String jsonText() {
            return "{\"city\":\"Oslo\",\"celsius\":3}";
        }

        @McpTool(name = "names", description = "Returns an array")
        public List<String> names() {
            return List.of("a", "b");
        }

        @McpResource(uri = "test://static", name = "Static", mimeType = "text/plain")
        public String staticResource() {
            return "static";
        }

        @McpResource(uri = "test://rooms/{roomId}/history", name = "Room History",
                description = "History of one room", mimeType = "text/plain")
        public String roomHistory(@McpParam(name = "roomId") String roomId) {
            return "history of " + roomId;
        }

        @McpPrompt(name = "greet", description = "Greeting")
        public List<McpMessage> greet(@McpParam(name = "name") String name,
                                      @McpParam(name = "tone") Tone tone) {
            return List.of(McpMessage.user(tone + " hello " + name));
        }

        @McpComplete(prompt = "greet", argument = "name")
        public List<String> names(String prefix, Map<String, String> context) {
            var out = new ArrayList<String>();
            for (var n : List.of("Alice", "Alex", "Bob")) {
                if (n.startsWith(prefix)) {
                    out.add(context.containsKey("tone") ? n + "/" + context.get("tone") : n);
                }
            }
            return out;
        }

        @McpComplete(resource = "test://rooms/{roomId}/history", argument = "roomId")
        public List<String> rooms(String prefix) {
            var out = new ArrayList<String>();
            for (int i = 0; i < 150; i++) {
                out.add("room-" + i);
            }
            return out;
        }
    }

    private McpProtocolHandler handler;
    private AtmosphereResource resource;

    @BeforeEach
    void setUp() {
        var registry = new McpRegistry();
        registry.scan(new Server());
        handler = new McpProtocolHandler("spec", "1.0", registry, mock(AtmosphereConfig.class));
        resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("spec-uuid");
    }

    private static String stateless() {
        return """
                "_meta":{
                    "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                    "io.modelcontextprotocol/clientInfo":{"name":"c","version":"1"},
                    "io.modelcontextprotocol/clientCapabilities":{}
                }""";
    }

    /** Send {@code method} with {@code params} (JSON object body, no braces) on either dialect. */
    private JsonNode call(boolean statelessDialect, String method, String params) throws Exception {
        var body = params.isEmpty() ? "" : params;
        if (statelessDialect) {
            body = body.isEmpty() ? stateless() : body + "," + stateless();
        }
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{" + body + "}}";
        return mapper.readTree(handler.handleMessage(resource, req));
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (var n : array) {
            if (value.equals(n.get(field).stringValue())) {
                return n;
            }
        }
        return null;
    }

    // ── Tool annotations ─────────────────────────────────────────────────

    @Test
    void toolAnnotationsEmittedOnlyWhenDeclaredOnBothDialects() throws Exception {
        for (var stateless : new boolean[] {false, true}) {
            var tools = call(stateless, "tools/list", "").get("result").get("tools");
            var lookup = find(tools, "name", "lookup").get("annotations");
            assertNotNull(lookup, "hinted tool must carry annotations");
            assertTrue(lookup.get("readOnlyHint").asBoolean());
            assertTrue(lookup.get("idempotentHint").asBoolean());
            assertFalse(lookup.get("openWorldHint").asBoolean());
            assertNull(find(tools, "name", "plain").get("annotations"),
                    "a tool on spec defaults must not invent hints");
        }
    }

    @Test
    void programmaticAnnotationsOmitUnsetHints() throws Exception {
        var registry = new McpRegistry();
        registry.registerTool("rm", "delete", args -> "ok");
        registry.setToolAnnotations("rm", new McpRegistry.ToolAnnotations(null, true, null, null));
        var h = new McpProtocolHandler("s", "1", registry, mock(AtmosphereConfig.class));
        var tools = mapper.readTree(h.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                .get("result").get("tools");
        var ann = tools.get(0).get("annotations");
        assertEquals(1, ann.size());
        assertTrue(ann.get("destructiveHint").asBoolean());
    }

    // ── outputSchema / structuredContent ─────────────────────────────────

    @Test
    void outputSchemaAdvertisedFromRecordType() throws Exception {
        var tools = call(false, "tools/list", "").get("result").get("tools");
        var schema = find(tools, "name", "weather").get("outputSchema");
        assertNotNull(schema);
        assertEquals("object", schema.get("type").stringValue());
        assertEquals("string", schema.get("properties").get("city").get("type").stringValue());
        assertEquals("integer", schema.get("properties").get("celsius").get("type").stringValue());
        assertEquals(2, schema.get("required").size());
        assertNull(find(tools, "name", "plain").get("outputSchema"));
    }

    @Test
    void structuredContentConformsOnBothDialects() throws Exception {
        for (var stateless : new boolean[] {false, true}) {
            var result = call(stateless, "tools/call",
                    "\"name\":\"weather\",\"arguments\":{\"city\":\"Lyon\"}").get("result");
            assertFalse(result.get("isError").asBoolean());
            assertEquals("Lyon", result.get("structuredContent").get("city").stringValue());
            assertEquals(21, result.get("structuredContent").get("celsius").asInt());
            assertTrue(result.get("content").get(0).get("text").stringValue().contains("Lyon"),
                    "serialized JSON text block kept for older clients");
        }
    }

    @Test
    void resultViolatingOutputSchemaIsAToolError() throws Exception {
        var result = call(false, "tools/call", "\"name\":\"broken\",\"arguments\":{}").get("result");
        assertTrue(result.get("isError").asBoolean());
        assertNull(result.get("structuredContent"));
        assertTrue(result.get("content").get(0).get("text").stringValue()
                .contains("missing required property 'celsius'"));
    }

    @Test
    void jsonObjectStringSatisfiesDeclaredSchema() throws Exception {
        var result = call(false, "tools/call", "\"name\":\"jsonText\",\"arguments\":{}").get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("Oslo", result.get("structuredContent").get("city").stringValue());
    }

    @Test
    void arrayResultIsNotStructuredContent() throws Exception {
        // structuredContent is a JSON object in the schema; an array must stay text-only.
        var result = call(false, "tools/call", "\"name\":\"names\",\"arguments\":{}").get("result");
        assertNull(result.get("structuredContent"));
        assertEquals("[\"a\",\"b\"]", result.get("content").get(0).get("text").stringValue());
    }

    @Test
    void nonObjectOutputTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> McpRegistry.outputSchema(List.class));
        var registry = new McpRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.setToolOutputSchema("x", Map.of("type", "array")));
    }

    // ── Resource templates ───────────────────────────────────────────────

    @Test
    void templatesListedSeparatelyFromConcreteResources() throws Exception {
        for (var stateless : new boolean[] {false, true}) {
            var resources = call(stateless, "resources/list", "").get("result").get("resources");
            assertNotNull(find(resources, "uri", "test://static"));
            assertNull(find(resources, "uri", "test://rooms/{roomId}/history"),
                    "a URI template is not a readable concrete resource");

            var result = call(stateless, "resources/templates/list", "").get("result");
            var templates = result.get("resourceTemplates");
            assertEquals(1, templates.size());
            var t = templates.get(0);
            assertEquals("test://rooms/{roomId}/history", t.get("uriTemplate").stringValue());
            assertEquals("Room History", t.get("name").stringValue());
            assertEquals("text/plain", t.get("mimeType").stringValue());
            if (stateless) {
                assertNotNull(result.get("cacheScope"), "templates list is cacheable");
            }
        }
    }

    @Test
    void readingAConcreteUriBindsTheTemplateVariable() throws Exception {
        for (var stateless : new boolean[] {false, true}) {
            var result = call(stateless, "resources/read",
                    "\"uri\":\"test://rooms/lobby%20east/history\"").get("result");
            assertNotNull(result, "stateless=" + stateless);
            var content = result.get("contents").get(0);
            assertEquals("history of lobby east", content.get("text").stringValue());
            assertEquals("test://rooms/lobby%20east/history", content.get("uri").stringValue());
        }
    }

    @Test
    void templateVariablesCannotEscapeTheirSegment() throws Exception {
        for (var uri : List.of("test://rooms/../history", "test://rooms/a%2Fb/history",
                "test://rooms/a/b/history", "test://rooms//history")) {
            var node = call(false, "resources/read", "\"uri\":\"" + uri + "\"");
            assertNotNull(node.get("error"), "must reject " + uri);
        }
    }

    @Test
    void statelessResourceNotFoundCarriesUriInErrorData() throws Exception {
        // SEP-2164: the not-found error SHOULD echo the requested uri in data.
        var node = call(true, "resources/read", "\"uri\":\"test://nope\"");
        assertEquals(-32602, node.get("error").get("code").asInt());
        assertEquals("test://nope", node.get("error").get("data").get("uri").stringValue());
    }

    @Test
    void subscribeAcceptsAUriMatchingATemplate() throws Exception {
        var node = call(false, "resources/subscribe", "\"uri\":\"test://rooms/r1/history\"");
        assertNull(node.get("error"));
    }

    // ── completion/complete ──────────────────────────────────────────────

    @Test
    void completionsCapabilityAdvertisedOnBothDialects() throws Exception {
        var init = call(false, "initialize",
                "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"}");
        assertNotNull(init.get("result").get("capabilities").get("completions"));
        var discover = call(false, "server/discover", "");
        assertNotNull(discover.get("result").get("capabilities").get("completions"));
    }

    @Test
    void completionsCapabilityAbsentWithoutASource() throws Exception {
        var registry = new McpRegistry();
        registry.registerPrompt("p", "d", List.of(
                new McpRegistry.ParamEntry("q", "", true, String.class)), args -> "x");
        var h = new McpProtocolHandler("s", "1", registry, mock(AtmosphereConfig.class));
        var init = mapper.readTree(h.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{},
                  "clientInfo":{"name":"c","version":"1"}}}"""));
        assertNull(init.get("result").get("capabilities").get("completions"));
    }

    @Test
    void explicitPromptCompletionReceivesContextOnBothDialects() throws Exception {
        for (var stateless : new boolean[] {false, true}) {
            var result = call(stateless, "completion/complete",
                    "\"ref\":{\"type\":\"ref/prompt\",\"name\":\"greet\"},"
                            + "\"argument\":{\"name\":\"name\",\"value\":\"Al\"},"
                            + "\"context\":{\"arguments\":{\"tone\":\"FUNNY\"}}").get("result");
            var completion = result.get("completion");
            assertEquals(2, completion.get("values").size());
            assertEquals("Alice/FUNNY", completion.get("values").get(0).stringValue());
            assertEquals(2, completion.get("total").asInt());
            assertFalse(completion.get("hasMore").asBoolean());
        }
    }

    @Test
    void enumArgumentsCompleteAutomatically() throws Exception {
        var completion = call(false, "completion/complete",
                "\"ref\":{\"type\":\"ref/prompt\",\"name\":\"greet\"},"
                        + "\"argument\":{\"name\":\"tone\",\"value\":\"f\"}")
                .get("result").get("completion");
        var values = new ArrayList<String>();
        completion.get("values").forEach(v -> values.add(v.stringValue()));
        assertEquals(List.of("FORMAL", "FRIENDLY", "FUNNY"), values);
    }

    @Test
    void resourceCompletionIsCappedAtOneHundred() throws Exception {
        var completion = call(false, "completion/complete",
                "\"ref\":{\"type\":\"ref/resource\",\"uri\":\"test://rooms/{roomId}/history\"},"
                        + "\"argument\":{\"name\":\"roomId\",\"value\":\"\"}")
                .get("result").get("completion");
        assertEquals(100, completion.get("values").size());
        assertEquals(150, completion.get("total").asInt());
        assertTrue(completion.get("hasMore").asBoolean());
    }

    @Test
    void unknownReferenceOrMalformedRequestIsInvalidParams() throws Exception {
        for (var params : List.of(
                "\"ref\":{\"type\":\"ref/prompt\",\"name\":\"nope\"},\"argument\":{\"name\":\"a\",\"value\":\"\"}",
                "\"ref\":{\"type\":\"ref/resource\",\"uri\":\"test://static\"},\"argument\":{\"name\":\"a\",\"value\":\"\"}",
                "\"ref\":{\"type\":\"ref/other\",\"name\":\"x\"},\"argument\":{\"name\":\"a\",\"value\":\"\"}",
                "\"argument\":{\"name\":\"a\"}")) {
            var node = call(false, "completion/complete", params);
            assertEquals(-32602, node.get("error").get("code").asInt(), params);
        }
    }

    @Test
    void completeMethodWithWrongSignatureFailsAtScan() {
        var registry = new McpRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.scan(new Object() {
            @McpComplete(prompt = "p", resource = "r", argument = "a")
            public List<String> both(String v) {
                return List.of();
            }
        }));
    }
}
