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
package org.atmosphere.ai.crewai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback-only HTTP callback server used by the {@link CrewAiAgentRuntime}
 * tool bridge. The Python sidecar POSTs to this server whenever CrewAI
 * invokes a Java-side {@code @AiTool}; this server looks the tool up by name,
 * runs it through {@link ToolExecutionHelper#executeWithApproval} (so approval
 * gates, validation, governance, and authorisation all apply identically to
 * the in-process tool path), and returns the formatted result string as JSON.
 *
 * <h3>Security posture</h3>
 * <p>Binds <strong>127.0.0.1 only</strong> with an ephemeral port — never
 * 0.0.0.0 — so the surface is unreachable from outside the host.</p>
 *
 * <p>Loopback is not on its own an authorisation boundary: every process on
 * the host can reach an ephemeral loopback port, so bind address alone would
 * let any local process invoke Java {@code @AiTool}s. Each server therefore
 * mints a fresh 256-bit token in {@link #start()} and rejects any callback
 * whose {@code X-Atmosphere-Tool-Token} header does not match it with
 * {@code 401} — checked before the body is read, so an unauthorised caller
 * cannot reach tool lookup or the payload parser (Correctness Invariant #6 —
 * every mutating surface requires explicit authorisation; default deny).</p>
 *
 * <p>The token is handed to the sidecar out-of-band on the
 * {@code POST /v1/sessions} body ({@code tool_callback_token}) over the same
 * loopback hop, and is never logged. It is per-server and per-run: a leaked
 * token dies with the execution that minted it.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>One server per active execution. Started in
 * {@link CrewAiAgentRuntime#doExecute}, stopped on every terminal path so
 * resources never leak (Correctness Invariant #1 — Ownership, #2 — Terminal
 * Path Completeness). {@link #stop()} is idempotent via a CAS guard.</p>
 *
 * <h3>Backpressure</h3>
 * <p>Handler thread pool is bounded (default 8 threads). If saturation kicks
 * in, the server returns HTTP 503 with a JSON error so the sidecar (and thus
 * CrewAI) sees a clean rejection rather than a stall (Correctness
 * Invariant #3 — Backpressure).</p>
 */
public final class ToolCallbackServer {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallbackServer.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String CALLBACK_PATH = "/v1/tools/call";
    private static final int DEFAULT_POOL_SIZE = 8;
    private static final int MAX_PAYLOAD_BYTES = 1 << 20; // 1 MiB ceiling per call

    /** Header the sidecar must present on every tool callback. */
    static final String TOKEN_HEADER = "X-Atmosphere-Tool-Token";

    /** 256 bits — well beyond brute force over a per-run loopback listener. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, ToolDefinition> toolRegistry;
    private final StreamingSession session;
    private final ApprovalStrategy approvalStrategy;
    private final int poolSize;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int port = -1;
    private volatile byte[] tokenBytes;
    private volatile String token;

    /**
     * Construct a callback server. Callers MUST {@link #start()} before
     * {@link #callbackUrl()} returns a meaningful value.
     *
     * @param toolRegistry tools available to the sidecar, keyed by name; never
     *                     {@code null}
     * @param session      streaming session for ToolStart/ToolResult emission;
     *                     may be {@code null} when the runtime path opts out of
     *                     UI eventing
     * @param strategy     approval strategy threaded into
     *                     {@link ToolExecutionHelper#executeWithApproval}; may
     *                     be {@code null} (executor fails closed for tools that
     *                     declare {@code @RequiresApproval})
     */
    public ToolCallbackServer(Map<String, ToolDefinition> toolRegistry,
                              StreamingSession session,
                              ApprovalStrategy strategy) {
        this(toolRegistry, session, strategy, DEFAULT_POOL_SIZE);
    }

    /** Visible for tests so the pool size can be tuned to exercise saturation. */
    ToolCallbackServer(Map<String, ToolDefinition> toolRegistry,
                       StreamingSession session,
                       ApprovalStrategy strategy,
                       int poolSize) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.session = session;
        this.approvalStrategy = strategy;
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive");
        }
        this.poolSize = poolSize;
    }

    /**
     * Bind to {@code 127.0.0.1} on an ephemeral port and begin accepting
     * requests. Subsequent calls are no-ops so the runtime's try/finally
     * scaffolding cannot accidentally double-start.
     */
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // Mint before the listener accepts anything, so there is no window in
        // which the context is reachable with a null token (which would read
        // as "no token configured" and fail open).
        var secret = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(secret);
        var hex = HexFormat.of().formatHex(secret);
        this.token = hex;
        this.tokenBytes = hex.getBytes(StandardCharsets.UTF_8);

        var bind = new InetSocketAddress("127.0.0.1", 0);
        var srv = HttpServer.create(bind, 0);
        srv.createContext(CALLBACK_PATH, new CallbackHandler());
        var pool = Executors.newFixedThreadPool(poolSize, r -> {
            var t = new Thread(r, "crewai-tool-callback");
            t.setDaemon(true);
            return t;
        });
        srv.setExecutor(pool);
        srv.start();
        this.server = srv;
        this.executor = pool;
        this.port = srv.getAddress().getPort();
        logger.debug("CrewAI tool callback server bound to 127.0.0.1:{} ({} tool(s))",
                port, toolRegistry.size());
    }

    /**
     * The callback URL the sidecar should POST to. {@code null} until
     * {@link #start()} has bound the listener.
     */
    public String callbackUrl() {
        if (port < 0) {
            return null;
        }
        return "http://127.0.0.1:" + port + CALLBACK_PATH;
    }

    /**
     * The shared secret the sidecar must echo in the {@link #TOKEN_HEADER}
     * header on every callback. {@code null} until {@link #start()} has minted
     * it. Hand this to the sidecar over the loopback session hop only — never
     * log it and never put it in a URL (URLs land in access logs and process
     * listings).
     */
    public String token() {
        return token;
    }

    /** Loopback host the listener is bound to — exposed for assertion in tests. */
    String host() {
        if (server == null) {
            return null;
        }
        return server.getAddress().getAddress().getHostAddress();
    }

    /**
     * Stop the listener. Idempotent — calling {@code stop()} more than once
     * is safe and the second call is a no-op. Waits up to two seconds for
     * in-flight handlers to drain before forcing the executor to shut down.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        // HttpServer.stop blocks up to its delay arg waiting for in-flight
        // exchanges; we cap it at 2 seconds so a stuck tool can't pin the
        // runtime's terminal path. After that, the executor receives an
        // interrupt-shutdown.
        var srv = this.server;
        if (srv != null) {
            srv.stop(2);
        }
        var pool = this.executor;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.debug("CrewAI tool callback server stopped (port {})", port);
    }

    private final class CallbackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, 405,
                            Map.of("error", "method not allowed: "
                                    + exchange.getRequestMethod()));
                    return;
                }
                // Authorisation first: an unauthorised caller must not reach
                // tool lookup, the body parser, or the pool-saturation probe.
                // Default deny — a null expected token means start() has not
                // minted one, and we refuse rather than wave the caller past.
                if (!authorized(exchange)) {
                    logger.warn("rejected unauthorised tool callback from {}",
                            exchange.getRemoteAddress());
                    writeJson(exchange, 401, Map.of("error", "unauthorized"));
                    return;
                }
                // Pool saturation: if the underlying ThreadPoolExecutor's queue
                // refuses additional work, the framework's HttpServer wraps it
                // as RejectedExecutionException and ends up surfacing as a
                // dropped connection. We can also explicitly inspect the pool
                // and signal 503 BEFORE running the tool so the sidecar gets a
                // clean rejection (Correctness Invariant #3). The framework
                // pool is fixed-size with an unbounded queue by default;
                // bounded backpressure is enforced by the per-server pool size.
                if (executor instanceof ThreadPoolExecutor tpe
                        && tpe.getActiveCount() >= poolSize
                        && tpe.getQueue().size() > poolSize) {
                    writeJson(exchange, 503,
                            Map.of("error", "tool callback server busy"));
                    return;
                }

                JsonNode body;
                try {
                    body = readJsonBody(exchange);
                } catch (RuntimeException parse) {
                    logger.trace("malformed tool-callback payload: {}", parse.toString());
                    writeJson(exchange, 400,
                            Map.of("error", "malformed JSON: " + parse.getMessage()));
                    return;
                }
                if (body == null || !body.isObject()) {
                    writeJson(exchange, 400,
                            Map.of("error", "expected JSON object body"));
                    return;
                }

                var name = body.path("name").asString("");
                if (name.isBlank()) {
                    writeJson(exchange, 400,
                            Map.of("error", "missing required field 'name'"));
                    return;
                }
                var tool = toolRegistry.get(name);
                if (tool == null) {
                    // Tool-execution errors travel back as HTTP 200 with an
                    // {error} field so the sidecar can route them to CrewAI
                    // as tool-failure replies (per wire protocol contract).
                    writeJson(exchange, 200,
                            Map.of("error", "unknown tool: " + name));
                    return;
                }
                var args = parseArguments(body.path("arguments"));

                String result;
                try {
                    result = ToolExecutionHelper.executeWithApproval(
                            name, tool, args, session, approvalStrategy);
                } catch (RuntimeException e) {
                    logger.warn("tool {} execution threw uncaught exception", name, e);
                    writeJson(exchange, 200,
                            Map.of("error", e.getClass().getSimpleName()
                                    + (e.getMessage() != null
                                        ? ": " + e.getMessage() : "")));
                    return;
                }

                writeJson(exchange, 200, Map.of("result", result != null ? result : ""));
            } catch (RejectedExecutionException reject) {
                // Pool full while running — backpressure rejection. Surface
                // 503 so the sidecar fails fast.
                logger.warn("tool callback server pool rejected request: {}",
                        reject.getMessage());
                writeJson(exchange, 503,
                        Map.of("error", "tool callback server busy"));
            }
        }
    }

    /**
     * Constant-time comparison of the presented header against the minted
     * token. {@link MessageDigest#isEqual} does not short-circuit on the
     * first differing byte, so response latency does not leak a prefix
     * oracle. Fails closed on a missing header and on a server that never
     * minted a token.
     */
    private boolean authorized(HttpExchange exchange) {
        var expected = tokenBytes;
        if (expected == null) {
            return false;
        }
        var presented = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected);
    }

    private static JsonNode readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            // Cap body size — defensive boundary check (Correctness Invariant
            // #4 — Boundary Safety). A misbehaving sidecar/CrewAI tool call
            // should not be able to push gigabytes of "arguments" into the
            // process.
            var bytes = in.readNBytes(MAX_PAYLOAD_BYTES + 1);
            if (bytes.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                        "payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
            }
            return MAPPER.readTree(bytes);
        }
    }

    private static Map<String, Object> parseArguments(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            // Boundary safety: if the sidecar sends a non-object, treat as
            // empty so the tool's argument validator fires cleanly (and the
            // model gets a recoverable error rather than an opaque crash).
            return Map.of();
        }
        var args = new LinkedHashMap<String, Object>();
        var it = node.properties().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            args.put(entry.getKey(), unwrap(entry.getValue()));
        }
        return args;
    }

    private static Object unwrap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isFloat() || node.isDouble()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            var list = new java.util.ArrayList<Object>(node.size());
            for (var child : node) {
                list.add(unwrap(child));
            }
            return list;
        }
        if (node.isObject()) {
            var map = new LinkedHashMap<String, Object>();
            var it = node.properties().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                map.put(entry.getKey(), unwrap(entry.getValue()));
            }
            return map;
        }
        // Fallback: stringify so the executor at least sees the data.
        return node.toString();
    }

    private static void writeJson(HttpExchange exchange, int status,
                                  Map<String, ?> payload) throws IOException {
        // Build a deterministic key order so test assertions on the serialized
        // body are stable across JVMs.
        var ordered = new LinkedHashMap<String, Object>();
        for (var entry : payload.entrySet()) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        byte[] bytes = MAPPER.writeValueAsBytes(ordered);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Snapshot of the tool registry size — useful only for diagnostics.
     */
    public int toolCount() {
        return toolRegistry.size();
    }

    /**
     * Convenience builder for the common case where a runtime translates an
     * {@code AgentExecutionContext.tools()} list into a name → definition map.
     */
    public static Map<String, ToolDefinition> indexByName(
            java.util.Collection<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return Map.of();
        }
        var map = new HashMap<String, ToolDefinition>(tools.size());
        for (var tool : tools) {
            if (tool != null && tool.name() != null) {
                map.put(tool.name(), tool);
            }
        }
        return Map.copyOf(map);
    }
}
