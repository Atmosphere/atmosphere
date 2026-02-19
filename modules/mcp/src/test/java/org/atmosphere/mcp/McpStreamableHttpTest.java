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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpServer;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpHandler;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.atmosphere.mcp.runtime.McpSession;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/**
 * Tests for Streamable HTTP transport support in McpHandler.
 */
public class McpStreamableHttpTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private McpHandler handler;

    @McpServer(name = "test-server", version = "1.0.0")
    public static class SimpleMcpServer {
        @McpTool(name = "echo", description = "Echo input")
        public String echo(@McpParam(name = "text") String text) {
            return text;
        }
    }

    @BeforeMethod
    public void setUp() {
        var registry = new McpRegistry();
        registry.scan(new SimpleMcpServer());
        var protocolHandler = new McpProtocolHandler("test-server", "1.0.0", registry);
        handler = new McpHandler(protocolHandler);
    }

    // ── POST with JSON response ──────────────────────────────────────────

    @Test
    public void testPostJsonResponse() throws Exception {
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"ping"}""";

        var output = new StringWriter();
        var resource = mockResource("POST", body, "application/json", null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        var node = mapper.readTree(output.toString());
        assertEquals(node.get("id").asInt(), 1);
        assertNotNull(node.get("result"));
        verify(resource.getResponse()).setContentType("application/json");
        verify(resource.getResponse()).setStatus(200);
    }

    // ── POST with SSE response ───────────────────────────────────────────

    @Test
    public void testPostSseResponse() throws Exception {
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"ping"}""";

        var output = new StringWriter();
        var resource = mockResource("POST", body, "application/json, text/event-stream", null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        var raw = output.toString();
        assertTrue(raw.startsWith("event: message\ndata: "), "Should be SSE format");
        assertTrue(raw.endsWith("\n\n"), "SSE must end with double newline");
        verify(resource.getResponse()).setContentType("text/event-stream");

        // Extract JSON from SSE
        var json = raw.replace("event: message\ndata: ", "").replace("\n\n", "");
        var node = mapper.readTree(json);
        assertEquals(node.get("id").asInt(), 1);
    }

    // ── POST notification returns 202 ────────────────────────────────────

    @Test
    public void testPostNotificationReturns202() throws Exception {
        var body = """
                {"jsonrpc":"2.0","method":"notifications/initialized"}""";

        var resource = mockResource("POST", body, "application/json", null);

        handler.onRequest(resource);

        verify(resource.getResponse()).setStatus(202);
    }

    // ── POST empty body returns 400 ──────────────────────────────────────

    @Test
    public void testPostEmptyBodyReturns400() throws Exception {
        var output = new StringWriter();
        var resource = mockResource("POST", "", "application/json", null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        verify(resource.getResponse()).setStatus(400);
        assertTrue(output.toString().contains("Empty body"));
    }

    // ── Session ID management ────────────────────────────────────────────

    @Test
    public void testInitializeReturnsSessionId() throws Exception {
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test","version":"1.0"}
                }}""";

        var output = new StringWriter();
        var resource = mockResource("POST", body, "application/json", null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        // Session ID header should be set
        verify(resource.getResponse()).setHeader(eq("Mcp-Session-Id"), argThat(s -> s != null && !s.isEmpty()));

        // Session should be stored
        assertEquals(handler.sessions().size(), 1);
    }

    @Test
    public void testSessionIdRestoredOnSubsequentRequest() throws Exception {
        // First: initialize
        var initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test","version":"1.0"}
                }}""";

        var output1 = new StringWriter();
        var resource1 = mockResource("POST", initBody, "application/json", null);
        when(resource1.getResponse().getWriter()).thenReturn(new PrintWriter(output1));

        handler.onRequest(resource1);

        // Get the session ID
        var sessionId = handler.sessions().keySet().iterator().next();
        assertNotNull(sessionId);

        // Second: ping with session ID
        var pingBody = """
                {"jsonrpc":"2.0","id":2,"method":"ping"}""";
        var output2 = new StringWriter();
        var resource2 = mockResource("POST", pingBody, "application/json", sessionId);
        when(resource2.getResponse().getWriter()).thenReturn(new PrintWriter(output2));

        handler.onRequest(resource2);

        // Session should be restored (same session attribute set)
        verify(resource2.getRequest()).setAttribute(eq(McpSession.ATTRIBUTE_KEY), any(McpSession.class));
    }

    // ── GET suspends for SSE ─────────────────────────────────────────────

    @Test
    public void testGetSuspendsForSse() throws Exception {
        var resource = mockResource("GET", "", "text/event-stream", null);

        handler.onRequest(resource);

        verify(resource).suspend();
        verify(resource.getResponse()).setContentType("text/event-stream");
    }

    // ── DELETE terminates session ────────────────────────────────────────

    @Test
    public void testDeleteTerminatesSession() throws Exception {
        // First create a session
        var initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test","version":"1.0"}
                }}""";
        var output = new StringWriter();
        var resource1 = mockResource("POST", initBody, "application/json", null);
        when(resource1.getResponse().getWriter()).thenReturn(new PrintWriter(output));
        handler.onRequest(resource1);

        assertEquals(handler.sessions().size(), 1);
        var sessionId = handler.sessions().keySet().iterator().next();

        // DELETE
        var resource2 = mockResource("DELETE", "", null, sessionId);
        handler.onRequest(resource2);

        verify(resource2.getResponse()).setStatus(204);
        assertTrue(handler.sessions().isEmpty(), "Session should be removed");
    }

    // ── Unsupported method returns 405 ───────────────────────────────────

    @Test
    public void testUnsupportedMethodReturns405() throws Exception {
        var output = new StringWriter();
        var resource = mockResource("PUT", "", null, null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        verify(resource.getResponse()).setStatus(405);
    }

    // ── Helper ───────────────────────────────────────────────────────────

    private AtmosphereResource mockResource(String method, String body,
                                            String acceptHeader, String sessionId) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);

        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.uuid()).thenReturn("test-uuid");

        when(request.getMethod()).thenReturn(method);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        when(request.getHeader("Accept")).thenReturn(acceptHeader);
        when(request.getHeader("Mcp-Session-Id")).thenReturn(sessionId);

        // Support setAttribute/getAttribute so McpSession can be stored and retrieved
        var attributes = new java.util.HashMap<String, Object>();
        doAnswer(inv -> {
            attributes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.getArgument(0, String.class)));

        return resource;
    }
}
