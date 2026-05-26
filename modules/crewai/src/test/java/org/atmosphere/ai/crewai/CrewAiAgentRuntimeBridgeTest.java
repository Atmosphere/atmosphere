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
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bridge test that stands up a real {@link HttpServer} speaking the CrewAI
 * sidecar protocol with canned responses. Drives the runtime end-to-end:
 * discovery + health probe + SSE drain + cancel.
 *
 * <p>Why a real local server and not Mockito on {@link java.net.http.HttpClient}:
 * the runtime's behavior under wire-level events (multi-line SSE frames,
 * connection drop, idle reader) is exactly the behavior we want to assert;
 * mocking the streaming InputStream hides the parser bugs we care about
 * catching.</p>
 */
class CrewAiAgentRuntimeBridgeTest {

    private FakeSidecar sidecar;
    private String savedEnvUrl;

    @BeforeEach
    void setUp() throws Exception {
        sidecar = new FakeSidecar();
        sidecar.start();
        // System properties are scoped to this JVM and have higher priority
        // than the environment lookup we test for, so the test never depends
        // on whether ATMOSPHERE_CREWAI_SIDECAR_URL is set in the host shell.
        System.setProperty(CrewAiSidecarConfig.SYS_URL, sidecar.url());
        // Lower health timeout so the "down" test fails fast.
        System.setProperty(CrewAiSidecarConfig.SYS_HEALTH_TIMEOUT_MS, "500");
        savedEnvUrl = System.getProperty(CrewAiSidecarConfig.SYS_URL);
    }

    @AfterEach
    void tearDown() {
        sidecar.stop();
        System.clearProperty(CrewAiSidecarConfig.SYS_URL);
        System.clearProperty(CrewAiSidecarConfig.SYS_HEALTH_TIMEOUT_MS);
        System.clearProperty(CrewAiSidecarConfig.SYS_REQUEST_TIMEOUT_MS);
        // Defensive — should already be gone; keep the env reset symmetrical.
        if (savedEnvUrl == null) {
            System.clearProperty(CrewAiSidecarConfig.SYS_URL);
        }
    }

    @Test
    void health_isAvailable_whenSidecarRespondsOk() {
        sidecar.healthStatus = 200;
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);
        assertTrue(runtime.isAvailable(),
                "isAvailable() must reflect a live /health probe, not classpath presence");
    }

    @Test
    void health_isUnavailable_whenSidecarDown() {
        sidecar.healthStatus = 503;
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);
        assertFalse(runtime.isAvailable(),
                "Sidecar returning 503 must surface as runtime unavailable "
                        + "(Correctness Invariant #5 — Runtime Truth)");
    }

    @Test
    void streamingTokens_dispatchedToSession() throws Exception {
        sidecar.healthStatus = 200;
        sidecar.startResponse = """
                event: token
                data: {"text":"Hello"}

                event: token
                data: {"text":" "}

                event: token
                data: {"text":"world"}

                event: usage
                data: {"input":7,"output":2,"total":9,"model":"crewai-default"}

                event: done
                data: {}

                """;
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);
        var session = new RecordingSession();
        runtime.execute(textContext(), session);

        assertTrue(session.awaitTerminal(5, TimeUnit.SECONDS),
                "session must reach a terminal state");
        assertEquals(List.of("Hello", " ", "world"), session.textChunks,
                "every token event must reach session.send()");
        assertEquals(1, session.completionCount.get(),
                "session.complete() must fire exactly once on a clean stream");
        assertTrue(session.errors.isEmpty(),
                "no errors expected: " + session.errors);
        assertEquals(7L, session.metadata.get("ai.tokens.input"),
                "usage event must reach session.usage() and re-emit legacy keys");
        assertEquals(2L, session.metadata.get("ai.tokens.output"));
        assertEquals(9L, session.metadata.get("ai.tokens.total"));
    }

    @Test
    void errorEvent_propagatesToSession() throws Exception {
        sidecar.healthStatus = 200;
        sidecar.startResponse = """
                event: token
                data: {"text":"partial"}

                event: error
                data: {"message":"boom"}

                """;
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);
        var session = new RecordingSession();
        runtime.execute(textContext(), session);

        assertTrue(session.awaitTerminal(5, TimeUnit.SECONDS),
                "error frame must reach a terminal state");
        assertEquals(1, session.errors.size(), "exactly one error expected");
        assertTrue(session.errors.get(0).getMessage().contains("boom"),
                "error message must carry the sidecar-reported reason; got: "
                        + session.errors.get(0).getMessage());
        assertEquals(0, session.completionCount.get(),
                "complete() must NOT fire when the stream ended with an error");
    }

    @Test
    void cancel_callsSidecarDelete() throws Exception {
        sidecar.healthStatus = 200;
        // Long stream — the test cancels before it completes naturally.
        sidecar.holdStreamOpen = true;
        sidecar.startResponse = """
                event: token
                data: {"text":"streaming"}

                """;
        sidecar.assignedSessionId = "run-42";
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);
        var session = new RecordingSession();
        var handle = runtime.executeWithHandle(textContext(), session);

        // Wait until the sidecar has accepted the POST and assigned the id
        // so the cancel path actually has a sessionId to DELETE.
        assertTrue(sidecar.streamStarted.await(5, TimeUnit.SECONDS),
                "sidecar must accept the POST before cancel runs");
        // Small wait so the SSE iterator has read at least one frame and the
        // runtime has stashed the session id.
        assertTrue(waitFor(() -> session.textChunks.contains("streaming"),
                5, TimeUnit.SECONDS),
                "runtime must drain the first token before cancel");

        handle.cancel();
        // Second cancel must be a safe no-op (idempotency assertion).
        handle.cancel();

        assertTrue(sidecar.deleteLatch.await(5, TimeUnit.SECONDS),
                "cancel() must issue DELETE /v1/sessions/{id} to the sidecar");
        assertEquals(1, sidecar.deleteCount.get(),
                "DELETE must fire exactly once across two cancel() calls");
        assertEquals("run-42", sidecar.lastDeletedId.get(),
                "DELETE path must carry the sidecar-assigned session id");

        // Unblock the held stream so the runtime can wind down and the
        // session reaches its terminal state.
        sidecar.releaseStream();
        assertTrue(session.awaitTerminal(5, TimeUnit.SECONDS),
                "session must reach a terminal state after cancel "
                        + "(Correctness Invariant #2)");
        // Future from handle must resolve so callers can chain cleanup.
        handle.whenDone().get(5, TimeUnit.SECONDS);
        assertTrue(handle.isDone(),
                "handle.isDone() must return true after the runtime winds down");
    }

    @Test
    void terminalPath_alwaysClosesSession() throws Exception {
        sidecar.healthStatus = 200;
        // Drop the connection partway through — no done, no error frame.
        sidecar.startResponse = """
                event: token
                data: {"text":"partial"}

                """;
        sidecar.dropAfterBody = true;
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);
        var session = new RecordingSession();
        runtime.execute(textContext(), session);

        assertTrue(session.awaitTerminal(5, TimeUnit.SECONDS),
                "session must reach a terminal state even when the sidecar "
                        + "drops the connection mid-stream (Correctness Invariant #2)");
        // The runtime translates the missing-terminal-frame case to an
        // error so the caller can distinguish it from a clean completion.
        assertEquals(1, session.errors.size(),
                "missing terminal frame must surface as session.error()");
        assertEquals(0, session.completionCount.get());
    }

    @Test
    void runtime_declaresExpectedCapabilities() {
        var runtime = new CrewAiAgentRuntime();
        var caps = runtime.capabilities();
        assertEquals(Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOKEN_USAGE,
                AiCapability.AGENT_ORCHESTRATION,
                AiCapability.CANCELLATION,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.PER_REQUEST_RETRY
        ), caps,
                "CrewAI runtime ships the tool-RPC bridge + ToolExecutionHelper.executeWithApproval "
                        + "routing (TOOL_CALLING + TOOL_APPROVAL) and the system-prompt threading "
                        + "the pipeline's StructuredOutputCapturingSession composes with "
                        + "(SYSTEM_PROMPT + STRUCTURED_OUTPUT) on top of the original "
                        + "streaming/usage/cancel surface; CONVERSATION_MEMORY remains out "
                        + "until a sidecar-side checkpoint contract lands (Invariant #5).");
    }

    @Test
    void runtime_nameAndPriority() {
        var runtime = new CrewAiAgentRuntime();
        assertEquals("crewai", runtime.name());
        assertEquals(50, runtime.priority(),
                "CrewAI runtime priority must remain stable so resolver order "
                        + "is predictable when multiple runtimes are present");
    }

    @Test
    void httpClient_pinnedToHttp11() {
        // Regression test: java.net.http defaults to HTTP_2 which tries to
        // negotiate an `Upgrade: h2c` against plain-HTTP sidecars. uvicorn
        // (the FastAPI host) does not implement h2c upgrade — the request
        // lands with an empty body and FastAPI returns 422 "body missing".
        // Surfaced via chrome-devtools roundtrip against a real Python
        // sidecar. Pin HTTP_1_1 here so a future "let's use the default
        // HttpClient" refactor breaks the test before it breaks production.
        var config = new CrewAiSidecarConfig(
                java.net.URI.create("http://127.0.0.1:1"),
                java.time.Duration.ofMillis(100),
                java.time.Duration.ofMillis(100));
        var client = new HttpSseSidecarClient(config);
        // Reach in via reflection — the HttpClient is intentionally private
        // because callers should NOT be tuning the version themselves.
        try {
            var field = HttpSseSidecarClient.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            var httpClient = (java.net.http.HttpClient) field.get(client);
            assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, httpClient.version(),
                    "HttpSseSidecarClient.httpClient MUST be pinned to HTTP_1_1 — "
                            + "default HTTP_2 negotiates badly with uvicorn / FastAPI sidecars");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "If httpClient field was renamed, update this test too", e);
        }
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hello sidecar", null, "crewai-default",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    private static boolean waitFor(java.util.function.BooleanSupplier predicate,
                                   long timeout, TimeUnit unit) throws InterruptedException {
        var deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (predicate.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return predicate.getAsBoolean();
    }

    /**
     * Embedded sidecar that speaks the SSE wire shape the runtime expects.
     * State is intentionally mutable so each test tweaks just the field it
     * cares about — avoids a builder explosion for a 6-method test class.
     */
    private static final class FakeSidecar {

        private HttpServer server;
        volatile int healthStatus = 200;
        volatile String startResponse = "";
        volatile boolean holdStreamOpen;
        volatile boolean dropAfterBody;
        volatile String assignedSessionId = "session-1";
        final CountDownLatch streamStarted = new CountDownLatch(1);
        final CountDownLatch deleteLatch = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger deleteCount =
                new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<String> lastDeletedId = new AtomicReference<>();
        private final AtomicBoolean release = new AtomicBoolean();

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/health", new HealthHandler());
            server.createContext("/v1/sessions", new SessionHandler());
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        }

        void stop() {
            release.set(true);
            if (server != null) {
                server.stop(0);
            }
        }

        void releaseStream() {
            release.set(true);
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
                    var method = exchange.getRequestMethod();
                    var path = exchange.getRequestURI().getPath();
                    if ("POST".equalsIgnoreCase(method) && "/v1/sessions".equals(path)) {
                        handleStart(exchange);
                    } else if ("DELETE".equalsIgnoreCase(method)
                            && path.startsWith("/v1/sessions/")) {
                        handleCancel(exchange, path);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                }
            }

            private void handleStart(HttpExchange exchange) throws IOException {
                // Drain the request body so the client connection releases
                // — Correctness Invariant #1 mirrored on the test fixture.
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().add("X-Atmosphere-CrewAI-Session",
                        assignedSessionId);
                var bytes = startResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, 0);
                var os = exchange.getResponseBody();
                streamStarted.countDown();
                os.write(bytes);
                os.flush();
                if (holdStreamOpen) {
                    // Hold the response open until the test signals release.
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!release.get() && System.nanoTime() < deadline) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (dropAfterBody) {
                    // Close abruptly without a done/error frame — the runtime
                    // must surface this as session.error per Invariant #2.
                    os.close();
                }
            }

            private void handleCancel(HttpExchange exchange, String path) throws IOException {
                var id = path.substring("/v1/sessions/".length());
                lastDeletedId.set(java.net.URLDecoder.decode(id, StandardCharsets.UTF_8));
                deleteCount.incrementAndGet();
                deleteLatch.countDown();
                exchange.sendResponseHeaders(204, -1);
            }
        }
    }

    /** Recording {@link StreamingSession} — the test double. */
    private static final class RecordingSession implements StreamingSession {
        final List<String> textChunks = new CopyOnWriteArrayList<>();
        final Map<String, Object> metadata = new ConcurrentHashMap<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger completionCount =
                new java.util.concurrent.atomic.AtomicInteger();
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean errored = new AtomicBoolean();

        @Override public String sessionId() { return "test-session"; }

        @Override public void send(String text) { textChunks.add(text); }

        @Override public void sendMetadata(String key, Object value) {
            metadata.put(key, value);
        }

        @Override public void progress(String message) { }

        @Override public void complete() {
            completionCount.incrementAndGet();
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
