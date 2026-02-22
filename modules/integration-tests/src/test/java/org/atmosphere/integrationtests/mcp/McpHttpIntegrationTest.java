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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MCP Streamable HTTP transport with a live embedded server.
 * Validates the full JSON-RPC round-trip over HTTP POST, session management via
 * Mcp-Session-Id, and DELETE session termination.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class McpHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @SuppressWarnings("resource") // closed in tearDown()
    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.mcp")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.mcp.processor");
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    public void tearDown() throws Exception {
        httpClient.close();
        server.close();
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testInitialize() throws Exception {
        var response = postJsonRpc("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26",
                  "capabilities":{},
                  "clientInfo":{"name":"test-client","version":"1.0.0"}
                }}""", null);

        assertEquals(200, response.statusCode());

        var body = MAPPER.readTree(response.body());
        assertEquals("2.0", body.get("jsonrpc").asText());
        assertEquals(1, body.get("id").asInt());

        var result = body.get("result");
        assertNotNull(result, "Initialize response must have result");
        assertEquals("2025-03-26", result.get("protocolVersion").asText());
        assertEquals("test-server", result.get("serverInfo").get("name").asText());
        assertEquals("1.0.0", result.get("serverInfo").get("version").asText());
        assertTrue(result.get("capabilities").has("tools"));
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testInitializeReturnsSessionId() throws Exception {
        var response = postJsonRpc("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26",
                  "capabilities":{},
                  "clientInfo":{"name":"test-client","version":"1.0.0"}
                }}""", null);

        var sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(sessionId, "Initialize must return Mcp-Session-Id header");
        assertFalse(sessionId.isBlank());
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testToolsList() throws Exception {
        var sessionId = initializeAndGetSessionId();

        var response = postJsonRpc("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""", sessionId);

        assertEquals(200, response.statusCode());
        var body = MAPPER.readTree(response.body());
        var tools = body.get("result").get("tools");
        assertNotNull(tools);
        assertTrue(tools.isArray());
        assertTrue(tools.size() >= 2, "Should have at least 2 tools (echo, add)");

        var toolNames = new java.util.ArrayList<String>();
        for (var tool : tools) {
            toolNames.add(tool.get("name").asText());
        }
        assertTrue(toolNames.contains("echo"), "Should contain echo tool");
        assertTrue(toolNames.contains("add"), "Should contain add tool");
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testToolsCallEcho() throws Exception {
        var sessionId = initializeAndGetSessionId();

        var response = postJsonRpc("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"echo","arguments":{"text":"hello world"}
                }}""", sessionId);

        assertEquals(200, response.statusCode());
        var body = MAPPER.readTree(response.body());
        var result = body.get("result");
        assertFalse(result.get("isError").asBoolean());
        var content = result.get("content").get(0);
        assertEquals("text", content.get("type").asText());
        assertEquals("Echo: hello world", content.get("text").asText());
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testToolsCallAdd() throws Exception {
        var sessionId = initializeAndGetSessionId();

        var response = postJsonRpc("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"add","arguments":{"a":3,"b":7}
                }}""", sessionId);

        assertEquals(200, response.statusCode());
        var body = MAPPER.readTree(response.body());
        var result = body.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("10", result.get("content").get(0).get("text").asText());
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testUnknownToolReturnsError() throws Exception {
        var sessionId = initializeAndGetSessionId();

        var response = postJsonRpc("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
                  "name":"nonexistent","arguments":{}
                }}""", sessionId);

        assertEquals(200, response.statusCode());
        var body = MAPPER.readTree(response.body());
        assertNotNull(body.get("error"), "Should return error for unknown tool");
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testPing() throws Exception {
        var sessionId = initializeAndGetSessionId();

        var response = postJsonRpc("""
                {"jsonrpc":"2.0","id":6,"method":"ping","params":{}}""", sessionId);

        assertEquals(200, response.statusCode());
        var body = MAPPER.readTree(response.body());
        assertNotNull(body.get("result"));
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testDeleteSession() throws Exception {
        var sessionId = initializeAndGetSessionId();

        var request = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + "/mcp"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .header("Mcp-Session-Id", sessionId)
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testNotificationReturns202() throws Exception {
        var sessionId = initializeAndGetSessionId();

        // "initialized" is a notification (no id field)
        var response = postJsonRpc("""
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""", sessionId);

        assertEquals(202, response.statusCode());
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testEmptyBodyReturns400() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String initializeAndGetSessionId() throws Exception {
        var response = postJsonRpc("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26",
                  "capabilities":{},
                  "clientInfo":{"name":"test-client","version":"1.0.0"}
                }}""", null);

        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Mcp-Session-Id").orElse(null);
    }

    private HttpResponse<String> postJsonRpc(String json, String sessionId) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
