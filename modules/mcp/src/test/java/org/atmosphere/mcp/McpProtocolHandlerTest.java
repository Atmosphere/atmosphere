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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpServer;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.protocol.McpMessage;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.atmosphere.mcp.runtime.McpSession;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the MCP protocol handler — initialize, tools, resources, prompts.
 */
public class McpProtocolHandlerTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private McpProtocolHandler handler;
    private AtmosphereResource resource;

    // ── Test MCP Server class ────────────────────────────────────────────

    @McpServer(name = "test-server", version = "1.0.0", path = "/mcp")
    public static class TestMcpServer {

        @McpTool(name = "greet", description = "Greet a person")
        public String greet(
                @McpParam(name = "name", description = "Person's name") String name
        ) {
            return "Hello, " + name + "!";
        }

        @McpTool(name = "add", description = "Add two numbers")
        public int add(
                @McpParam(name = "a", description = "First number") int a,
                @McpParam(name = "b", description = "Second number") int b
        ) {
            return a + b;
        }

        @McpTool(name = "failing_tool", description = "A tool that always fails")
        public String failingTool() {
            throw new RuntimeException("Something went wrong");
        }

        @McpResource(uri = "test://data/status", name = "Status",
                description = "Server status", mimeType = "application/json")
        public String status() {
            return "{\"status\":\"ok\"}";
        }

        @McpTool(name = "greet_optional", description = "Greet with optional title")
        public String greetOptional(
                @McpParam(name = "name", description = "Person's name") String name,
                @McpParam(name = "title", description = "Optional title", required = false) String title
        ) {
            return title != null ? "Hello, " + title + " " + name + "!" : "Hello, " + name + "!";
        }

        @McpPrompt(name = "analyze", description = "Analyze data")
        public List<McpMessage> analyze(
                @McpParam(name = "topic", description = "Topic to analyze") String topic
        ) {
            return List.of(
                    McpMessage.system("You are a data analyst."),
                    McpMessage.user("Analyze: " + topic)
            );
        }
    }

    @BeforeEach
    public void setUp() {
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());
        var config = mock(AtmosphereConfig.class);
        handler = new McpProtocolHandler("test-server", "1.0.0", registry, config);

        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("test-uuid");
    }

    // ── Initialize ───────────────────────────────────────────────────────

    @Test
    public void testInitialize() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test-client","version":"0.1.0"},
                    "capabilities":{}
                }}""";

        var response = handler.handleMessage(resource, request);
        assertNotNull(response);

        var node = mapper.readTree(response);
        assertEquals("2.0", node.get("jsonrpc").asText());
        assertEquals(1, node.get("id").asInt());

        var result = node.get("result");
        assertNotNull(result);
        assertEquals("2025-03-26", result.get("protocolVersion").asText());
        assertEquals("test-server", result.get("serverInfo").get("name").asText());
        assertEquals("1.0.0", result.get("serverInfo").get("version").asText());

        // Should report tool and resource capabilities
        assertTrue(result.get("capabilities").has("tools"));
        assertTrue(result.get("capabilities").has("resources"));
        assertTrue(result.get("capabilities").has("prompts"));
    }

    @Test
    public void testInitializedNotification() {
        var notification = """
                {"jsonrpc":"2.0","method":"notifications/initialized"}""";

        var response = handler.handleMessage(resource, notification);
        assertNull(response, "Notifications should not produce a response");
    }

    // ── Ping ─────────────────────────────────────────────────────────────

    @Test
    public void testPing() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":2,"method":"ping"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertEquals(2, node.get("id").asInt());
        assertNotNull(node.get("result"));
    }

    // ── Tools ────────────────────────────────────────────────────────────

    @Test
    public void testToolsList() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":3,"method":"tools/list"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var tools = node.get("result").get("tools");
        assertTrue(tools.isArray());
        assertEquals(4, tools.size());

        // Find the greet tool
        JsonNode greetTool = null;
        for (var tool : tools) {
            if ("greet".equals(tool.get("name").asText())) {
                greetTool = tool;
                break;
            }
        }
        assertNotNull(greetTool, "greet tool should be listed");
        assertEquals("Greet a person", greetTool.get("description").asText());

        // Check input schema
        var schema = greetTool.get("inputSchema");
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("properties").has("name"));
        assertEquals("string", schema.get("properties").get("name").get("type").asText());
    }

    @Test
    public void testToolsCallGreet() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                    "name":"greet",
                    "arguments":{"name":"World"}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var result = node.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("Hello, World!", result.get("content").get(0).get("text").asText());
    }

    @Test
    public void testToolsCallAdd() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
                    "name":"add",
                    "arguments":{"a":3,"b":7}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var result = node.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("10", result.get("content").get(0).get("text").asText());
    }

    @Test
    public void testToolsCallUnknown() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
                    "name":"nonexistent"
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertNotNull(node.get("error"));
        assertEquals(-32601, node.get("error").get("code").asInt());
    }

    @Test
    public void testToolsCallFailure() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
                    "name":"failing_tool"
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var result = node.get("result");
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("Something went wrong"));
    }

    // ── Resources ────────────────────────────────────────────────────────

    @Test
    public void testResourcesList() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":8,"method":"resources/list"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var resources = node.get("result").get("resources");
        assertTrue(resources.isArray());
        assertEquals(1, resources.size());
        assertEquals("test://data/status", resources.get(0).get("uri").asText());
        assertEquals("application/json", resources.get(0).get("mimeType").asText());
    }

    @Test
    public void testResourcesRead() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":9,"method":"resources/read","params":{
                    "uri":"test://data/status"
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var contents = node.get("result").get("contents");
        assertEquals("{\"status\":\"ok\"}", contents.get(0).get("text").asText());
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    @Test
    public void testPromptsList() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":10,"method":"prompts/list"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var prompts = node.get("result").get("prompts");
        assertEquals(1, prompts.size());
        assertEquals("analyze", prompts.get(0).get("name").asText());
        assertTrue(prompts.get(0).has("arguments"));
    }

    @Test
    public void testPromptsGet() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":11,"method":"prompts/get","params":{
                    "name":"analyze",
                    "arguments":{"topic":"sales data"}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var result = node.get("result");
        var messages = result.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertTrue(messages.get(1).get("content").get("text").asText().contains("sales data"));
    }

    // ── Error handling ───────────────────────────────────────────────────

    @Test
    public void testInvalidJson() throws Exception {
        var response = handler.handleMessage(resource, "not json");
        var node = mapper.readTree(response);
        assertNotNull(node.get("error"));
        assertEquals(-32700, node.get("error").get("code").asInt());
    }

    @Test
    public void testUnknownMethod() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":99,"method":"unknown/method"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertNotNull(node.get("error"));
        assertEquals(-32601, node.get("error").get("code").asInt());
    }

    // ── Registry ─────────────────────────────────────────────────────────

    @Test
    public void testRegistryScan() {
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());

        assertEquals(4, registry.tools().size());
        assertEquals(1, registry.resources().size());
        assertEquals(1, registry.prompts().size());

        var greet = registry.tool("greet");
        assertTrue(greet.isPresent());
        assertEquals(1, greet.get().params().size());
        assertEquals("name", greet.get().params().getFirst().name());
    }

    @Test
    public void testInputSchema() {
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());

        var addTool = registry.tool("add").orElseThrow();
        var schema = McpRegistry.inputSchema(addTool);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) schema.get("properties");
        assertEquals("integer", ((Map<?, ?>) props.get("a")).get("type"));
        assertEquals("integer", ((Map<?, ?>) props.get("b")).get("type"));
    }

    // ── Dynamic Tool Registration ────────────────────────────────────────

    @Test
    public void testDynamicToolRegistration() {
        var registry = new McpRegistry();
        registry.registerTool("dynamic_hello", "Say hello dynamically",
                List.of(new McpRegistry.ParamEntry("name", "Person's name", true, String.class)),
                args -> "Hello, " + args.get("name") + "!");

        assertTrue(registry.tool("dynamic_hello").isPresent());
        assertTrue(registry.tool("dynamic_hello").get().isDynamic());
        assertEquals(1, registry.tools().size());
    }

    @Test
    public void testDynamicToolCall() throws Exception {
        var registry = new McpRegistry();
        registry.registerTool("upper", "Convert to uppercase",
                List.of(new McpRegistry.ParamEntry("text", "Input text", true, String.class)),
                args -> ((String) args.get("text")).toUpperCase());
        var testHandler = new McpProtocolHandler("test", "1.0.0", registry, mock(AtmosphereConfig.class));

        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"upper",
                    "arguments":{"text":"hello world"}
                }}""";

        var node = mapper.readTree(testHandler.handleMessage(resource, request));
        var result = node.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("HELLO WORLD", result.get("content").get(0).get("text").asText());
    }

    @Test
    public void testDynamicToolNoParams() throws Exception {
        var registry = new McpRegistry();
        registry.registerTool("version", "Get version", args -> "4.0.0");
        var testHandler = new McpProtocolHandler("test", "1.0.0", registry, mock(AtmosphereConfig.class));

        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"version"}}""";

        var node = mapper.readTree(testHandler.handleMessage(resource, request));
        assertEquals("4.0.0", node.get("result").get("content").get(0).get("text").asText());
    }

    @Test
    public void testRemoveTool() {
        var registry = new McpRegistry();
        registry.registerTool("temp", "Temporary tool", args -> "temp");
        assertTrue(registry.tool("temp").isPresent());

        assertTrue(registry.removeTool("temp"));
        assertTrue(registry.tool("temp").isEmpty());
        assertFalse(registry.removeTool("nonexistent"));
    }

    @Test
    public void testDynamicResourceRegistration() throws Exception {
        var registry = new McpRegistry();
        registry.registerResource("app://status", "Status", "App status",
                "application/json", args -> "{\"up\":true}");
        var testHandler = new McpProtocolHandler("test", "1.0.0", registry, mock(AtmosphereConfig.class));

        var request = """
                {"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"app://status"}}""";

        var node = mapper.readTree(testHandler.handleMessage(resource, request));
        var contents = node.get("result").get("contents");
        assertEquals("{\"up\":true}", contents.get(0).get("text").asText());
    }

    @Test
    public void testMixedAnnotationAndDynamic() throws Exception {
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());
        registry.registerTool("dynamic_tool", "A dynamic tool", args -> "dynamic result");

        assertEquals(5, registry.tools().size()); // 4 annotation + 1 dynamic

        var testHandler = new McpProtocolHandler("test", "1.0.0", registry, mock(AtmosphereConfig.class));
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""";
        var node = mapper.readTree(testHandler.handleMessage(resource, request));
        assertEquals(5, node.get("result").get("tools").size());
    }

    // ── Resource Subscribe / Unsubscribe ─────────────────────────────────

    @Test
    public void testResourcesSubscribe() throws Exception {
        // First initialize so a session exists
        var init = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test","version":"0.1"},
                    "capabilities":{}
                }}""";
        handler.handleMessage(resource, init);

        var request = """
                {"jsonrpc":"2.0","id":20,"method":"resources/subscribe","params":{
                    "uri":"test://data/status"
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertNotNull(node.get("result"));
        assertNull(node.get("error"));
    }

    @Test
    public void testResourcesSubscribeMissingUri() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":21,"method":"resources/subscribe","params":{}}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertEquals(-32602, node.get("error").get("code").asInt());
    }

    @Test
    public void testResourcesSubscribeUnknownResource() throws Exception {
        var init = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test","version":"0.1"},
                    "capabilities":{}
                }}""";
        handler.handleMessage(resource, init);

        var request = """
                {"jsonrpc":"2.0","id":22,"method":"resources/subscribe","params":{
                    "uri":"test://nonexistent"
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertNotNull(node.get("error"));
        assertEquals(-32601, node.get("error").get("code").asInt());
    }

    @Test
    public void testResourcesUnsubscribe() throws Exception {
        var init = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test","version":"0.1"},
                    "capabilities":{}
                }}""";
        handler.handleMessage(resource, init);

        // Subscribe first
        var sub = """
                {"jsonrpc":"2.0","id":23,"method":"resources/subscribe","params":{
                    "uri":"test://data/status"
                }}""";
        handler.handleMessage(resource, sub);

        // Unsubscribe
        var unsub = """
                {"jsonrpc":"2.0","id":24,"method":"resources/unsubscribe","params":{
                    "uri":"test://data/status"
                }}""";
        var node = mapper.readTree(handler.handleMessage(resource, unsub));
        assertNotNull(node.get("result"));
        assertNull(node.get("error"));
    }

    @Test
    public void testResourcesUnsubscribeMissingUri() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":25,"method":"resources/unsubscribe"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertEquals(-32602, node.get("error").get("code").asInt());
    }

    // ── Required Parameter Validation ────────────────────────────────────

    @Test
    public void testToolCallMissingRequiredParam() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":30,"method":"tools/call","params":{
                    "name":"greet",
                    "arguments":{}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertEquals(-32602, node.get("error").get("code").asInt());
        assertTrue(node.get("error").get("message").asText().contains("Missing required parameter"));
    }

    @Test
    public void testToolCallOptionalParamOmitted() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":31,"method":"tools/call","params":{
                    "name":"greet_optional",
                    "arguments":{"name":"Alice"}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var result = node.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("Hello, Alice!", result.get("content").get(0).get("text").asText());
    }

    @Test
    public void testToolCallOptionalParamProvided() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":32,"method":"tools/call","params":{
                    "name":"greet_optional",
                    "arguments":{"name":"Alice","title":"Dr."}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var result = node.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("Hello, Dr. Alice!", result.get("content").get(0).get("text").asText());
    }

    @Test
    public void testPromptGetMissingRequiredParam() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":33,"method":"prompts/get","params":{
                    "name":"analyze",
                    "arguments":{}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertEquals(-32602, node.get("error").get("code").asInt());
        assertTrue(node.get("error").get("message").asText().contains("Missing required parameter"));
    }

    // ── Session Subscription Tracking ────────────────────────────────────

    @Test
    public void testSessionSubscriptionTracking() {
        var session = new McpSession();
        assertFalse(session.isSubscribed("test://data"));
        assertTrue(session.subscriptions().isEmpty());

        session.addSubscription("test://data");
        assertTrue(session.isSubscribed("test://data"));
        assertEquals(1, session.subscriptions().size());

        session.addSubscription("test://other");
        assertEquals(2, session.subscriptions().size());

        session.removeSubscription("test://data");
        assertFalse(session.isSubscribed("test://data"));
        assertTrue(session.isSubscribed("test://other"));
        assertEquals(1, session.subscriptions().size());

        session.removeSubscription("test://other");
        assertTrue(session.subscriptions().isEmpty());
    }

    @Test
    public void testSessionSubscriptionIdempotent() {
        var session = new McpSession();
        session.addSubscription("test://data");
        session.addSubscription("test://data");
        assertEquals(1, session.subscriptions().size());

        session.removeSubscription("test://nonexistent");
        assertEquals(1, session.subscriptions().size());
    }

    // ── Initialize advertises subscribe capability ───────────────────────

    @Test
    public void testInitializeAdvertisesSubscribeCapability() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":40,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test","version":"0.1"},
                    "capabilities":{}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var capabilities = node.get("result").get("capabilities");
        var resources = capabilities.get("resources");
        assertTrue(resources.get("subscribe").asBoolean());
    }
}
