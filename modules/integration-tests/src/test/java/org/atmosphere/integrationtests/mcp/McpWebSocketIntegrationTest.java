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
package org.atmosphere.integrationtests.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

/**
 * Integration tests for MCP over WebSocket transport with a live embedded server.
 * Validates JSON-RPC round-trip, tool invocation, and session lifecycle.
 */
@Test(groups = "core")
public class McpWebSocketIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @SuppressWarnings("resource") // closed in tearDown()
    @BeforeClass
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.mcp")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.mcp.processor");
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterClass
    public void tearDown() throws Exception {
        httpClient.close();
        server.close();
    }

    @Test(timeOut = 15_000)
    public void testWebSocketInitializeAndToolCall() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        // Expect at least 2 JSON-RPC responses (initialize + tools/call)
        var responseLatch = new CountDownLatch(2);

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/mcp"), new JsonRpcListener(received, openLatch, responseLatch))
                .join();

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket should connect");
        Thread.sleep(500);

        // Send initialize
        ws.sendText("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26",
                  "capabilities":{},
                  "clientInfo":{"name":"ws-test","version":"1.0.0"}
                }}""", true).join();

        // Send initialized notification
        ws.sendText("""
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""", true).join();

        // Wait for initialize response
        Thread.sleep(500);

        // Call echo tool
        ws.sendText("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"echo","arguments":{"text":"ws-test-message"}
                }}""", true).join();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS),
                "Should receive 2 responses but got: " + received);

        // Verify initialize response
        var initResponse = findResponseById(received, 1);
        assertNotNull(initResponse, "Should have initialize response");
        var initResult = MAPPER.readTree(initResponse);
        assertEquals(initResult.get("result").get("serverInfo").get("name").asText(), "test-server");

        // Verify echo response
        var echoResponse = findResponseById(received, 2);
        assertNotNull(echoResponse, "Should have echo response");
        var echoResult = MAPPER.readTree(echoResponse);
        assertFalse(echoResult.get("result").get("isError").asBoolean());
        assertEquals(echoResult.get("result").get("content").get(0).get("text").asText(),
                "Echo: ws-test-message");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test(timeOut = 15_000)
    public void testWebSocketToolsList() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var responseLatch = new CountDownLatch(2);

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/mcp"), new JsonRpcListener(received, openLatch, responseLatch))
                .join();

        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Initialize
        ws.sendText("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26","capabilities":{},
                  "clientInfo":{"name":"ws-test","version":"1.0.0"}
                }}""", true).join();
        Thread.sleep(500);

        // List tools
        ws.sendText("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""", true).join();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS),
                "Should receive tools/list response, got: " + received);

        var toolsResponse = findResponseById(received, 2);
        assertNotNull(toolsResponse);
        var tools = MAPPER.readTree(toolsResponse).get("result").get("tools");
        assertTrue(tools.size() >= 2);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test(timeOut = 15_000)
    public void testWebSocketAddTool() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var responseLatch = new CountDownLatch(2);

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/mcp"), new JsonRpcListener(received, openLatch, responseLatch))
                .join();

        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Initialize
        ws.sendText("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26","capabilities":{},
                  "clientInfo":{"name":"ws-test","version":"1.0.0"}
                }}""", true).join();
        Thread.sleep(500);

        // Call add tool
        ws.sendText("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"add","arguments":{"a":15,"b":27}
                }}""", true).join();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        var addResponse = findResponseById(received, 2);
        assertNotNull(addResponse);
        var result = MAPPER.readTree(addResponse).get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals(result.get("content").get(0).get("text").asText(), "42");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test(timeOut = 15_000)
    public void testWebSocketPing() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var responseLatch = new CountDownLatch(2);

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/mcp"), new JsonRpcListener(received, openLatch, responseLatch))
                .join();

        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Initialize
        ws.sendText("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26","capabilities":{},
                  "clientInfo":{"name":"ws-test","version":"1.0.0"}
                }}""", true).join();
        Thread.sleep(500);

        // Ping
        ws.sendText("""
                {"jsonrpc":"2.0","id":2,"method":"ping","params":{}}""", true).join();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        var pingResponse = findResponseById(received, 2);
        assertNotNull(pingResponse);
        var result = MAPPER.readTree(pingResponse);
        assertNotNull(result.get("result"));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private URI buildWsUri(String path) {
        return URI.create(server.getWebSocketUrl() + path
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                + "&X-Atmosphere-Transport=websocket"
                + "&X-Atmosphere-Framework=5.0.0");
    }

    private String findResponseById(CopyOnWriteArrayList<String> messages, int id) {
        for (var msg : messages) {
            try {
                var node = MAPPER.readTree(msg);
                if (node.has("id") && node.get("id").asInt() == id) {
                    return msg;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * WebSocket listener that collects JSON-RPC responses, ignoring protocol padding.
     */
    static class JsonRpcListener implements WebSocket.Listener {
        private final CopyOnWriteArrayList<String> received;
        private final CountDownLatch openLatch;
        private final CountDownLatch responseLatch;
        private final StringBuilder buffer = new StringBuilder();

        JsonRpcListener(CopyOnWriteArrayList<String> received,
                        CountDownLatch openLatch, CountDownLatch responseLatch) {
            this.received = received;
            this.openLatch = openLatch;
            this.responseLatch = responseLatch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            openLatch.countDown();
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                var msg = buffer.toString().trim();
                buffer.setLength(0);
                if (!msg.isBlank() && msg.startsWith("{")) {
                    received.add(msg);
                    // Count down for JSON-RPC responses (those with "id")
                    try {
                        var node = new ObjectMapper().readTree(msg);
                        if (node.has("id")) {
                            responseLatch.countDown();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
        }
    }
}
