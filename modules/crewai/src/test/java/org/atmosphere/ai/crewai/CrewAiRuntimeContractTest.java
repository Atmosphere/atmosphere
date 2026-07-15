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
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TCK-style contract test for {@link CrewAiAgentRuntime}. Stands up a real
 * loopback {@link HttpServer} speaking the CrewAI sidecar wire shape (health
 * probe, SSE token/usage/done/error frames, and the tool-callback round trip)
 * so every cross-runtime contract assertion drives the runtime end-to-end
 * without a live Python sidecar process.
 *
 * <h3>Architectural deviation — tool-call routing</h3>
 *
 * <p>CrewAI's sidecar architecture differs from in-JVM runtimes (LangChain4j,
 * Spring AI, ADK) in <em>how</em> tool invocations reach Java: CrewAI's
 * Python agents own the tool loop and call back to the JVM over HTTP at the
 * {@code tool_callback_url} advertised on the {@code POST /v1/sessions} body
 * — there is no in-SSE {@code tool_call} delta event. Tool invocations
 * therefore appear on the {@link ToolCallbackServer}'s loopback listener,
 * not on the SSE stream itself. The fake sidecar below honors that contract:
 * when the start request advertises tools, it POSTs to the callback URL
 * during the stream, then continues with token + done frames.</p>
 *
 * <p>The cross-runtime {@code hitlPendingApprovalEmitsProtocolEvent}
 * assertion is satisfied via {@link #createApprovalTriggerContext()} which
 * wires a {@code @RequiresApproval} tool into the context. The fake sidecar
 * POSTs to the callback URL during the stream so the
 * {@link ToolCallbackServer} routes through
 * {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval} and
 * the capturing {@link org.atmosphere.ai.approval.ApprovalStrategy} fires —
 * proving the bridge honors the unified HITL seam (Correctness Invariant
 * #7 — Mode Parity).</p>
 */
class CrewAiRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FakeSidecar sidecar;
    private String savedUrlProperty;
    private String savedHealthTimeoutProperty;
    private String savedRequestTimeoutProperty;

    @BeforeEach
    void setUp() throws Exception {
        savedUrlProperty = System.getProperty(CrewAiSidecarConfig.SYS_URL);
        savedHealthTimeoutProperty = System.getProperty(
                CrewAiSidecarConfig.SYS_HEALTH_TIMEOUT_MS);
        savedRequestTimeoutProperty = System.getProperty(
                CrewAiSidecarConfig.SYS_REQUEST_TIMEOUT_MS);
        sidecar = new FakeSidecar();
        sidecar.start();
        // System properties shadow the environment lookup so the test never
        // depends on whether ATMOSPHERE_CREWAI_SIDECAR_URL is exported in the
        // host shell. Low timeouts keep failure modes fast.
        System.setProperty(CrewAiSidecarConfig.SYS_URL, sidecar.url());
        System.setProperty(CrewAiSidecarConfig.SYS_HEALTH_TIMEOUT_MS, "500");
        System.setProperty(CrewAiSidecarConfig.SYS_REQUEST_TIMEOUT_MS, "5000");
    }

    @AfterEach
    void tearDown() {
        sidecar.stop();
        restoreProperty(CrewAiSidecarConfig.SYS_URL, savedUrlProperty);
        restoreProperty(CrewAiSidecarConfig.SYS_HEALTH_TIMEOUT_MS,
                savedHealthTimeoutProperty);
        restoreProperty(CrewAiSidecarConfig.SYS_REQUEST_TIMEOUT_MS,
                savedRequestTimeoutProperty);
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    @Override
    protected AgentRuntime createRuntime() {
        var runtime = new CrewAiAgentRuntime();
        runtime.configure(null);
        return runtime;
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello sidecar", "You are helpful", "crewai-default",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        // A non-approval tool: the fake sidecar POSTs to the callback URL
        // during the stream so the runtime drives ToolExecutionHelper without
        // going through the approval gate. The contract assertion is "session
        // reaches a terminal state after the tool call" — see
        // toolCallExecutesIfSupported in the base.
        var tool = ToolDefinition.builder("echo", "echoes the input back")
                .parameter("text", "the input text", "string", true)
                .executor(args -> "echo:" + args.getOrDefault("text", ""))
                .build();
        return new AgentExecutionContext(
                "please call echo", "You are helpful", "crewai-default",
                null, "session-1", "user-1", "conv-1",
                List.of(tool), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        return new AgentExecutionContext(
                CONTRACT_ERROR_SENTINEL, "You are helpful", "crewai-default",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /**
     * Approval-trigger context — wires a tool that declares
     * {@code @RequiresApproval} so the fake sidecar's POST to the callback
     * URL drives {@link ToolCallbackServer} through
     * {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval},
     * which then consults the capturing approval strategy installed by the
     * base test. Proves the CrewAI bridge honors the unified HITL seam end
     * to end (not just at the helper-level fallback).
     */
    @Override
    protected AgentExecutionContext createApprovalTriggerContext() {
        var sensitive = ToolDefinition.builder("contract_delete",
                        "test-only deletion gated by approval")
                .parameter("id", "row id", "string", true)
                .executor(args -> "deleted:" + args.getOrDefault("id", ""))
                .requiresApproval("Approve contract deletion?", 60)
                .build();
        return new AgentExecutionContext(
                "please delete row r-1", "You are helpful", "crewai-default",
                null, "session-1", "user-1", "conv-1",
                List.of(sensitive), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected Set<AiCapability> expectedCapabilities() {
        // Pinned to the runtime's live capabilities() so future drift breaks
        // the build at runtimeDeclaresExactlyExpectedCapabilities. The pair
        // (SYSTEM_PROMPT + STRUCTURED_OUTPUT) and (TOOL_CALLING +
        // TOOL_APPROVAL) are mandatory under the cross-runtime invariants
        // expressed in the abstract base: the pipeline's
        // StructuredOutputCapturingSession auto-wraps every SYSTEM_PROMPT-
        // capable runtime, and ToolCallbackServer routes every tool call
        // through ToolExecutionHelper.executeWithApproval.
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOKEN_USAGE,
                AiCapability.AGENT_ORCHESTRATION,
                AiCapability.CANCELLATION,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.PER_REQUEST_RETRY);
    }

    /**
     * Fake CrewAI sidecar. Smart enough to dispatch on the request body:
     * <ul>
     *   <li>If the body's {@code message} contains
     *       {@link AbstractAgentRuntimeContractTest#CONTRACT_ERROR_SENTINEL},
     *       emit {@code event: error} immediately.</li>
     *   <li>If the body advertises tools (i.e. {@code tools[]} non-empty AND
     *       {@code tool_callback_url} present), stream a token, POST to the
     *       callback URL with a synthetic tool-call request matching the
     *       first advertised tool, drain the response, then send a final
     *       token + usage + done.</li>
     *   <li>Otherwise stream three tokens, a usage frame, and done.</li>
     * </ul>
     */
    private static final class FakeSidecar {

        private static final String SESSION_ID = "contract-session";

        private HttpServer server;

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

        private static final class HealthHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try (exchange) {
                    // 200 means sidecar is ready; the runtime's isAvailable()
                    // probe demands a 200 response (Correctness Invariant #5).
                    exchange.sendResponseHeaders(200, -1);
                }
            }
        }

        private static final class SessionHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try (exchange) {
                    var method = exchange.getRequestMethod();
                    var path = exchange.getRequestURI().getPath();
                    if ("POST".equalsIgnoreCase(method) && "/v1/sessions".equals(path)) {
                        handleStart(exchange);
                    } else if ("DELETE".equalsIgnoreCase(method)
                            && path.startsWith("/v1/sessions/")) {
                        // Cancellation acknowledged — the contract suite's
                        // cancellation surface lives on the bridge test, not
                        // here. Keep a 204 so an aggressive caller doesn't
                        // see a transport failure.
                        exchange.sendResponseHeaders(204, -1);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                }
            }

            private void handleStart(HttpExchange exchange) throws IOException {
                var bodyBytes = exchange.getRequestBody().readAllBytes();
                JsonNode body = parseOrEmpty(bodyBytes);
                var message = body.path("message").asString("");
                var toolCallbackUrl = body.path("tool_callback_url").asString("");
                // A real sidecar reads the per-run secret off the same body and
                // echoes it on every callback; the fixture must too, or the
                // callback server refuses it with 401.
                var toolCallbackToken = body.path("tool_callback_token").asString("");
                var tools = body.path("tools");

                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().add(
                        "X-Atmosphere-CrewAI-Session", SESSION_ID);
                exchange.sendResponseHeaders(200, 0);
                var os = exchange.getResponseBody();

                if (message.contains(CONTRACT_ERROR_SENTINEL)) {
                    writeFrame(os, "session", "{\"id\":\"" + SESSION_ID + "\"}");
                    writeFrame(os, "error",
                            "{\"message\":\"intentional test failure\"}");
                    os.close();
                    return;
                }

                writeFrame(os, "session", "{\"id\":\"" + SESSION_ID + "\"}");
                writeFrame(os, "token", "{\"text\":\"Hello\"}");

                if (tools.isArray() && tools.size() > 0
                        && !toolCallbackUrl.isBlank()) {
                    // The first advertised tool drives the callback round
                    // trip — its name is whatever the test wired in (echo,
                    // contract_delete, etc.). The callback handler in
                    // ToolCallbackServer routes through
                    // ToolExecutionHelper.executeWithApproval so the unified
                    // HITL seam fires identically to the in-process tool
                    // path (Correctness Invariant #7 — Mode Parity).
                    var toolName = tools.get(0).path("name").asString("");
                    if (!toolName.isBlank()) {
                        invokeToolCallback(toolCallbackUrl, toolCallbackToken, toolName);
                    }
                }

                writeFrame(os, "token", "{\"text\":\" \"}");
                writeFrame(os, "token", "{\"text\":\"world\"}");
                writeFrame(os, "usage",
                        "{\"input\":7,\"output\":2,\"total\":9,\"model\":\"crewai-default\"}");
                writeFrame(os, "done", "{}");
                os.close();
            }

            private static void invokeToolCallback(String callbackUrl,
                                                   String callbackToken,
                                                   String toolName) {
                // Best-effort — the runtime's terminal-path guarantees absorb
                // a failed callback (the SSE stream still finishes with a
                // done frame). The contract assertion lives downstream on
                // the approval strategy that ToolExecutionHelper consults.
                try {
                    var client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .build();
                    var body = "{\"call_id\":\"c1\",\"name\":\"" + toolName
                            + "\",\"arguments\":{\"text\":\"hi\",\"id\":\"r-1\"}}";
                    var request = HttpRequest.newBuilder(URI.create(callbackUrl))
                            .timeout(Duration.ofSeconds(2))
                            .header("content-type", "application/json")
                            .header(ToolCallbackServer.TOKEN_HEADER, callbackToken)
                            .POST(HttpRequest.BodyPublishers.ofString(body,
                                    StandardCharsets.UTF_8))
                            .build();
                    client.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (RuntimeException | IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    // Intentionally swallow — see method javadoc. Logging via
                    // System.err keeps the failure visible in CI without
                    // pulling SLF4J into the fake's classpath.
                    System.err.println("FakeSidecar callback POST failed: "
                            + e.getMessage());
                }
            }
        }

        private static void writeFrame(java.io.OutputStream os, String event,
                                       String data) throws IOException {
            var frame = "event: " + event + "\ndata: " + data + "\n\n";
            os.write(frame.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        private static JsonNode parseOrEmpty(byte[] bodyBytes) {
            try {
                return MAPPER.readTree(bodyBytes);
            } catch (RuntimeException e) {
                return MAPPER.createObjectNode();
            }
        }
    }
}
