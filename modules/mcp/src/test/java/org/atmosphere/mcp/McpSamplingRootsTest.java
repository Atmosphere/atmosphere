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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins server→client {@code sampling/createMessage} and {@code roots/list}
 * issuance. The server previously advertised tools/resources/prompts/tasks but
 * had no way to ask the client for an LLM completion or for its roots, even
 * though the elicitation plumbing already carried server-initiated
 * request/response over {@link McpSession} futures.
 *
 * <p>Both are <em>client</em>-declared capabilities per the MCP spec, so the
 * gating assertions here are Runtime Truth (Correctness Invariant #5): the
 * server must never issue a request the connected client never said it could
 * answer.</p>
 */
class McpSamplingRootsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private McpProtocolHandler handler;
    private McpSession session;
    private AtmosphereResource resource;

    private static final List<Map<String, Object>> MESSAGES = List.of(
            Map.of("role", "user", "content", Map.of("type", "text", "text", "Summarize this")));

    @BeforeEach
    void setUp() {
        handler = new McpProtocolHandler("test", "1.0", new McpRegistry(),
                mock(AtmosphereConfig.class));
        session = new McpSession();
        session.markInitialized();
        session.setClientInfo("test-client", "1.0",
                Map.of("sampling", Map.of(), "roots", Map.of("listChanged", true)));
        session.setProtocolVersion("2025-11-25");

        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("test-sampling");
        var attrs = new java.util.HashMap<String, Object>();
        attrs.put(McpSession.ATTRIBUTE_KEY, session);
        org.mockito.Mockito.doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString()))
                .thenAnswer(inv -> attrs.get(inv.getArgument(0, String.class)));
    }

    // --- capability gating (Runtime Truth) ---

    @Test
    void samplingFailsFastWhenClientDidNotAdvertiseIt() {
        var noCap = new McpSession();
        noCap.setClientInfo("anon", "0", Map.of("roots", Map.of()));

        var future = handler.sample(noCap, MESSAGES, null);
        assertTrue(future.isCompletedExceptionally());
        var ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("sampling"), ex.getCause().getMessage());
        assertTrue(noCap.drainPendingNotifications().isEmpty(),
                "nothing may go on the wire when the capability is absent");
    }

    @Test
    void rootsFailsFastWhenClientDidNotAdvertiseIt() {
        var noCap = new McpSession();
        noCap.setClientInfo("anon", "0", Map.of("sampling", Map.of()));

        var future = handler.listRoots(noCap);
        assertTrue(future.isCompletedExceptionally());
        var ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("roots"), ex.getCause().getMessage());
    }

    @Test
    void issuanceRequiresASession() {
        var future = handler.sample(null, MESSAGES, null);
        assertTrue(future.isCompletedExceptionally());
        var ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void samplingRejectsAnEmptyConversation() {
        var future = handler.sample(session, List.of(), null);
        assertTrue(future.isCompletedExceptionally());
        var ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    // --- request envelopes ---

    @Test
    void samplingEnqueuesAWellFormedCreateMessageRequest() throws Exception {
        var future = handler.sample(session, MESSAGES,
                Map.of("maxTokens", 300, "systemPrompt", "Be brief"));
        assertFalse(future.isDone(), "pending until the client replies");

        var pending = session.drainPendingNotifications();
        assertEquals(1, pending.size());
        var node = mapper.readTree(pending.getFirst());

        assertEquals("2.0", node.get("jsonrpc").stringValue());
        assertNotNull(node.get("id"), "must carry a request id for response correlation");
        assertEquals("sampling/createMessage", node.get("method").stringValue());
        var params = node.get("params");
        assertEquals(300, params.get("maxTokens").asInt());
        assertEquals("Be brief", params.get("systemPrompt").stringValue());
        assertEquals("user", params.get("messages").get(0).get("role").stringValue());
        assertEquals("Summarize this",
                params.get("messages").get(0).get("content").get("text").stringValue());
    }

    @Test
    void optionsCannotDisplaceTheMessagesField() throws Exception {
        handler.sample(session, MESSAGES, Map.of("messages", "hijacked"));
        var node = mapper.readTree(session.drainPendingNotifications().getFirst());
        assertTrue(node.get("params").get("messages").isArray(),
                "the caller's messages must always win over an options collision");
    }

    @Test
    void rootsEnqueuesAWellFormedListRequest() throws Exception {
        var future = handler.listRoots(session);
        assertFalse(future.isDone());

        var pending = session.drainPendingNotifications();
        assertEquals(1, pending.size());
        var node = mapper.readTree(pending.getFirst());
        assertEquals("2.0", node.get("jsonrpc").stringValue());
        assertEquals("roots/list", node.get("method").stringValue());
        assertNotNull(node.get("id"));
    }

    // --- response correlation ---

    @Test
    void clientSamplingResponseCompletesThePendingFuture() throws Exception {
        var future = handler.sample(session, MESSAGES, Map.of("maxTokens", 100));
        var requestId = mapper.readTree(session.drainPendingNotifications().getFirst())
                .get("id").stringValue();

        var reply = String.format("""
                {"jsonrpc":"2.0","id":"%s","result":{
                    "role":"assistant",
                    "model":"test-model",
                    "content":{"type":"text","text":"A summary."}
                }}""", requestId);
        assertNull(handler.handleMessage(resource, reply),
                "a response envelope must not echo a server response");

        var resolved = future.get(1, TimeUnit.SECONDS);
        assertEquals("A summary.",
                resolved.get("result").get("content").get("text").stringValue());
        assertEquals("test-model", resolved.get("result").get("model").stringValue());
        assertTrue(session.pendingServerRequestIds().isEmpty(),
                "the slot must be released once the response arrives");
    }

    @Test
    void clientRootsResponseCompletesThePendingFuture() throws Exception {
        var future = handler.listRoots(session);
        var requestId = mapper.readTree(session.drainPendingNotifications().getFirst())
                .get("id").stringValue();

        var reply = String.format("""
                {"jsonrpc":"2.0","id":"%s","result":{
                    "roots":[{"uri":"file:///workspace","name":"workspace"}]
                }}""", requestId);
        assertNull(handler.handleMessage(resource, reply));

        var resolved = future.get(1, TimeUnit.SECONDS);
        var roots = resolved.get("result").get("roots");
        assertEquals(1, roots.size());
        assertEquals("file:///workspace", roots.get(0).get("uri").stringValue());
        assertTrue(session.pendingServerRequestIds().isEmpty());
    }

    @Test
    void samplingErrorEnvelopeAlsoCompletesTheFuture() throws Exception {
        var future = handler.sample(session, MESSAGES, null);
        var requestId = mapper.readTree(session.drainPendingNotifications().getFirst())
                .get("id").stringValue();

        var reply = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"%s\",\"error\":{\"code\":-32603,"
                        + "\"message\":\"user rejected sampling\"}}", requestId);
        assertNull(handler.handleMessage(resource, reply));

        // Terminal path completeness: a client-side refusal must resolve the
        // waiter rather than leave the tool parked forever.
        var resolved = future.get(1, TimeUnit.SECONDS);
        assertEquals("user rejected sampling",
                resolved.get("error").get("message").stringValue());
    }

    // --- bounded pending requests (Invariant #3) ---

    @Test
    void pendingServerRequestsAreBounded() {
        CompletableFuture<tools.jackson.databind.JsonNode> last = null;
        for (int i = 0; i < McpSession.MAX_PENDING_SERVER_REQUESTS + 5; i++) {
            last = handler.listRoots(session);
        }
        assertNotNull(last);
        assertTrue(last.isCompletedExceptionally(),
                "issuance past the in-flight bound must be refused, not queued forever");
        assertEquals(McpSession.MAX_PENDING_SERVER_REQUESTS,
                session.pendingServerRequestIds().size(),
                "the pending map must never exceed its bound");

        var ex = assertThrows(ExecutionException.class, () -> {
            var f = handler.listRoots(session);
            f.get(1, TimeUnit.SECONDS);
        });
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("in flight"), ex.getCause().getMessage());
    }

    @Test
    void tryRegisterRefusesPastTheBoundButRegisterStaysUnbounded() {
        var bounded = new McpSession();
        for (int i = 0; i < McpSession.MAX_PENDING_SERVER_REQUESTS; i++) {
            assertTrue(bounded.tryRegisterServerRequest("id-" + i, new CompletableFuture<>()));
        }
        assertFalse(bounded.tryRegisterServerRequest("overflow", new CompletableFuture<>()));
        assertFalse(bounded.pendingServerRequestIds().contains("overflow"),
                "a refused registration must leave no slot behind");
    }
}
