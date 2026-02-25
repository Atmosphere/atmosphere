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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpServer;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;

import java.util.Map;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for @McpTool injectable parameter support: Broadcaster, AtmosphereConfig,
 * BroadcasterFactory, and AtmosphereFramework injection.
 */
public class McpToolInjectionTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private McpProtocolHandler handler;
    private AtmosphereResource resource;
    private Broadcaster mockBroadcaster;
    private AtmosphereConfig mockConfig;
    private BroadcasterFactory mockBroadcasterFactory;
    private AtmosphereFramework mockFramework;

    // Capture values injected into tool methods
    static Broadcaster capturedBroadcaster;
    static AtmosphereConfig capturedConfig;
    static BroadcasterFactory capturedBroadcasterFactory;
    static AtmosphereFramework capturedFramework;

    @McpServer(name = "injection-test", version = "1.0.0")
    public static class InjectionMcpServer {

        @McpTool(name = "broadcast_message", description = "Broadcast a message to a topic")
        public String broadcastMessage(
                @McpParam(name = "message", description = "The message") String message,
                @McpParam(name = "topic", description = "The topic") String topic,
                Broadcaster broadcaster) {
            capturedBroadcaster = broadcaster;
            return "sent to " + topic;
        }

        @McpTool(name = "with_config", description = "Tool with config injection")
        public String withConfig(
                @McpParam(name = "key", description = "Config key") String key,
                AtmosphereConfig config) {
            capturedConfig = config;
            return "config:" + key;
        }

        @McpTool(name = "with_factory", description = "Tool with BroadcasterFactory")
        public String withFactory(
                @McpParam(name = "name", description = "A name") String name,
                BroadcasterFactory factory) {
            capturedBroadcasterFactory = factory;
            return "factory:" + name;
        }

        @McpTool(name = "with_framework", description = "Tool with AtmosphereFramework")
        public String withFramework(
                @McpParam(name = "action", description = "An action") String action,
                AtmosphereFramework framework) {
            capturedFramework = framework;
            return "framework:" + action;
        }

        @McpTool(name = "multi_inject", description = "Tool with multiple injectables")
        public Map<String, Object> multiInject(
                @McpParam(name = "question", description = "A question") String question,
                @McpParam(name = "topic", description = "Topic to broadcast to") String topic,
                Broadcaster broadcaster,
                AtmosphereConfig config) {
            capturedBroadcaster = broadcaster;
            capturedConfig = config;
            return Map.of("question", question, "topic", topic,
                    "hasBroadcaster", broadcaster != null,
                    "hasConfig", config != null);
        }
    }

    @BeforeEach
    public void setUp() {
        // Reset captured values
        capturedBroadcaster = null;
        capturedConfig = null;
        capturedBroadcasterFactory = null;
        capturedFramework = null;

        // Set up mocks
        mockBroadcaster = mock(Broadcaster.class);
        mockConfig = mock(AtmosphereConfig.class);
        mockBroadcasterFactory = mock(BroadcasterFactory.class);
        mockFramework = mock(AtmosphereFramework.class);

        when(mockConfig.getBroadcasterFactory()).thenReturn(mockBroadcasterFactory);
        when(mockConfig.framework()).thenReturn(mockFramework);
        when(mockBroadcasterFactory.lookup(anyString(), eq(true))).thenReturn(mockBroadcaster);

        var registry = new McpRegistry();
        registry.scan(new InjectionMcpServer());
        handler = new McpProtocolHandler("injection-test", "1.0.0", registry, mockConfig);

        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("test-uuid");
    }

    // ── Schema Tests ─────────────────────────────────────────────────────

    @Test
    public void testInjectableParamsNotInSchema() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var tools = node.get("result").get("tools");

        // Find broadcast_message tool
        for (var tool : tools) {
            if ("broadcast_message".equals(tool.get("name").asText())) {
                var schema = tool.get("inputSchema");
                var props = schema.get("properties");
                // Should have message and topic, but NOT broadcaster
                assertTrue(props.has("message"), "Should have message param");
                assertTrue(props.has("topic"), "Should have topic param");
                assertFalse(props.has("broadcaster"), "Broadcaster should NOT be in schema");
                return;
            }
        }
        fail("broadcast_message tool not found");
    }

    @Test
    public void testConfigNotInSchema() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var tools = node.get("result").get("tools");

        for (var tool : tools) {
            if ("with_config".equals(tool.get("name").asText())) {
                var props = tool.get("inputSchema").get("properties");
                assertTrue(props.has("key"));
                assertFalse(props.has("config"), "AtmosphereConfig should NOT be in schema");
                return;
            }
        }
        fail("with_config tool not found");
    }

    // ── Broadcaster Injection ────────────────────────────────────────────

    @Test
    public void testBroadcasterInjected() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"broadcast_message",
                    "arguments":{"message":"hello","topic":"/chat"}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var result = node.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("sent to /chat", result.get("content").get(0).get("text").asText());

        assertNotNull(capturedBroadcaster, "Broadcaster should have been injected");
        verify(mockBroadcasterFactory).lookup("/chat", true);
    }

    // ── AtmosphereConfig Injection ───────────────────────────────────────

    @Test
    public void testConfigInjected() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"with_config",
                    "arguments":{"key":"test-key"}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertEquals("config:test-key", node.get("result").get("content").get(0).get("text").asText());
        assertSame(mockConfig, capturedConfig, "AtmosphereConfig should be the mock");
    }

    // ── BroadcasterFactory Injection ─────────────────────────────────────

    @Test
    public void testBroadcasterFactoryInjected() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"with_factory",
                    "arguments":{"name":"test"}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertEquals("factory:test", node.get("result").get("content").get(0).get("text").asText());
        assertSame(mockBroadcasterFactory, capturedBroadcasterFactory);
    }

    // ── AtmosphereFramework Injection ────────────────────────────────────

    @Test
    public void testFrameworkInjected() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"with_framework",
                    "arguments":{"action":"deploy"}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        assertEquals("framework:deploy", node.get("result").get("content").get(0).get("text").asText());
        assertSame(mockFramework, capturedFramework);
    }

    // ── Multiple Injectables ─────────────────────────────────────────────

    @Test
    public void testMultipleInjectables() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"multi_inject",
                    "arguments":{"question":"what?","topic":"/stream"}
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, request));
        var result = node.get("result");
        assertFalse(result.get("isError").asBoolean());

        assertNotNull(capturedBroadcaster);
        assertSame(mockConfig, capturedConfig);
        verify(mockBroadcasterFactory).lookup("/stream", true);
    }
}
