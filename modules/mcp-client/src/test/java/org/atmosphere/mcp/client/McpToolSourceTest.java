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
package org.atmosphere.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for {@link McpToolSource} that exercises the
 * remote-tool → {@code ToolDefinition} translation without spinning up a
 * real MCP server. The integration coverage (real wire round-trip through
 * {@code spring-boot-mcp-server}) lives in the
 * {@code modules/integration-tests} e2e suite.
 *
 * <p>Reflection is used to reach the package-private translator methods so
 * the public API (which requires a live transport) doesn't need to be
 * mocked end-to-end. This keeps the test focused on the schema-translation
 * contract that the e2e spec relies on.</p>
 */
class McpToolSourceTest {

    @Test
    void translatesToolWithRequiredAndOptionalParameters() throws Exception {
        var schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "city", Map.of("type", "string", "description", "City name"),
                        "units", Map.of("type", "string", "description", "metric or imperial")
                ),
                List.of("city"),
                Boolean.FALSE,
                null,
                null);
        var tool = new McpSchema.Tool(
                "get_weather", null, "Look up current weather", schema, null, null, null);

        var defs = invokeTranslate(tool, mock(McpSyncClient.class), "test://server");

        assertEquals(1, defs.size());
        var def = defs.get(0);
        assertEquals("get_weather", def.name());
        assertEquals("Look up current weather", def.description());
        assertEquals(2, def.parameters().size());
        var byName = def.parameters().stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.name(), p -> p));
        assertTrue(byName.get("city").required(), "city is in required[] — must be required");
        assertFalse(byName.get("units").required(), "units is not in required[] — must be optional");
        assertEquals("string", byName.get("city").type());
    }

    @Test
    void blankDescriptionFallsBackToToolName() throws Exception {
        // ToolDefinition rejects blank descriptions — verify the source
        // fills them rather than failing the whole listTools roundtrip when
        // a remote server ships a tool without a docstring.
        var schema = new McpSchema.JsonSchema(
                "object", Map.of(), List.of(), Boolean.FALSE, null, null);
        var tool = new McpSchema.Tool("undocumented_tool", null, "", schema, null, null, null);

        var defs = invokeTranslate(tool, mock(McpSyncClient.class), "test://server");

        assertEquals("undocumented_tool", defs.get(0).description(),
                "blank description must fall back to the tool name");
    }

    @Test
    void executorRoundTripsToCallTool() throws Exception {
        var schema = new McpSchema.JsonSchema(
                "object",
                Map.of("question", Map.of("type", "string")),
                List.of("question"),
                Boolean.FALSE, null, null);
        var tool = new McpSchema.Tool(
                "ask", null, "Ask a question", schema, null, null, null);

        var client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("answer-42")),
                        Boolean.FALSE, null, null));

        var def = invokeTranslate(tool, client, "test://server").get(0);
        var result = def.executor().execute(Map.of("question", "what?"));

        assertEquals("answer-42", result);
    }

    @Test
    void executorSurfacesServerErrorAsToolErrorString() throws Exception {
        // Server-reported tool errors (isError=true) must be returned as a
        // string so the agent loop decides what to do, NOT thrown — throwing
        // would abort the loop. This matches how a local @AiTool failure
        // surfaces after ToolExecutionHelper wraps the exception.
        var schema = new McpSchema.JsonSchema(
                "object", Map.of(), List.of(), Boolean.FALSE, null, null);
        var tool = new McpSchema.Tool("flaky", null, "Sometimes fails", schema, null, null, null);

        var client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("rate limited")),
                        Boolean.TRUE, null, null));

        var def = invokeTranslate(tool, client, "test://server").get(0);
        var result = def.executor().execute(Map.of());

        assertNotNull(result);
        assertTrue(result.toString().startsWith("tool error:"),
                "server-reported errors must be returned as 'tool error: ...' strings");
        assertTrue(result.toString().contains("rate limited"));
    }

    @Test
    void emptyContentReturnsEmptyString() throws Exception {
        var schema = new McpSchema.JsonSchema(
                "object", Map.of(), List.of(), Boolean.FALSE, null, null);
        var tool = new McpSchema.Tool("noop", null, "Returns nothing", schema, null, null, null);

        var client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(), Boolean.FALSE, null, null));

        var result = invokeTranslate(tool, client, "test://server").get(0)
                .executor().execute(Map.of());
        assertEquals("", result);
    }

    @Test
    void executorRecordsMetrics() throws Exception {
        var schema = new McpSchema.JsonSchema(
                "object", Map.of(), List.of(), Boolean.FALSE, null, null);
        var tool = new McpSchema.Tool("counted", null, "metered", schema, null, null, null);

        var client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("ok")), Boolean.FALSE, null, null));

        var metrics = new McpToolMetrics();
        var def = invokeTranslateWithMetrics(tool, client, "test://server", metrics);

        assertEquals(0, metrics.calls());
        def.executor().execute(Map.of());
        assertEquals(1, metrics.calls());
        assertEquals(0, metrics.errors());
        def.executor().execute(Map.of());
        assertEquals(2, metrics.calls());
    }

    @Test
    void executorIncrementsErrorOnServerReportedError() throws Exception {
        var schema = new McpSchema.JsonSchema(
                "object", Map.of(), List.of(), Boolean.FALSE, null, null);
        var tool = new McpSchema.Tool("flaky", null, "fails", schema, null, null, null);

        var client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("rate limited")),
                        Boolean.TRUE, null, null));

        var metrics = new McpToolMetrics();
        var def = invokeTranslateWithMetrics(tool, client, "test://server", metrics);

        def.executor().execute(Map.of());
        assertEquals(1, metrics.calls());
        assertEquals(1, metrics.errors());
    }

    @SuppressWarnings("unchecked")
    private static List<org.atmosphere.ai.tool.ToolDefinition> invokeTranslate(
            McpSchema.Tool tool, McpSyncClient client, String label) throws Exception {
        return List.of(invokeTranslateWithMetrics(tool, client, label, new McpToolMetrics()));
    }

    private static org.atmosphere.ai.tool.ToolDefinition invokeTranslateWithMetrics(
            McpSchema.Tool tool, McpSyncClient client, String label, McpToolMetrics metrics) throws Exception {
        Method translate = McpToolSource.class.getDeclaredMethod(
                "toDefinition", McpSchema.Tool.class, McpSyncClient.class, String.class, McpToolMetrics.class);
        translate.setAccessible(true);
        return (org.atmosphere.ai.tool.ToolDefinition) translate.invoke(null, tool, client, label, metrics);
    }

    /**
     * Regression: when {@code initialize()} throws, the underlying client
     * (and therefore its transport) must be closed. Earlier the failure
     * propagated without closing, leaking the transport for the JVM
     * lifetime (subprocess pipes, sockets, HTTP connection pool).
     *
     * <p>We use a real {@link McpClientTransport} stub whose
     * {@code connect()} returns {@link Mono#error} so {@code initialize()}
     * fails. The transport's {@code close()} (called transitively by
     * {@code closeGracefully}) flips a flag we assert on.</p>
     */
    @Test
    void connectClosesTransportOnInitializeFailure() {
        var closed = new AtomicBoolean(false);
        McpClientTransport transport = new McpClientTransport() {
            @Override
            public Mono<Void> connect(java.util.function.Function<
                    Mono<McpSchema.JSONRPCMessage>,
                    Mono<McpSchema.JSONRPCMessage>> handler) {
                return Mono.error(new IllegalStateException("transport refused"));
            }
            @Override
            public Mono<Void> closeGracefully() {
                closed.set(true);
                return Mono.empty();
            }
            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return Mono.error(new IllegalStateException("transport refused"));
            }
            @Override
            public <T> T unmarshalFrom(Object data, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
                throw new UnsupportedOperationException();
            }
        };

        assertThrows(RuntimeException.class,
                () -> McpToolSource.connect(transport, "leak-test"));
        assertTrue(closed.get(),
                "transport.closeGracefully() must be called when initialize() throws — "
                        + "ownership leak otherwise (Correctness Invariant #1)");
    }

    @Test
    void constructorSurfaceIsAccessible() throws Exception {
        // Smoke test: the public reflective surface compiles. The actual
        // listTools wiring is exercised by the e2e spec against a live
        // MCP server because mocking the transport+session+SDK chain is
        // brittle and adds little signal over the integration test.
        Constructor<?> ctor = McpToolSource.class.getDeclaredConstructor(
                McpSyncClient.class, List.class, String.class, Map.class);
        assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()),
                "constructor must stay private — connect() is the only entry");
    }
}
