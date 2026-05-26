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
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-RPC bridge wiring tests: cover both halves of the seam.
 *
 * <ol>
 *   <li>Java → sidecar: tool descriptors and {@code tool_callback_url}
 *       serialise into the {@code POST /v1/sessions} body, and a non-null
 *       system prompt threads through unchanged.</li>
 *   <li>Sidecar → Java: the {@link ToolCallbackServer} accepts a POST,
 *       looks up the named tool, runs it through
 *       {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval},
 *       and returns a {@code {"result":"..."}} body. Tool-execution errors
 *       come back as HTTP 200 + {@code {"error":...}} (not 5xx) so the
 *       sidecar can route them to CrewAI as recoverable tool failures.</li>
 * </ol>
 *
 * <p>The callback server is exercised stand-alone (without the runtime) so
 * the lifecycle / idempotency / loopback invariants are nailed down with
 * minimal moving parts. The runtime-level integration is covered by the
 * {@code FakeSidecarWithTools} fixture below, which captures the POST body
 * and asserts the wire shape.</p>
 */
class CrewAiToolBridgeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FakeSidecarWithTools sidecar;

    @BeforeEach
    void setUp() throws Exception {
        sidecar = new FakeSidecarWithTools();
        sidecar.start();
        System.setProperty(CrewAiSidecarConfig.SYS_URL, sidecar.url());
        System.setProperty(CrewAiSidecarConfig.SYS_HEALTH_TIMEOUT_MS, "500");
    }

    @AfterEach
    void tearDown() {
        sidecar.stop();
        System.clearProperty(CrewAiSidecarConfig.SYS_URL);
        System.clearProperty(CrewAiSidecarConfig.SYS_HEALTH_TIMEOUT_MS);
        System.clearProperty(CrewAiSidecarConfig.SYS_REQUEST_TIMEOUT_MS);
    }

    // ---- Wire-shape assertions on the start-session POST body ----------

    @Test
    void tools_arePassedToSidecar() throws Exception {
        sidecar.startResponse = doneOnly();
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);

        var executed = new AtomicBoolean();
        var tool = ToolDefinition.builder("lookup_order", "Look up an order by id")
                .parameter("order_id", "The order id", "string", true)
                .executor(args -> {
                    executed.set(true);
                    return "OK";
                })
                .build();
        var session = new RecordingSession();
        runtime.execute(contextWithTools(List.of(tool), null), session);

        assertTrue(session.awaitTerminal(5, TimeUnit.SECONDS));
        var body = MAPPER.readTree(sidecar.lastStartBody.get());
        var tools = body.path("tools");
        assertTrue(tools.isArray(), "tools must be serialised as an array");
        assertEquals(1, tools.size(), "exactly one tool advertised");
        assertEquals("lookup_order", tools.get(0).path("name").asString());
        assertEquals("Look up an order by id", tools.get(0).path("description").asString());
        var params = tools.get(0).path("parameters");
        assertEquals(1, params.size());
        assertEquals("order_id", params.get(0).path("name").asString());
        assertEquals("string", params.get(0).path("type").asString());
        assertTrue(params.get(0).path("required").asBoolean());
        // Callback URL must be present whenever tools are present —
        // otherwise the sidecar has nothing to POST to.
        var cb = body.path("tool_callback_url").asString("");
        assertTrue(cb.startsWith("http://127.0.0.1:"),
                "tool_callback_url must point at a loopback callback server; got: " + cb);
        // Tool itself shouldn't have been executed yet — the sidecar must
        // pull it via the callback URL.
        assertFalse(executed.get(), "executor must not run on the start path");
    }

    @Test
    void systemPrompt_threadedThroughStartRequest() throws Exception {
        sidecar.startResponse = doneOnly();
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);

        var session = new RecordingSession();
        runtime.execute(contextWithTools(List.of(), "You are a careful research assistant."),
                session);

        assertTrue(session.awaitTerminal(5, TimeUnit.SECONDS));
        var body = MAPPER.readTree(sidecar.lastStartBody.get());
        assertEquals("You are a careful research assistant.",
                body.path("system_prompt").asString());
        // No tools wired → tools/callback URL must be absent so older sidecars
        // can deserialise the body unchanged (forward compatibility).
        assertTrue(body.path("tools").isMissingNode()
                        || body.path("tools").isArray() && body.path("tools").size() == 0,
                "tools must be omitted when none are wired; got: " + body.path("tools"));
        assertTrue(body.path("tool_callback_url").isMissingNode(),
                "tool_callback_url must be omitted when no tools are wired");
    }

    // ---- Callback server stand-alone (no runtime) ----------------------

    @Test
    void toolCallback_invokesToolExecutionHelper() throws Exception {
        var executor = new RecordingExecutor();
        var tool = ToolDefinition.builder("lookup_order", "Look up an order")
                .parameter("order_id", "The order id", "string", true)
                .executor(executor)
                .build();
        var session = new RecordingSession();
        var server = new ToolCallbackServer(Map.of("lookup_order", tool), session, null);
        server.start();
        try {
            var response = post(server.callbackUrl(),
                    "{\"call_id\":\"c1\",\"name\":\"lookup_order\","
                            + "\"arguments\":{\"order_id\":\"A123\"}}");
            assertEquals(200, response.statusCode());
            var body = MAPPER.readTree(response.body());
            assertEquals("OK:A123", body.path("result").asString());
            assertTrue(body.path("error").isMissingNode(),
                    "successful invocation must NOT include an 'error' field");
            assertEquals(Map.of("order_id", "A123"), executor.lastArgs.get(),
                    "argument map must be unwrapped from JSON object");
        } finally {
            server.stop();
        }
    }

    @Test
    void toolError_returnsHttp200WithErrorField() throws Exception {
        var tool = ToolDefinition.builder("boom", "Always fails")
                .parameter("ignored", "ignored", "string", false)
                .executor(args -> {
                    throw new IllegalStateException("kaboom");
                })
                .build();
        var server = new ToolCallbackServer(Map.of("boom", tool),
                new RecordingSession(), null);
        server.start();
        try {
            var response = post(server.callbackUrl(),
                    "{\"call_id\":\"c2\",\"name\":\"boom\",\"arguments\":{}}");
            // 200, not 5xx — sidecar routes the error back to CrewAI as a
            // recoverable tool failure (per wire protocol contract).
            assertEquals(200, response.statusCode(),
                    "tool-execution errors must use HTTP 200 so the sidecar "
                            + "can route them to CrewAI as recoverable failures");
            var body = MAPPER.readTree(response.body());
            // ToolExecutionHelper catches the throw and formats a JSON error
            // payload into the result, so the result field will carry the
            // wrapped error rather than the raw exception. Either shape
            // (top-level error OR result containing kaboom) is acceptable —
            // both are non-transport failures.
            var hasError = !body.path("error").isMissingNode()
                    && body.path("error").asString("").contains("kaboom");
            var hasErrorInResult = !body.path("result").isMissingNode()
                    && body.path("result").asString("").contains("kaboom");
            assertTrue(hasError || hasErrorInResult,
                    "either 'error' or 'result' must carry the failure detail; got: "
                            + response.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void toolCallback_unknownTool_returnsHttp200WithErrorField() throws Exception {
        var server = new ToolCallbackServer(Map.of(), new RecordingSession(), null);
        server.start();
        try {
            var response = post(server.callbackUrl(),
                    "{\"call_id\":\"c3\",\"name\":\"never_registered\","
                            + "\"arguments\":{}}");
            assertEquals(200, response.statusCode(),
                    "unknown-tool errors must use HTTP 200 (sidecar routes back)");
            var body = MAPPER.readTree(response.body());
            assertTrue(body.path("error").asString("").contains("unknown tool"),
                    "error field must carry the unknown-tool reason");
        } finally {
            server.stop();
        }
    }

    @Test
    void toolCallback_malformedJson_returns400() throws Exception {
        var server = new ToolCallbackServer(Map.of(), new RecordingSession(), null);
        server.start();
        try {
            var response = post(server.callbackUrl(), "{ not json");
            assertEquals(400, response.statusCode(),
                    "malformed JSON is a wire-level failure; 400 surfaces it cleanly");
        } finally {
            server.stop();
        }
    }

    @Test
    void callbackServer_loopbackOnly() throws Exception {
        var server = new ToolCallbackServer(Map.of(), new RecordingSession(), null);
        server.start();
        try {
            assertEquals("127.0.0.1", server.host(),
                    "callback server MUST bind 127.0.0.1, never 0.0.0.0 — "
                            + "loopback isolation is the only trust boundary today");
            assertTrue(server.callbackUrl().startsWith("http://127.0.0.1:"),
                    "callback URL must advertise 127.0.0.1 host");
        } finally {
            server.stop();
        }
    }

    @Test
    void callbackServer_stop_isIdempotent() throws Exception {
        var server = new ToolCallbackServer(Map.of(), new RecordingSession(), null);
        server.start();
        server.stop();
        // Second stop() must not throw — Correctness Invariant #2 demands
        // close-once semantics on every resource.
        server.stop();
        // After stop, the URL is still queryable but the listener is gone;
        // the post should fail. We don't assert exact behavior because the
        // OS-level connection-refused varies across platforms — we just
        // assert no NPE/IllegalStateException leaks out of stop().
    }

    @Test
    void callbackServer_notStarted_callbackUrlIsNull() {
        var server = new ToolCallbackServer(Map.of(), new RecordingSession(), null);
        assertNull(server.callbackUrl(),
                "callbackUrl() must be null until start() succeeds — callers "
                        + "should never advertise a URL that isn't bound");
    }

    // ---- Test plumbing -------------------------------------------------

    private static HttpResponse<String> post(String url, String body)
            throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(java.time.Duration.ofSeconds(5))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String doneOnly() {
        return "event: done\ndata: {}\n\n";
    }

    private static AgentExecutionContext contextWithTools(List<ToolDefinition> tools,
                                                          String systemPrompt) {
        return new AgentExecutionContext(
                "Run a tool, please.", systemPrompt, "crewai-default",
                null, "session-1", "user-1", "conv-1",
                tools, null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /** Recording executor stores the args the dispatcher hands it. */
    private static final class RecordingExecutor
            implements org.atmosphere.ai.tool.ToolExecutor {
        final AtomicReference<Map<String, Object>> lastArgs = new AtomicReference<>();

        @Override
        public Object execute(Map<String, Object> args) {
            lastArgs.set(args);
            return "OK:" + args.getOrDefault("order_id", "?");
        }
    }

    private static final class FakeSidecarWithTools {
        private HttpServer server;
        volatile String startResponse = "";
        volatile int healthStatus = 200;
        final AtomicReference<String> lastStartBody = new AtomicReference<>("");
        final CountDownLatch streamStarted = new CountDownLatch(1);

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/health", new HealthHandler());
            server.createContext("/v1/sessions", new SessionHandler());
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private final class HealthHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try (exchange) {
                    exchange.sendResponseHeaders(healthStatus, -1);
                }
            }
        }

        private final class SessionHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try (exchange) {
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        lastStartBody.set(new String(
                                exchange.getRequestBody().readAllBytes(),
                                StandardCharsets.UTF_8));
                        exchange.getResponseHeaders().add("Content-Type",
                                "text/event-stream");
                        exchange.getResponseHeaders().add(
                                "X-Atmosphere-CrewAI-Session", "tool-session-1");
                        var bytes = startResponse.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        streamStarted.countDown();
                        try (var os = exchange.getResponseBody()) {
                            os.write(bytes);
                        }
                    } else {
                        exchange.sendResponseHeaders(204, -1);
                    }
                }
            }
        }
    }

    private static final class RecordingSession implements StreamingSession {
        final List<String> textChunks = new CopyOnWriteArrayList<>();
        final Map<String, Object> metadata = new ConcurrentHashMap<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean errored = new AtomicBoolean();

        @Override public String sessionId() { return "test-session"; }
        @Override public void send(String text) { textChunks.add(text); }
        @Override public void sendMetadata(String key, Object value) { metadata.put(key, value); }
        @Override public void progress(String message) { }
        @Override public void complete() {
            if (closed.compareAndSet(false, true)) {
                terminal.countDown();
            }
        }
        @Override public void complete(String summary) {
            if (summary != null && !summary.isEmpty()) {
                textChunks.add(summary);
            }
            complete();
        }
        @Override public void error(Throwable t) {
            errors.add(t);
            errored.set(true);
            if (closed.compareAndSet(false, true)) {
                terminal.countDown();
            }
        }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public boolean hasErrored() { return errored.get(); }

        boolean awaitTerminal(long timeout, TimeUnit unit) throws InterruptedException {
            return terminal.await(timeout, unit);
        }
    }
}
