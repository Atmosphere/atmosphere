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
package org.atmosphere.mcp.runtime;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves all three session-teardown paths in {@link McpHandler} complete
 * in-flight server-initiated requests rather than dropping them
 * (Correctness Invariant #2): {@code DELETE}, TTL eviction, and
 * {@code destroy()}.
 */
class McpHandlerTeardownTest {

    public static class SimpleMcpServer {
        @McpTool(name = "echo", description = "Echo input")
        public String echo(@McpParam(name = "text") String text) {
            return text;
        }
    }

    private static McpHandler handler(long ttlMs) {
        var registry = new McpRegistry();
        registry.scan(new SimpleMcpServer());
        var protocolHandler = new McpProtocolHandler("test-server", "1.0.0", registry,
                mock(AtmosphereConfig.class));
        return new McpHandler(protocolHandler, ttlMs);
    }

    /** Drive an initialize handshake so the handler owns a live session. */
    private static String openSession(McpHandler handler) throws Exception {
        var initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "clientInfo":{"name":"test","version":"1.0"}
                }}""";
        var resource = mockResource("POST", initBody, "application/json", null);
        when(resource.getResponse().getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        handler.onRequest(resource);
        assertEquals(1, handler.sessions().size(), "precondition: handshake created a session");
        return handler.sessions().keySet().iterator().next();
    }

    private static CompletableFuture<JsonNode> pendingRequestOn(McpSession session) {
        var future = new CompletableFuture<JsonNode>();
        session.registerServerRequest("elicit-1", future);
        assertTrue(!future.isDone(), "precondition: the request is genuinely in flight");
        return future;
    }

    @Test
    void deleteCancelsPendingServerRequests() throws Exception {
        var handler = handler(McpSession.DEFAULT_TTL_MS);
        var sessionId = openSession(handler);
        var session = handler.sessions().get(sessionId);
        var pending = pendingRequestOn(session);

        var deleteResource = mockResource("DELETE", "", null, sessionId);
        handler.onRequest(deleteResource);

        assertTrue(handler.sessions().isEmpty(), "DELETE must remove the session");
        assertTrue(pending.isCompletedExceptionally(),
                "DELETE must fail the in-flight elicitation instead of dropping it");
        assertTrue(session.isClosed());
    }

    @Test
    void ttlEvictionCancelsPendingServerRequests() throws Exception {
        // Drive a sweep pass directly rather than racing the fixed-rate
        // schedule (whose first pass fires before this session exists).
        var handler = handler(1L);
        var sessionId = openSession(handler);
        var session = handler.sessions().get(sessionId);
        var pending = pendingRequestOn(session);

        Thread.sleep(20); // age the session past the 1ms TTL
        var evicted = handler.evictExpiredSessions();

        assertEquals(1, evicted, "the idle session must be evicted");
        assertTrue(handler.sessions().isEmpty());
        assertTrue(pending.isCompletedExceptionally(),
                "TTL eviction must fail the in-flight elicitation instead of dropping it");
        assertTrue(session.isClosed());
        handler.destroy();
    }

    @Test
    void ttlEvictionLeavesLiveSessionsAlone() throws Exception {
        var handler = handler(McpSession.DEFAULT_TTL_MS);
        var sessionId = openSession(handler);
        var pending = pendingRequestOn(handler.sessions().get(sessionId));

        assertEquals(0, handler.evictExpiredSessions(),
                "a session inside its TTL must never be evicted");
        assertTrue(handler.sessions().containsKey(sessionId));
        assertTrue(!pending.isDone(), "a live session's in-flight request must stay pending");

        handler.destroy();
    }

    @Test
    void destroyCancelsPendingServerRequests() throws Exception {
        var handler = handler(McpSession.DEFAULT_TTL_MS);
        var sessionId = openSession(handler);
        var session = handler.sessions().get(sessionId);
        var pending = pendingRequestOn(session);

        handler.destroy();

        assertTrue(handler.sessions().isEmpty(), "destroy must drain the session store");
        assertTrue(pending.isCompletedExceptionally(),
                "destroy must fail in-flight elicitations rather than clear() them away");
        assertTrue(session.isClosed());
    }

    @Test
    void destroyIsSafeWithNoSessions() {
        var handler = handler(McpSession.DEFAULT_TTL_MS);
        handler.destroy();
        handler.destroy();
        assertTrue(handler.sessions().isEmpty());
    }

    // ── Helper (mirrors McpStreamableHttpTest's harness) ─────────────────

    private static AtmosphereResource mockResource(String method, String body,
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

        var attributes = new java.util.HashMap<String, Object>();
        doAnswer(inv -> {
            attributes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString()))
                .thenAnswer(inv -> attributes.get(inv.getArgument(0, String.class)));

        return resource;
    }
}
