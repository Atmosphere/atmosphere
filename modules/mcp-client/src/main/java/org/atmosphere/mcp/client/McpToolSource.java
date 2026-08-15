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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges a remote MCP (Model Context Protocol) server into Atmosphere's
 * framework-agnostic tool layer. {@link #tools()} returns the remote server's
 * advertised tools as {@link ToolDefinition} instances whose
 * {@link org.atmosphere.ai.tool.ToolExecutor} round-trips arguments to the
 * remote MCP server via {@link McpSyncClient#callTool}, so any
 * {@link org.atmosphere.ai.AgentRuntime} that honors
 * {@link org.atmosphere.ai.AgentExecutionContext#tools()} can invoke remote
 * MCP tools without any per-runtime wiring.
 *
 * <p>This is the outbound counterpart to {@code atmosphere-mcp}'s server-side
 * {@code McpAgentRegistration}, which exposes locally-defined {@code @AiTool}
 * methods as MCP tools to external clients. {@code McpToolSource} is the
 * inverse — it consumes a remote server's tools as a tool source for the
 * local agent.</p>
 *
 * <p>Lifecycle: the source connects and lists tools at construction time. The
 * caller owns the lifecycle and must call {@link #close()} during shutdown
 * (typically wired into {@code @PreDestroy} for Spring or
 * {@code DisposableBean}). Reconnection on transport failure is the caller's
 * responsibility — the SDK's {@code McpSyncClient} surfaces transport errors
 * by throwing from {@code callTool}; this source surfaces them as checked
 * exceptions in {@link org.atmosphere.ai.tool.ToolExecutor#execute}.</p>
 *
 * <h2>Usage (Spring Boot)</h2>
 * <pre>{@code
 * @Bean
 * McpToolSource remoteTools() {
 *     return McpToolSource.connect(URI.create("http://localhost:8083"));
 * }
 *
 * @Prompt
 * public void onPrompt(String message, StreamingSession session,
 *                      McpToolSource remoteTools) {
 *     var ctx = AgentExecutionContext.builder()
 *             .message(message)
 *             .tools(remoteTools.tools())
 *             .build();
 *     runtime.execute(ctx, session);
 * }
 * }</pre>
 *
 * @see <a href="https://platform.claude.com/docs/en/managed-agents/overview">Anthropic Managed Agents</a> — the Claude-managed equivalent that wires remote MCP servers in by default
 */
public final class McpToolSource implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolSource.class);

    private final McpSyncClient client;
    private final List<ToolDefinition> tools;
    private final String endpoint;
    private final Map<String, McpToolMetrics> metrics;
    private final Map<String, McpToolBreaker> breakers;

    private McpToolSource(McpSyncClient client, List<ToolDefinition> tools, String endpoint,
                          Map<String, McpToolMetrics> metrics,
                          Map<String, McpToolBreaker> breakers) {
        this.client = client;
        this.tools = tools;
        this.endpoint = endpoint;
        this.metrics = metrics;
        this.breakers = breakers;
    }

    /**
     * Connect to a remote MCP server over Streamable HTTP and load its
     * advertised tools. Streamable HTTP is the modern MCP transport — it's
     * what {@code atmosphere-mcp} recommends and what Anthropic's reference
     * servers ship today. The legacy server-sent-events ({@code +/sse})
     * transport is intentionally not used here because mature MCP servers
     * (atmosphere-mcp included) emit framing/keepalive bytes that the SDK's
     * strict SSE parser rejects, while Streamable HTTP is widely interop-tested.
     *
     * <p>The URI's path is used as the MCP endpoint. If absent, the SDK
     * default ({@code /mcp}) is used.</p>
     *
     * @param endpoint base URL (with optional explicit MCP path) of the MCP
     *                 server (e.g. {@code http://localhost:8083/atmosphere/mcp})
     * @return a connected source whose {@link #tools()} reflects the server's
     *         advertised tools at the time of connection
     * @throws RuntimeException if the connection or initial {@code listTools}
     *                          call fails — the caller should treat this as
     *                          an unavailable tool source and fail fast
     */
    public static McpToolSource connect(java.net.URI endpoint) {
        return connect(endpoint, McpClientOptions.defaults());
    }

    /**
     * Connect over Streamable HTTP with {@link McpClientOptions} — per-server
     * tool filtering/renaming for collision-free aggregation, plus optional
     * elicitation/sampling callback handlers.
     *
     * @param endpoint base URL (with optional explicit MCP path)
     * @param options  filtering, renaming, and callback configuration
     */
    public static McpToolSource connect(java.net.URI endpoint, McpClientOptions options) {
        Objects.requireNonNull(endpoint, "endpoint");
        var path = endpoint.getPath();
        var baseUrl = stripPath(endpoint);
        var builder = HttpClientStreamableHttpTransport.builder(baseUrl);
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            builder.endpoint(path);
        }
        return connect(builder.build(), endpoint.toString(), options);
    }

    private static String stripPath(java.net.URI uri) {
        // The SDK's transport builder takes a *base* URL and appends the
        // SSE path itself; passing the full URL with path leads it to
        // double-append. Reconstruct the base with scheme + authority only.
        var scheme = uri.getScheme();
        var authority = uri.getAuthority();
        if (scheme == null || authority == null) {
            return uri.toString();
        }
        return scheme + "://" + authority;
    }

    /**
     * Connect using a caller-supplied transport. Use this when you need stdio
     * (subprocess MCP servers), a customized HTTP client, or any non-default
     * transport setup. The {@code label} appears in log lines and tool error
     * messages so multiple sources can be distinguished.
     */
    public static McpToolSource connect(McpClientTransport transport, String label) {
        return connect(transport, label, McpClientOptions.defaults());
    }

    /**
     * Connect using a caller-supplied transport with {@link McpClientOptions}.
     * Tools are filtered and renamed per the options (rename is display-only —
     * the executor still calls the server's original tool name), and any
     * elicitation/sampling handlers are wired into the client with their
     * capabilities advertised during {@code initialize}.
     */
    public static McpToolSource connect(McpClientTransport transport, String label,
                                        McpClientOptions options) {
        Objects.requireNonNull(transport, "transport");
        var opts = options == null ? McpClientOptions.defaults() : options;
        var spec = McpClient.sync(transport);
        var capabilities = McpSchema.ClientCapabilities.builder();
        var advertise = false;
        if (opts.elicitationHandler() != null) {
            spec.elicitation(opts.elicitationHandler()::apply);
            capabilities.elicitation();
            advertise = true;
        }
        if (opts.samplingHandler() != null) {
            spec.sampling(opts.samplingHandler()::apply);
            capabilities.sampling();
            advertise = true;
        }
        if (advertise) {
            spec.capabilities(capabilities.build());
        }
        var client = spec.build();
        // Ownership: this method created the client, so the transport is
        // ours to close on any failure path until the constructed
        // McpToolSource takes over ownership on the success branch
        // (Correctness Invariant #1). Without this guard, a failure inside
        // initialize() or listTools() leaks the underlying transport
        // (subprocess pipes, sockets, HTTP connection pool) for the lifetime
        // of the JVM.
        try {
            client.initialize();
            var listResult = client.listTools();
            var tools = listResult == null ? List.<McpSchema.Tool>of() : listResult.tools();
            var defs = new ArrayList<ToolDefinition>(tools.size());
            Map<String, McpToolMetrics> perTool = new ConcurrentHashMap<>();
            Map<String, McpToolBreaker> perToolBreakers = new ConcurrentHashMap<>();
            for (var tool : tools) {
                if (!opts.includes(tool.name())) {
                    LOG.debug("McpToolSource[{}] filtered out tool '{}'", label, tool.name());
                    continue;
                }
                var toolMetrics = new McpToolMetrics();
                perTool.put(tool.name(), toolMetrics);
                var breaker = new McpToolBreaker(opts.breakerFailureThreshold(),
                        opts.breakerOpenMillis());
                perToolBreakers.put(tool.name(), breaker);
                defs.add(rename(toDefinition(tool, client, label, toolMetrics, breaker),
                        opts.displayName(tool.name())));
            }
            LOG.info("McpToolSource connected to {} — loaded {} tool(s)", label, defs.size());
            return new McpToolSource(client, Collections.unmodifiableList(defs), label,
                    Collections.unmodifiableMap(perTool),
                    Collections.unmodifiableMap(perToolBreakers));
        } catch (RuntimeException re) {
            try {
                client.closeGracefully();
            } catch (RuntimeException closeFailure) {
                re.addSuppressed(closeFailure);
            }
            throw re;
        }
    }

    /**
     * Return a copy of {@code def} renamed to {@code displayName}, preserving
     * the executor (which still calls the server's original tool name). A no-op
     * when the name is unchanged.
     */
    static ToolDefinition rename(ToolDefinition def, String displayName) {
        if (displayName == null || displayName.equals(def.name())) {
            return def;
        }
        return new ToolDefinition(displayName, def.description(), def.parameters(),
                def.returnType(), def.executor(), def.approvalMessage(),
                def.approvalTimeout(), def.executionTimeout(), def.kind());
    }

    /**
     * Return the remote server's tools as Atmosphere {@link ToolDefinition}s.
     * The list is immutable and reflects the server's advertised tool set at
     * the time of {@link #connect connect}; if the server adds or removes
     * tools at runtime, reconnect to pick them up.
     */
    public List<ToolDefinition> tools() {
        return tools;
    }

    /**
     * Endpoint or label this source was constructed with — useful for
     * logging, metrics, and identifying which remote server contributed
     * which tools when multiple sources are wired into a single agent.
     */
    public String endpoint() {
        return endpoint;
    }

    /**
     * Per-tool dispatch metrics keyed by tool name. The map is unmodifiable
     * but the {@link McpToolMetrics} entries mutate as the executor records
     * calls. Operators / admin surfaces read this to observe tool usage
     * without instrumenting the runtime.
     */
    public Map<String, McpToolMetrics> metrics() {
        return metrics;
    }

    /**
     * Per-tool circuit breakers keyed by the server's original tool name. The
     * map is unmodifiable but the {@link McpToolBreaker} entries mutate as
     * calls succeed or fail — operators and tests read
     * {@link McpToolBreaker#state()} to see which remote tools are currently
     * short-circuited.
     */
    public Map<String, McpToolBreaker> breakers() {
        return breakers;
    }

    @Override
    public void close() {
        try {
            client.closeGracefully();
        } catch (RuntimeException ex) {
            LOG.warn("McpToolSource[{}] close failed: {}", endpoint, ex.toString());
        }
    }

    private static ToolDefinition toDefinition(McpSchema.Tool tool, McpSyncClient client,
                                                String label, McpToolMetrics metrics,
                                                McpToolBreaker breaker) {
        var builder = ToolDefinition.builder(tool.name(), descriptionOf(tool));
        // parameter(ToolParameter) preserves the structural facets (enum,
        // array items, nested object properties) read off the remote server's
        // JSON Schema; the flat 4-arg overload would drop them.
        for (var param : extractParameters(tool)) {
            builder.parameter(param);
        }
        builder.executor(args -> invokeRemote(tool.name(), args, client, label, metrics, breaker));
        return builder.build();
    }

    private static String descriptionOf(McpSchema.Tool tool) {
        var description = tool.description();
        if (description == null || description.isBlank()) {
            // ToolDefinition rejects blank descriptions — fall back to the
            // tool name so the registration still succeeds. The model still
            // gets a meaningful identifier even though the server didn't
            // ship a docstring.
            return tool.name();
        }
        return description;
    }

    private static List<ToolParameter> extractParameters(McpSchema.Tool tool) {
        // MCP SDK 2.0.0 hands back the raw JSON Schema object as a Map rather than
        // the JsonSchema record it used through 1.x, so the facets are read by key.
        // Reading the map directly is also more faithful: the record modelled a
        // fixed set of facets and dropped anything else the server sent.
        var schema = tool.inputSchema();
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        if (!(schema.get("properties") instanceof Map<?, ?> properties) || properties.isEmpty()) {
            return List.of();
        }
        var required = schema.get("required") instanceof List<?> list ? list : List.of();
        var out = new ArrayList<ToolParameter>(properties.size());
        for (var entry : properties.entrySet()) {
            var key = String.valueOf(entry.getKey());
            out.add(toParameter(key, entry.getValue(), required.contains(key)));
        }
        return out;
    }

    /**
     * Translate one JSON Schema property from a remote server's
     * {@code inputSchema} into a {@link ToolParameter}, carrying the structural
     * facets through: {@code enum} value sets, {@code items.type} for arrays,
     * and nested {@code properties}/{@code required} for objects. Before this,
     * only {@code type} and {@code description} survived, so a remote tool's
     * enum or nested-object contract was flattened away before the model ever
     * saw it.
     */
    private static ToolParameter toParameter(String name, Object raw, boolean required) {
        if (!(raw instanceof Map<?, ?> propMap)) {
            return new ToolParameter(name, "", "string", required);
        }
        var type = propMap.get("type") instanceof String s && !s.isBlank() ? s : "string";
        var description = propMap.get("description") instanceof String s ? s : "";

        var enumValues = new ArrayList<String>();
        if (propMap.get("enum") instanceof List<?> rawEnum) {
            for (var value : rawEnum) {
                if (value != null) {
                    enumValues.add(value.toString());
                }
            }
        }

        ToolParameter items = null;
        if (propMap.get("items") instanceof Map<?, ?> rawItems) {
            // Recurse, so a remote array-of-objects keeps its element schema
            // rather than being reduced to the element's type name.
            items = toParameter("item", rawItems, true);
        }

        var nested = new ArrayList<ToolParameter>();
        if (propMap.get("properties") instanceof Map<?, ?> nestedProps) {
            var nestedRequired = propMap.get("required") instanceof List<?> list
                    ? list : List.of();
            for (var entry : nestedProps.entrySet()) {
                var nestedName = String.valueOf(entry.getKey());
                nested.add(toParameter(nestedName, entry.getValue(),
                        nestedRequired.contains(nestedName)));
            }
        }
        return new ToolParameter(name, description, type, required, enumValues, items, nested);
    }

    private static Object invokeRemote(String toolName, Map<String, Object> arguments,
                                       McpSyncClient client, String label, McpToolMetrics metrics,
                                       McpToolBreaker breaker) {
        if (!breaker.tryAcquire()) {
            // Fail fast instead of paying another request timeout against a
            // server that has failed its last N calls (Invariant #3). Returned
            // as a tool-error string, not thrown, so the agent loop can pick a
            // different tool rather than aborting the turn.
            LOG.debug("McpToolSource[{}] breaker open for tool '{}' — short-circuiting",
                    label, toolName);
            metrics.recordError();
            return "tool error: MCP tool '" + toolName + "' on " + label
                    + " is temporarily unavailable (circuit breaker open after "
                    + breaker.consecutiveFailures() + " consecutive failures)";
        }
        var startNanos = System.nanoTime();
        McpSchema.CallToolResult result;
        try {
            // Request construction stays inside the try: a throw between
            // tryAcquire() and a recorded outcome would strand the half-open
            // probe slot and wedge the breaker permanently (Invariant #2 —
            // every path after acquiring must record success or failure).
            result = client.callTool(new McpSchema.CallToolRequest(
                    toolName, arguments == null ? Map.of() : arguments, null));
        } catch (RuntimeException ex) {
            metrics.recordCall((System.nanoTime() - startNanos) / 1_000_000L);
            metrics.recordError();
            breaker.recordFailure();
            throw new McpToolInvocationException(
                    "MCP tool '" + toolName + "' on " + label + " failed: " + ex.getMessage(), ex);
        }
        metrics.recordCall((System.nanoTime() - startNanos) / 1_000_000L);
        if (result == null) {
            breaker.recordSuccess();
            return "";
        }
        if (Boolean.TRUE.equals(result.isError())) {
            metrics.recordError();
            breaker.recordFailure();
            // Surface server-reported tool errors to the model as a string so
            // the loop can decide whether to retry, fall through, or report
            // the failure to the user. Throwing here would abort the loop;
            // returning the error text matches how a local @AiTool that
            // throws would surface to the model after ToolExecutionHelper
            // wraps the exception.
            return "tool error: " + flatten(result.content());
        }
        breaker.recordSuccess();
        return flatten(result.content());
    }

    private static String flatten(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (var part : content) {
            if (part instanceof McpSchema.TextContent text) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text.text());
            }
            // ImageContent / EmbeddedResource intentionally elided: the
            // ToolExecutor contract is "result will be serialized to JSON
            // and sent back to the model" and most LLMs don't accept binary
            // tool returns. Callers that need image/resource passthrough
            // should subclass and override invokeRemote.
        }
        return sb.toString();
    }

    /** Thrown when a remote MCP tool invocation fails. */
    public static final class McpToolInvocationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public McpToolInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
