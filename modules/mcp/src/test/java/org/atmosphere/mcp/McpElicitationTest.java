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
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.atmosphere.mcp.runtime.McpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Pins the elicitation/create flow (MCP 2025-06-18+):
 * <ul>
 *   <li>Capability gate: server refuses to elicit when the client did not
 *       advertise the {@code elicitation} capability at handshake.</li>
 *   <li>Request shape: outgoing request is well-formed JSON-RPC 2.0 with
 *       method {@code elicitation/create} and {@code message}/{@code requestedSchema} params.</li>
 *   <li>Response correlation: when the client posts a reply envelope (no
 *       method, matching id, with result), the future completes with that envelope.</li>
 * </ul>
 */
public class McpElicitationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private McpProtocolHandler handler;
    private McpSession session;
    private AtmosphereResource resource;

    @BeforeEach
    public void setUp() {
        var registry = new McpRegistry();
        handler = new McpProtocolHandler("test", "1.0", registry, mock(AtmosphereConfig.class));
        session = new McpSession();
        session.markInitialized();
        session.setClientInfo("test-client", "1.0", Map.of("elicitation", Map.of()));
        session.setProtocolVersion("2025-11-25");

        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("test-elicit");
        when(request.getAttribute(McpSession.ATTRIBUTE_KEY)).thenReturn(session);
        var attrs = new java.util.HashMap<String, Object>();
        attrs.put(McpSession.ATTRIBUTE_KEY, session);
        org.mockito.Mockito.doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString()))
                .thenAnswer(inv -> attrs.get(inv.getArgument(0, String.class)));
    }

    @Test
    public void elicitFailsWhenClientLacksCapability() {
        var noCapSession = new McpSession();
        noCapSession.setClientInfo("anon", "0", Map.of()); // no "elicitation"
        var future = handler.elicit(noCapSession, "What's your name?",
                Map.of("type", "object"));

        assertTrue(future.isCompletedExceptionally(), "missing capability must fail-fast");
        var ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("elicitation"));
    }

    @Test
    public void elicitEnqueuesValidRequestEnvelope() throws Exception {
        var schema = Map.<String, Object>of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")));
        var future = handler.elicit(session, "What is your name?", schema);
        assertFalse(future.isDone(), "future should be pending until client replies");

        // The serialized request lands on the session's notification queue,
        // which the SSE GET stream drains on the wire.
        var pending = session.drainPendingNotifications();
        assertEquals(1, pending.size());
        var node = mapper.readTree(pending.get(0));

        assertEquals("2.0", node.get("jsonrpc").stringValue());
        assertNotNull(node.get("id"), "must carry request id for response correlation");
        assertEquals("elicitation/create", node.get("method").stringValue());
        assertEquals("What is your name?", node.get("params").get("message").stringValue());
        assertEquals("object",
                node.get("params").get("requestedSchema").get("type").stringValue());
    }

    @Test
    public void clientResponseEnvelopeCompletesPendingFuture() throws Exception {
        var future = handler.elicit(session, "Confirm?", Map.of("type", "object"));
        var pending = session.drainPendingNotifications();
        var requestId = mapper.readTree(pending.get(0)).get("id").stringValue();

        // Client replies with the result envelope (no "method", same id)
        var reply = String.format("""
                {"jsonrpc":"2.0","id":"%s","result":{
                    "action":"accept",
                    "content":{"answer":"yes"}
                }}""", requestId);
        var serverResponse = handler.handleMessage(resource, reply);
        assertNull(serverResponse, "response envelopes must not echo a server response");

        var resolved = future.get(1, TimeUnit.SECONDS);
        assertNotNull(resolved);
        assertEquals(requestId, resolved.get("id").stringValue());
        assertEquals("accept", resolved.get("result").get("action").stringValue());
        assertEquals("yes",
                resolved.get("result").get("content").get("answer").stringValue());
        assertTrue(session.pendingServerRequestIds().isEmpty(),
                "future should be removed from registry once completed");
    }

    @Test
    public void unknownResponseIdIsIgnored() throws Exception {
        // A response envelope with no matching pending request must not crash
        // — log + drop is the spec-aligned behavior.
        var stray = """
                {"jsonrpc":"2.0","id":"never-issued","result":{"action":"cancel"}}""";
        var serverResponse = handler.handleMessage(resource, stray);
        assertNull(serverResponse);
    }

    @Test
    public void cancelServerRequestUnblocksWaiter() {
        var future = handler.elicit(session, "Wait", Map.of("type", "object"));
        var pending = session.drainPendingNotifications();
        var node = uncheckedReadTree(pending.get(0));
        var requestId = node.get("id").stringValue();

        // Caller-side timeout simulation
        var cancelled = session.cancelServerRequest(requestId,
                new TimeoutException("user timeout"));
        assertTrue(cancelled);
        var ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, ex.getCause());
    }

    private static tools.jackson.databind.JsonNode uncheckedReadTree(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
