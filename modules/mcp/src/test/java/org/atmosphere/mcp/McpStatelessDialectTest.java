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

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.protocol.McpMessage;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the stateless {@code 2026-07-28} dialect (SEP-2567 sessionless,
 * SEP-2575 no handshake). The defining behaviors: a request works with no prior
 * {@code initialize}, never creates a session, carries its protocol version in
 * {@code _meta}, gets a {@code resultType} envelope, and can be discovered via
 * {@code server/discover} — while producing byte-identical tool output to the
 * legacy session path (mode parity).
 */
public class McpStatelessDialectTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String VERSION = "2026-07-28";

    private McpProtocolHandler handler;
    private AtmosphereResource resource;
    private AtmosphereRequest request;

    public static class TestMcpServer {

        @McpTool(name = "greet", description = "Greet a person")
        public String greet(@McpParam(name = "name", description = "Person's name") String name) {
            return "Hello, " + name + "!";
        }

        @McpResource(uri = "test://data/status", name = "Status",
                description = "Server status", mimeType = "application/json")
        public String status() {
            return "{\"status\":\"ok\"}";
        }

        @McpPrompt(name = "analyze", description = "Analyze data")
        public List<McpMessage> analyze(
                @McpParam(name = "topic", description = "Topic to analyze") String topic) {
            return List.of(McpMessage.system("You are an analyst."),
                    McpMessage.user("Analyze: " + topic));
        }
    }

    @BeforeEach
    public void setUp() {
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());
        handler = new McpProtocolHandler("test-server", "1.0.0", registry, mock(AtmosphereConfig.class));

        resource = mock(AtmosphereResource.class);
        request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("stateless-uuid");
    }

    /** A stateless _meta envelope carrying the 2026-07-28 protocol version. */
    private static String meta() {
        return """
                "_meta":{
                    "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                    "io.modelcontextprotocol/clientInfo":{"name":"stateless-client","version":"1.0"},
                    "io.modelcontextprotocol/clientCapabilities":{}
                }""";
    }

    // ── No handshake, no session ─────────────────────────────────────────

    @Test
    public void testToolsCallWithoutHandshakeSucceeds() throws Exception {
        // No initialize was ever sent. The request stands alone on its _meta.
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"greet\",\"arguments\":{\"name\":\"World\"}," + meta() + "}}";

        var node = mapper.readTree(handler.handleMessage(resource, req));
        var result = node.get("result");
        assertNotNull(result, "stateless tools/call must succeed with no prior handshake");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("Hello, World!", result.get("content").get(0).get("text").stringValue());
    }

    @Test
    public void testStatelessRequestNeverCreatesSession() {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"greet\",\"arguments\":{\"name\":\"World\"}," + meta() + "}}";

        handler.handleMessage(resource, req);

        // The stateless dialect must not stash any session on the request — that
        // is what lets any instance behind a round-robin LB serve any request.
        verify(request, never()).setAttribute(anyString(), any());
    }

    @Test
    public void testResultCarriesResultTypeComplete() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{" + meta() + "}}";

        var node = mapper.readTree(handler.handleMessage(resource, req));
        var result = node.get("result");
        assertEquals("complete", result.get("resultType").stringValue(),
                "servers on 2026-07-28 MUST stamp resultType on every result");
        assertTrue(result.get("tools").isArray());
        assertEquals(1, result.get("tools").size());
    }

    // ── server/discover ──────────────────────────────────────────────────

    @Test
    public void testServerDiscover() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"server/discover\",\"params\":{" + meta() + "}}";

        var node = mapper.readTree(handler.handleMessage(resource, req));
        var result = node.get("result");
        assertNotNull(result);
        assertEquals("complete", result.get("resultType").stringValue());

        var versions = result.get("supportedVersions");
        assertTrue(versions.isArray());
        assertEquals(VERSION, versions.get(0).stringValue(), "stateless revision advertised first");

        var caps = result.get("capabilities");
        assertTrue(caps.has("tools"));
        assertTrue(caps.has("resources"));
        assertTrue(caps.has("prompts"));
        // Stateless does not serve session tasks/subscriptions yet — must not advertise them.
        assertNull(caps.get("tasks"), "stateless discovery must not over-advertise tasks");

        assertEquals("test-server", result.get("serverInfo").get("name").stringValue());

        // DiscoverResult extends CacheableResult: ttlMs + cacheScope are required.
        // Runtime registry mutation means caps are not durably cacheable → ttlMs 0.
        assertEquals(0, result.get("ttlMs").asInt());
        assertEquals("public", result.get("cacheScope").stringValue());
    }

    @Test
    public void testServerDiscoverWorksWithoutPinnedVersion() throws Exception {
        // A client may call discover before it knows which version to pin.
        var req = """
                {"jsonrpc":"2.0","id":4,"method":"server/discover","params":{}}""";

        var node = mapper.readTree(handler.handleMessage(resource, req));
        assertNotNull(node.get("result"));
        assertNull(node.get("error"));
        assertEquals(VERSION, node.get("result").get("supportedVersions").get(0).stringValue());
    }

    // ── Version gate ───────────────────────────────────────────────────────

    @Test
    public void testUnsupportedProtocolVersion() throws Exception {
        var req = """
                {"jsonrpc":"2.0","id":5,"method":"tools/list","params":{
                    "_meta":{
                        "io.modelcontextprotocol/protocolVersion":"2099-01-01",
                        "io.modelcontextprotocol/clientInfo":{"name":"c","version":"1"},
                        "io.modelcontextprotocol/clientCapabilities":{}
                    }
                }}""";

        var node = mapper.readTree(handler.handleMessage(resource, req));
        var error = node.get("error");
        assertNotNull(error);
        assertEquals(-32004, error.get("code").asInt(), "UNSUPPORTED_PROTOCOL_VERSION");
        assertEquals("2099-01-01", error.get("data").get("requested").stringValue());
        assertEquals(VERSION, error.get("data").get("supported").get(0).stringValue());
    }

    // ── Mode parity: same output across dialects ─────────────────────────

    @Test
    public void testModeParityWithSessionDialect() throws Exception {
        // Legacy session path: handshake, then a plain tools/call (no _meta).
        var init = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-11-25",
                    "clientInfo":{"name":"legacy","version":"0.1"},
                    "capabilities":{}
                }}""";
        handler.handleMessage(resource, init);
        var legacyCall = """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                    "name":"greet","arguments":{"name":"World"}
                }}""";
        var legacy = mapper.readTree(handler.handleMessage(resource, legacyCall));

        // Stateless path: same tool, driven by _meta, no handshake.
        var statelessCall = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"greet\",\"arguments\":{\"name\":\"World\"}," + meta() + "}}";
        var stateless = mapper.readTree(handler.handleMessage(resource, statelessCall));

        // Identical execution result — the only difference is the envelope.
        assertEquals(legacy.get("result").get("content").get(0).get("text").stringValue(),
                stateless.get("result").get("content").get(0).get("text").stringValue());
        assertEquals(legacy.get("result").get("isError").asBoolean(),
                stateless.get("result").get("isError").asBoolean());
        // Envelope difference: stateless stamps resultType, legacy does not.
        assertEquals("complete", stateless.get("result").get("resultType").stringValue());
        assertNull(legacy.get("result").get("resultType"));
    }

    // ── Session-only methods are not served statelessly ──────────────────

    @Test
    public void testSessionOnlyMethodRejectedStatelessly() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tasks/list\",\"params\":{" + meta() + "}}";

        var node = mapper.readTree(handler.handleMessage(resource, req));
        assertNotNull(node.get("error"));
        assertEquals(-32601, node.get("error").get("code").asInt(),
                "tasks/list is session-scoped and not part of the 2026-07-28 stateless dialect");
    }

    // ── SEP-2549 cache metadata on cacheable results ─────────────────────

    @Test
    public void testCacheMetadataOnListAndReadResults() throws Exception {
        // tools/list, resources/list, resources/read, prompts/list are
        // CacheableResult per schema → every one MUST carry ttlMs + cacheScope.
        for (var req : List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + meta() + "}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\",\"params\":{" + meta() + "}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\",\"params\":{"
                        + "\"uri\":\"test://data/status\"," + meta() + "}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\",\"params\":{" + meta() + "}}")) {
            var result = mapper.readTree(handler.handleMessage(resource, req)).get("result");
            assertNotNull(result, () -> "no result for: " + req);
            assertEquals("complete", result.get("resultType").stringValue());
            assertEquals(0, result.get("ttlMs").asInt(), () -> "default ttlMs is 0 for: " + req);
            assertEquals("public", result.get("cacheScope").stringValue(), () -> "cacheScope for: " + req);
        }
    }

    @Test
    public void testNonCacheableResultsHaveNoCacheFields() throws Exception {
        // tools/call is not a CacheableResult — resultType only, no ttlMs.
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"greet\",\"arguments\":{\"name\":\"World\"}," + meta() + "}}";
        var result = mapper.readTree(handler.handleMessage(resource, req)).get("result");
        assertEquals("complete", result.get("resultType").stringValue());
        assertNull(result.get("ttlMs"), "tools/call result is not cacheable");
        assertNull(result.get("cacheScope"));
    }

    @Test
    public void testConfiguredCacheTtlIsAdvertised() throws Exception {
        // A deployment with a static catalog can opt into a cache window via the
        // init-param; it must surface as ttlMs on cacheable results.
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(McpProtocolHandler.CACHE_TTL_INIT_PARAM)).thenReturn("60000");
        var registry = new McpRegistry();
        registry.scan(new TestMcpServer());
        var tunedHandler = new McpProtocolHandler("test-server", "1.0.0", registry, config);

        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + meta() + "}}";
        var result = mapper.readTree(tunedHandler.handleMessage(resource, req)).get("result");
        assertEquals(60000, result.get("ttlMs").asInt());
        assertEquals("public", result.get("cacheScope").stringValue());
    }

    // ── SEP-414 trace context propagation does not break dispatch ────────

    // Raw OpenTelemetry AttributeKey in the Mockito matcher is unavoidable
    // (third-party generic), same as McpTracingTest's class-level suppression.
    @SuppressWarnings("unchecked")
    @Test
    public void testTraceContextInMetaDoesNotBreakDispatch() throws Exception {
        // A real McpTracing (mock tracer) is attached; a request carrying W3C
        // trace context in _meta must dispatch normally with the scope applied.
        // (The W3C propagation itself is asserted in McpTracingTest.)
        var tracer = mock(io.opentelemetry.api.trace.Tracer.class);
        var spanBuilder = mock(io.opentelemetry.api.trace.SpanBuilder.class);
        var span = mock(io.opentelemetry.api.trace.Span.class);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(any(io.opentelemetry.api.common.AttributeKey.class), any()))
                .thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(io.opentelemetry.context.Scope.noop());
        handler.setTracing(new org.atmosphere.mcp.runtime.McpTracing(tracer));

        var req = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"greet","arguments":{"name":"Trace"},
                    "_meta":{
                        "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                        "io.modelcontextprotocol/clientInfo":{"name":"c","version":"1"},
                        "io.modelcontextprotocol/clientCapabilities":{},
                        "traceparent":"00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01"
                    }
                }}""";
        var result = mapper.readTree(handler.handleMessage(resource, req)).get("result");
        assertNotNull(result, "dispatch with trace context must still produce a result");
        assertEquals("Hello, Trace!", result.get("content").get(0).get("text").stringValue());
    }
}
