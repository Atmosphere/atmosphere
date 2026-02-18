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
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;

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

    @BeforeMethod
    public void setUp() {
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());
        handler = new McpProtocolHandler("test-server", "1.0.0", registry);

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
        assertEquals(node.get("jsonrpc").asText(), "2.0");
        assertEquals(node.get("id").asInt(), 1);

        var result = node.get("result");
        assertNotNull(result);
        assertEquals(result.get("protocolVersion").asText(), "2025-03-26");
        assertEquals(result.get("serverInfo").get("name").asText(), "test-server");
        assertEquals(result.get("serverInfo").get("version").asText(), "1.0.0");

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
        assertEquals(node.get("id").asInt(), 2);
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
        assertEquals(tools.size(), 3);

        // Find the greet tool
        JsonNode greetTool = null;
        for (var tool : tools) {
            if ("greet".equals(tool.get("name").asText())) {
                greetTool = tool;
                break;
            }
        }
        assertNotNull(greetTool, "greet tool should be listed");
        assertEquals(greetTool.get("description").asText(), "Greet a person");

        // Check input schema
        var schema = greetTool.get("inputSchema");
        assertEquals(schema.get("type").asText(), "object");
        assertTrue(schema.get("properties").has("name"));
        assertEquals(schema.get("properties").get("name").get("type").asText(), "string");
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
        assertEquals(result.get("content").get(0).get("text").asText(), "Hello, World!");
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
        assertEquals(result.get("content").get(0).get("text").asText(), "10");
    }

    @Test
    public void testToolsCallUnknown() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
                    "name":"nonexistent"
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertNotNull(node.get("error"));
        assertEquals(node.get("error").get("code").asInt(), -32601);
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
        assertEquals(resources.size(), 1);
        assertEquals(resources.get(0).get("uri").asText(), "test://data/status");
        assertEquals(resources.get(0).get("mimeType").asText(), "application/json");
    }

    @Test
    public void testResourcesRead() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":9,"method":"resources/read","params":{
                    "uri":"test://data/status"
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var contents = node.get("result").get("contents");
        assertEquals(contents.get(0).get("text").asText(), "{\"status\":\"ok\"}");
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    @Test
    public void testPromptsList() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":10,"method":"prompts/list"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var prompts = node.get("result").get("prompts");
        assertEquals(prompts.size(), 1);
        assertEquals(prompts.get(0).get("name").asText(), "analyze");
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
        assertEquals(messages.size(), 2);
        assertEquals(messages.get(0).get("role").asText(), "assistant");
        assertEquals(messages.get(1).get("role").asText(), "user");
        assertTrue(messages.get(1).get("content").get("text").asText().contains("sales data"));
    }

    // ── Error handling ───────────────────────────────────────────────────

    @Test
    public void testInvalidJson() throws Exception {
        var response = handler.handleMessage(resource, "not json");
        var node = mapper.readTree(response);
        assertNotNull(node.get("error"));
        assertEquals(node.get("error").get("code").asInt(), -32700);
    }

    @Test
    public void testUnknownMethod() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":99,"method":"unknown/method"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertNotNull(node.get("error"));
        assertEquals(node.get("error").get("code").asInt(), -32601);
    }

    // ── Registry ─────────────────────────────────────────────────────────

    @Test
    public void testRegistryScan() {
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());

        assertEquals(registry.tools().size(), 3);
        assertEquals(registry.resources().size(), 1);
        assertEquals(registry.prompts().size(), 1);

        var greet = registry.tool("greet");
        assertTrue(greet.isPresent());
        assertEquals(greet.get().params().size(), 1);
        assertEquals(greet.get().params().getFirst().name(), "name");
    }

    @Test
    public void testInputSchema() {
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());

        var addTool = registry.tool("add").orElseThrow();
        var schema = McpRegistry.inputSchema(addTool);
        assertEquals(schema.get("type"), "object");

        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) schema.get("properties");
        assertEquals(((Map<?, ?>) props.get("a")).get("type"), "integer");
        assertEquals(((Map<?, ?>) props.get("b")).get("type"), "integer");
    }
}
