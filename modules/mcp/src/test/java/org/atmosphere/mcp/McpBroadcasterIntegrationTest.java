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
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpServer;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: MCP tool call → Broadcaster → subscriber receives message.
 * Uses real AtmosphereFramework, real BroadcasterFactory, real Broadcaster.
 */
public class McpBroadcasterIntegrationTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private McpProtocolHandler handler;
    private AtmosphereResource mcpResource;
    private MessageCapture capture;

    /**
     * Captures broadcast messages received by a subscriber.
     */
    static class MessageCapture implements AtmosphereHandler {
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch;

        MessageCapture(int expectedMessages) {
            latch = new CountDownLatch(expectedMessages);
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            var msg = event.getMessage();
            if (msg != null) {
                messages.add(msg.toString());
                latch.countDown();
            }
        }

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void destroy() {
        }
    }

    @McpServer(name = "integration-test", version = "1.0.0")
    public static class IntegrationMcpServer {

        @McpTool(name = "send_message", description = "Send a message to a topic")
        public String sendMessage(
                @McpParam(name = "message", description = "The message to send") String message,
                @McpParam(name = "topic", description = "Target broadcaster topic") String topic,
                Broadcaster broadcaster) {
            broadcaster.broadcast(message);
            return "sent: " + message;
        }

        @McpTool(name = "send_with_config", description = "Send using config lookup")
        public String sendWithConfig(
                @McpParam(name = "message", description = "The message") String message,
                @McpParam(name = "topic", description = "Target topic") String topic,
                AtmosphereConfig config) {
            var b = config.getBroadcasterFactory().lookup(topic, true);
            b.broadcast(message);
            return "sent via config: " + message;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        framework = new AtmosphereFramework();
        config = framework.getAtmosphereConfig();

        var factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        var registry = new McpRegistry();
        registry.scan(new IntegrationMcpServer());
        handler = new McpProtocolHandler("integration-test", "1.0.0", registry, config);

        // Mock MCP client resource (the agent connection)
        mcpResource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(mcpResource.getRequest()).thenReturn(request);
        when(mcpResource.uuid()).thenReturn("mcp-agent-uuid");
    }

    @AfterEach
    public void tearDown() {
        if (framework != null) {
            framework.destroy();
        }
    }

    @SuppressWarnings("deprecation")
    private AtmosphereResource createSubscriber(Broadcaster broadcaster,
                                                 MessageCapture handler) throws Exception {
        @SuppressWarnings("unchecked")
        var asyncSupport = (org.atmosphere.cpr.AsyncSupport<AtmosphereResourceImpl>) mock(org.atmosphere.cpr.AsyncSupport.class);
        var resource = new AtmosphereResourceImpl(
                config,
                broadcaster,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                asyncSupport,
                handler
        );
        broadcaster.addAtmosphereResource(resource);
        return resource;
    }

    @Test
    public void testMcpToolBroadcastsToSubscriber() throws Exception {
        // Set up a subscriber on /chat
        var broadcaster = config.getBroadcasterFactory().lookup("/chat", true);
        capture = new MessageCapture(1);
        createSubscriber(broadcaster, capture);

        // Call the MCP tool via JSON-RPC
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"send_message",
                    "arguments":{"message":"Hello from agent!","topic":"/chat"}
                }}""";

        var response = handler.handleMessage(mcpResource, request);
        var node = mapper.readTree(response);

        // Tool returned success
        assertFalse(node.get("result").get("isError").asBoolean());
        assertEquals("sent: Hello from agent!",
                node.get("result").get("content").get(0).get("text").asText());

        // Subscriber received the broadcast
        assertTrue(capture.latch.await(5, TimeUnit.SECONDS),
                "Subscriber should receive the broadcast within 5s");
        assertEquals(1, capture.messages.size());
        assertEquals("Hello from agent!", capture.messages.get(0));
    }

    @Test
    public void testMcpToolBroadcastsToMultipleSubscribers() throws Exception {
        var broadcaster = config.getBroadcasterFactory().lookup("/multi", true);
        var capture1 = new MessageCapture(1);
        var capture2 = new MessageCapture(1);
        createSubscriber(broadcaster, capture1);
        createSubscriber(broadcaster, capture2);

        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"send_message",
                    "arguments":{"message":"broadcast to all","topic":"/multi"}
                }}""";

        handler.handleMessage(mcpResource, request);

        assertTrue(capture1.latch.await(5, TimeUnit.SECONDS));
        assertTrue(capture2.latch.await(5, TimeUnit.SECONDS));
        assertEquals("broadcast to all", capture1.messages.get(0));
        assertEquals("broadcast to all", capture2.messages.get(0));
    }

    @Test
    public void testMcpToolWithConfigInjection() throws Exception {
        var broadcaster = config.getBroadcasterFactory().lookup("/config-topic", true);
        capture = new MessageCapture(1);
        createSubscriber(broadcaster, capture);

        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"send_with_config",
                    "arguments":{"message":"via config","topic":"/config-topic"}
                }}""";

        var response = handler.handleMessage(mcpResource, request);
        var node = mapper.readTree(response);
        assertEquals("sent via config: via config",
                node.get("result").get("content").get(0).get("text").asText());

        assertTrue(capture.latch.await(5, TimeUnit.SECONDS));
        assertEquals("via config", capture.messages.get(0));
    }

    @Test
    public void testDifferentTopicsAreIsolated() throws Exception {
        var chatBroadcaster = config.getBroadcasterFactory().lookup("/chat", true);
        var alertBroadcaster = config.getBroadcasterFactory().lookup("/alerts", true);
        var chatCapture = new MessageCapture(1);
        var alertCapture = new MessageCapture(1);
        createSubscriber(chatBroadcaster, chatCapture);
        createSubscriber(alertBroadcaster, alertCapture);

        // Send to /chat only
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"send_message",
                    "arguments":{"message":"chat only","topic":"/chat"}
                }}""";

        handler.handleMessage(mcpResource, request);

        assertTrue(chatCapture.latch.await(5, TimeUnit.SECONDS));
        assertEquals("chat only", chatCapture.messages.get(0));

        // Alert subscriber should NOT have received anything
        assertFalse(alertCapture.latch.await(500, TimeUnit.MILLISECONDS),
                "Alert subscriber should not receive chat messages");
        assertTrue(alertCapture.messages.isEmpty());
    }
}
