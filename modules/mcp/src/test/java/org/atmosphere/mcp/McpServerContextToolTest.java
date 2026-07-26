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
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.atmosphere.mcp.runtime.McpServerContext;
import org.atmosphere.mcp.runtime.McpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the sampling/roots surface has a real consumer: an {@code @McpTool}
 * method declaring an {@link McpServerContext} parameter receives the live
 * session's context and can issue a client-side completion from inside the
 * tool body. Without this the issuance API would be an SPI with no reachable
 * caller.
 */
class McpServerContextToolTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private McpRegistry registry;
    private McpProtocolHandler handler;
    private McpSession session;
    private AtmosphereResource resource;

    /** Tool provider whose method asks the client to complete a prompt. */
    public static class SamplingTools {

        @McpTool(name = "summarize", description = "Summarize text via client sampling")
        public String summarize(@McpParam(name = "text") String text, McpServerContext ctx) {
            if (!ctx.canSample()) {
                return "sampling-unavailable";
            }
            ctx.sample(List.of(Map.of("role", "user",
                            "content", Map.of("type", "text", "text", "Summarize: " + text))),
                    Map.of("maxTokens", 50));
            return "requested";
        }

        @McpTool(name = "capabilities", description = "Report the client's capabilities")
        public String capabilities(McpServerContext ctx) {
            return ctx.canSample() + "/" + ctx.canListRoots() + "/" + ctx.canElicit();
        }
    }

    @BeforeEach
    void setUp() {
        registry = new McpRegistry();
        registry.scan(new SamplingTools());
        handler = new McpProtocolHandler("test", "1.0", registry, mock(AtmosphereConfig.class));

        session = new McpSession();
        session.markInitialized();
        session.setClientInfo("test-client", "1.0", Map.of("sampling", Map.of()));
        session.setProtocolVersion("2025-11-25");

        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("ctx-test");
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
    void contextParameterIsNotExposedInTheToolSchema() {
        var tool = registry.tool("summarize").orElseThrow();
        var schema = McpRegistry.inputSchema(tool);
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("text"));
        assertEquals(1, properties.size(),
                "the injected context must never be asked of the model: " + properties.keySet());
    }

    @Test
    void toolIssuesASamplingRequestThroughTheInjectedContext() throws Exception {
        var call = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"summarize","arguments":{"text":"hello world"}}}""";
        var response = handler.handleMessage(resource, call);
        var node = mapper.readTree(response);
        assertEquals("requested",
                node.get("result").get("content").get(0).get("text").stringValue());

        var pending = session.drainPendingNotifications();
        assertEquals(1, pending.size(), "the tool's sampling request must reach the wire");
        var sampling = mapper.readTree(pending.getFirst());
        assertEquals("sampling/createMessage", sampling.get("method").stringValue());
        assertTrue(sampling.get("params").get("messages").get(0)
                        .get("content").get("text").stringValue().contains("hello world"),
                "the tool's own prompt must be carried through");
    }

    @Test
    void contextReportsTheClientsActualCapabilities() throws Exception {
        var call = """
                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                 "params":{"name":"capabilities","arguments":{}}}""";
        var node = mapper.readTree(handler.handleMessage(resource, call));
        // Only "sampling" was advertised at initialize.
        assertEquals("true/false/false",
                node.get("result").get("content").get(0).get("text").stringValue(),
                "the context must report advertised capabilities, not assume support");
    }

    @Test
    void toolWithoutASessionSeesSamplingUnavailable() throws Exception {
        var sessionless = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(sessionless.getRequest()).thenReturn(request);
        when(sessionless.uuid()).thenReturn("no-session");
        when(request.getAttribute(anyString())).thenReturn(null);

        var call = """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"summarize","arguments":{"text":"x"}}}""";
        var node = mapper.readTree(handler.handleMessage(sessionless, call));
        assertEquals("sampling-unavailable",
                node.get("result").get("content").get(0).get("text").stringValue());
    }

    @Test
    void contextDoesNotLeakBetweenInvocations() throws Exception {
        var call = """
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"capabilities","arguments":{}}}""";
        handler.handleMessage(resource, call);

        // A second call on a session-less resource must NOT inherit the
        // previous invocation's session from the thread-local.
        var sessionless = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(sessionless.getRequest()).thenReturn(request);
        when(sessionless.uuid()).thenReturn("no-session");
        when(request.getAttribute(anyString())).thenReturn(null);

        var node = mapper.readTree(handler.handleMessage(sessionless, call));
        var reported = node.get("result").get("content").get(0).get("text").stringValue();
        assertEquals("false/false/false", reported);
        assertFalse(reported.contains("true"), "a stale session must never bleed across calls");
    }
}
