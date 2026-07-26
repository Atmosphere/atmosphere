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
package org.atmosphere.ai.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiConfidenceElicitation;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-shape tests for the native-logprobs confidence path: the Built-in
 * client requests {@code logprobs: true} when a confidence elicitation is in
 * scope (subject to the {@link LogprobsMode} gate), parses
 * {@code choices[].logprobs.content}, and fires
 * {@link AiConfidence#fromLogprobs(java.util.List)} — the previously
 * unreachable {@link AiConfidence.Source#LOGPROBS_NATIVE} path — through
 * {@link StreamingSession#confidence(AiConfidence)}.
 *
 * <p>Runs against a local {@code com.sun.net.httpserver.HttpServer} on
 * loopback, which is on the shared known-tolerant-endpoint allow-list, so the
 * AUTO gate resolves to "emit" without forcing the tri-state knob.</p>
 */
class OpenAiCompatibleClientLogprobsTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

    /**
     * Two content chunks, each carrying a logprobs entry, then a final chunk
     * with finish_reason. exp(-0.1) ~= 0.9048, exp(-0.3) ~= 0.7408 — mean
     * ~= 0.8228.
     */
    private static final String SSE_WITH_LOGPROBS = """
            data: {"choices":[{"delta":{"content":"Yes"},"logprobs":{"content":[{"token":"Yes","logprob":-0.1}]}}]}

            data: {"choices":[{"delta":{"content":" indeed"},"logprobs":{"content":[{"token":" indeed","logprob":-0.3}]}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}

            data: [DONE]

            """;

    /** Same stream shape but with no logprobs blocks at all. */
    private static final String SSE_WITHOUT_LOGPROBS = """
            data: {"choices":[{"delta":{"content":"Yes"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}

            data: [DONE]

            """;

    /** Malformed logprob entries the parser must skip without aborting. */
    private static final String SSE_WITH_MALFORMED_LOGPROBS = """
            data: {"choices":[{"delta":{"content":"A"},"logprobs":{"content":[{"token":"A"},{"logprob":-0.2},{"token":"B","logprob":"NaN"},{"token":"C","logprob":0.5},{"token":"D","logprob":-0.2}]}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

            data: [DONE]

            """;

    /** Round 1: a tool call, with a logprob on the emitted token. */
    private static final String SSE_TOOL_ROUND = """
            data: {"choices":[{"delta":{"content":"call"},"logprobs":{"content":[{"token":"call","logprob":-0.2}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"echo","arguments":"{\\"value\\":\\"x\\"}"}}]}}]}

            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

            data: [DONE]

            """;

    /** Round 2: the final answer, with its own logprob. */
    private static final String SSE_FINAL_ROUND = """
            data: {"choices":[{"delta":{"content":"done"},"logprobs":{"content":[{"token":"done","logprob":-0.4}]}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}

            data: [DONE]

            """;

    private static HttpServer toolServer;
    private static int toolPort;
    private static final List<String> TOOL_ROUND_BODIES =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @BeforeAll
    static void startToolServer() throws IOException {
        // Separate server for the tool-loop case so its per-round body capture
        // stays independent of the single-round tests above.
        toolServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        toolServer.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            TOOL_ROUND_BODIES.add(body);
            // The first request has no tool result yet; the follow-up carries
            // the tool-role message the loop appended.
            respond(exchange, body.contains("\"role\":\"tool\"")
                    ? SSE_FINAL_ROUND : SSE_TOOL_ROUND);
        });
        toolServer.start();
        toolPort = toolServer.getAddress().getPort();
    }

    @AfterAll
    static void stopToolServer() {
        if (toolServer != null) {
            toolServer.stop(0);
        }
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            // Marker-driven stub: the user message selects the response shape
            // so each test can pin one provider behaviour independently of
            // whether the client requested the field.
            var request = LAST_BODY.get();
            String body;
            if (request.contains("malformed")) {
                body = SSE_WITH_MALFORMED_LOGPROBS;
            } else if (request.contains("silent-provider")) {
                // Provider ignored the logprobs request entirely.
                body = SSE_WITHOUT_LOGPROBS;
            } else if (request.contains("\"logprobs\":true")) {
                body = SSE_WITH_LOGPROBS;
            } else {
                body = SSE_WITHOUT_LOGPROBS;
            }
            respond(exchange, body);
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @AfterEach
    void clearMode() {
        System.clearProperty(org.atmosphere.ai.AiConfig.LOGPROBS_PROPERTY);
    }

    @Test
    void elicitationActiveRequestsLogprobsAndEmitsNativeConfidence() {
        var session = runtimeStream(AiConfidenceElicitation.defaults());

        assertTrue(LAST_BODY.get().contains("\"logprobs\":true"),
                "an active elicitation must request logprobs: " + LAST_BODY.get());

        var confidence = session.confidence.get();
        assertNotNull(confidence, "a confidence record must reach the session");
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, confidence.source(),
                "the native path must win over the model-reported fallback");
        assertTrue(confidence.aggregate().isPresent(), "an aggregate must be computed");
        // mean(exp(-0.1), exp(-0.3)) == 0.82283...
        assertEquals(0.8228, confidence.aggregate().getAsDouble(), 1e-3);
        assertEquals(List.of("Yes", " indeed"),
                confidence.tokens().stream().map(t -> t.token()).toList(),
                "per-token breakdown must survive to the record");
        assertEquals(-0.1, confidence.tokens().get(0).logprob(), 1e-9);
    }

    @Test
    void noElicitationLeavesWireShapeUnchangedAndEmitsNoConfidence() {
        var session = runtimeStream(null);

        assertFalse(LAST_BODY.get().contains("logprobs"),
                "without an elicitation the body must be byte-identical to before: "
                        + LAST_BODY.get());
        assertNull(session.confidence.get(),
                "no elicitation means the runtime emits no confidence of its own");
    }

    @Test
    void disabledModeSuppressesTheFieldEvenWithElicitationActive() {
        System.setProperty(org.atmosphere.ai.AiConfig.LOGPROBS_PROPERTY, "disabled");
        var session = runtimeStream(AiConfidenceElicitation.defaults());

        assertFalse(LAST_BODY.get().contains("logprobs"),
                "DISABLED must suppress the field regardless of elicitation: "
                        + LAST_BODY.get());
        // The provider therefore returns no logprobs and the runtime stays
        // silent — the pipeline's model-reported-field decorator still covers
        // confidence for this request.
        assertNull(session.confidence.get(),
                "a suppressed request must not fabricate a native confidence");
    }

    @Test
    void providerSilenceOnLogprobsLeavesTheFallbackPathIntact() {
        // The field WAS requested (ENABLED forces it past the host gate) but
        // the provider returned no logprobs blocks. The runtime must stay
        // silent rather than emitting an empty LOGPROBS_NATIVE record —
        // emitting one would set ConfidenceCapturingSession's explicit flag
        // and suppress the model-reported-field fallback, degrading the signal
        // instead of enriching it.
        System.setProperty(org.atmosphere.ai.AiConfig.LOGPROBS_PROPERTY, "enabled");
        var session = runtimeStreamWithMessage(
                AiConfidenceElicitation.defaults(), "silent-provider");

        assertTrue(LAST_BODY.get().contains("\"logprobs\":true"),
                "ENABLED must force the field: " + LAST_BODY.get());
        assertNull(session.confidence.get(),
                "an empty logprob capture must not emit an unknown LOGPROBS_NATIVE record");
        assertTrue(session.completed, "the stream must still complete normally");
    }

    @Test
    void malformedLogprobEntriesAreSkippedWithoutAbortingTheStream() {
        var session = runtimeStreamWithMessage(AiConfidenceElicitation.defaults(), "malformed");

        var confidence = session.confidence.get();
        assertNotNull(confidence, "a malformed entry must not abort the confidence emission");
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, confidence.source());
        // Kept: {"token":"C","logprob":0.5} clamped to 0.0, and
        // {"token":"D","logprob":-0.2}. Skipped: missing-logprob,
        // missing-token, and the non-numeric "NaN" string.
        assertEquals(List.of("C", "D"),
                confidence.tokens().stream().map(t -> t.token()).toList(),
                "only well-formed entries survive: " + confidence.tokens());
        assertEquals(0.0, confidence.tokens().get(0).logprob(), 1e-9,
                "a positive logprob must be clamped, not rejected");
        assertTrue(session.completed, "the stream must still complete normally");
    }

    /**
     * Regression: the {@code logprobs} opt-in must survive into every
     * tool-loop follow-up round. The final answer — and therefore the tokens
     * worth scoring — comes from the LAST round, so a follow-up request that
     * dropped the flag would leave the aggregate reflecting only the
     * tool-call round.
     */
    @Test
    void logprobsOptInSurvivesToolLoopRounds() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + toolPort + "/v1")
                .apiKey("sk-test")
                .build();
        var runtime = new BuiltInAgentRuntime();
        runtime.configure(new org.atmosphere.ai.AiConfig.LlmSettings(
                client, "gpt-5-mini", "remote", null, "sk-test",
                PromptCacheKeyMode.AUTO, org.atmosphere.ai.GenerationParams.defaults()));

        var echo = org.atmosphere.ai.tool.ToolDefinition
                .builder("echo", "Echo a value")
                .parameter("value", "the value", "string")
                .executor(args -> "echoed")
                .build();
        var context = new AgentExecutionContext(
                "call the tool", "You are helpful", "gpt-5-mini",
                null, "session-1", "user-1", "conv-tool",
                List.of(echo), null, null, List.of(),
                Map.of(AiConfidenceElicitation.METADATA_KEY, AiConfidenceElicitation.defaults()),
                List.of(), null, null);

        var session = new CapturingSession();
        runtime.execute(context, session);
        session.await();

        assertEquals(2, TOOL_ROUND_BODIES.size(),
                "the loop must run a tool round and a final round");
        assertTrue(TOOL_ROUND_BODIES.get(1).contains("\"logprobs\":true"),
                "the follow-up round must still request logprobs: "
                        + TOOL_ROUND_BODIES.get(1));

        var confidence = session.confidence.get();
        assertNotNull(confidence, "a confidence record must reach the session");
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, confidence.source());
        // Round 1 contributed "call" (-0.2); round 2 contributed "done" (-0.4).
        assertEquals(List.of("call", "done"),
                confidence.tokens().stream().map(t -> t.token()).toList(),
                "tokens from BOTH rounds must aggregate: " + confidence.tokens());
    }

    /**
     * Mode-scope pin (Correctness Invariant #7): the Responses API body
     * builder never emits {@code logprobs}, even for a request that opted in.
     * The difference is intentional and documented in modules/ai/README.md
     * (§ Native logprobs confidence → "Mode scope"); this asserts the code
     * matches the doc so the two cannot drift apart silently.
     */
    @Test
    void responsesApiPathNeverRequestsLogprobs() {
        // The Responses path is selected only for api.openai.com with a
        // conversationId, so assert on the body builder directly rather than
        // driving a live stream against the stub.
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("sk-test")
                .build();
        var request = ChatCompletionRequest.builder("gpt-5-mini")
                .user("Is the sky blue?")
                .conversationId("conv-1")
                .logprobs(true)
                .build();

        var body = invokeResponsesApiBody(client, request);
        assertFalse(body.contains("logprobs"),
                "the Responses API body must never carry logprobs: " + body);
        // Sanity: the same request DOES carry it on the chat-completions path,
        // so the assertion above is about the mode, not about the opt-in
        // silently failing to reach the builder.
        assertTrue(request.logprobs(), "the request itself must carry the opt-in");
    }

    /** Reflective call into the private Responses-API body builder. */
    private static String invokeResponsesApiBody(OpenAiCompatibleClient client,
                                                 ChatCompletionRequest request) {
        try {
            var m = OpenAiCompatibleClient.class.getDeclaredMethod(
                    "buildResponsesApiBody", ChatCompletionRequest.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(client, request, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "buildResponsesApiBody signature changed — update this mode-scope pin", e);
        }
    }

    @Test
    void logprobsModeParsesLikeThePromptCacheKnob() {
        assertEquals(LogprobsMode.AUTO, LogprobsMode.parse(null));
        assertEquals(LogprobsMode.AUTO, LogprobsMode.parse(""));
        assertEquals(LogprobsMode.AUTO, LogprobsMode.parse("nonsense"));
        assertEquals(LogprobsMode.ENABLED, LogprobsMode.parse("TRUE"));
        assertEquals(LogprobsMode.ENABLED, LogprobsMode.parse(" enabled "));
        assertEquals(LogprobsMode.DISABLED, LogprobsMode.parse("off"));
        assertTrue(LogprobsMode.ENABLED.resolve(false), "ENABLED overrides the host gate");
        assertFalse(LogprobsMode.DISABLED.resolve(true), "DISABLED overrides the host gate");
        assertTrue(LogprobsMode.AUTO.resolve(true), "AUTO defers to the host gate");
        assertFalse(LogprobsMode.AUTO.resolve(false), "AUTO defers to the host gate");
    }

    @Test
    void autoSuppressesOnAnUnknownHost() {
        // A non-loopback, non-OpenAI host is not on the shared allow-list, so
        // AUTO must default-deny — a strict compat proxy that rejects the
        // field would otherwise fail the whole request.
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://llm.internal.example/v1")
                .apiKey("k")
                .build();
        assertFalse(client.supportsLogprobs(),
                "AUTO must default-deny on an unknown host");
    }

    // ---------------------------------------------------------------- helpers

    private CapturingSession runtimeStream(AiConfidenceElicitation elicitation) {
        return runtimeStreamWithMessage(elicitation, "Is the sky blue?");
    }

    private CapturingSession runtimeStreamWithMessage(AiConfidenceElicitation elicitation,
                                                      String message) {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/v1")
                .apiKey("sk-test")
                .build();
        var runtime = new BuiltInAgentRuntime();
        runtime.configure(new org.atmosphere.ai.AiConfig.LlmSettings(
                client, "gpt-5-mini", "remote", null, "sk-test",
                PromptCacheKeyMode.AUTO, org.atmosphere.ai.GenerationParams.defaults()));

        Map<String, Object> metadata = elicitation == null
                ? Map.of() : Map.of(AiConfidenceElicitation.METADATA_KEY, elicitation);
        var context = new AgentExecutionContext(
                message, "You are helpful", "gpt-5-mini",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null);

        var session = new CapturingSession();
        runtime.execute(context, session);
        session.await();
        return session;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Session capturing the confidence record the runtime emits. */
    private static final class CapturingSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private final AtomicReference<AiConfidence> confidence = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean closed;
        private volatile boolean completed;

        @Override public String sessionId() { return "test-session"; }

        @Override public void send(String t) {
            if (t != null) {
                text.append(t);
            }
        }

        @Override public void sendMetadata(String key, Object value) { }

        @Override public void progress(String message) { }

        @Override public void usage(TokenUsage usage) { }

        @Override public void confidence(AiConfidence c) { confidence.set(c); }

        @Override public void emit(AiEvent event) { }

        @Override public Map<Class<?>, Object> injectables() { return new LinkedHashMap<>(); }

        @Override public void complete() {
            completed = true;
            closed = true;
            done.countDown();
        }

        @Override public void complete(String summary) { complete(); }

        @Override public void error(Throwable t) { closed = true; done.countDown(); }

        @Override public boolean isClosed() { return closed; }

        void await() {
            try {
                assertTrue(done.await(10, TimeUnit.SECONDS), "stream must settle");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                assertFalse(true, "interrupted while awaiting stream");
            }
        }
    }
}
