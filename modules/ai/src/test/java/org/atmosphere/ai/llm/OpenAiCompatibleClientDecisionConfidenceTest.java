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
import org.atmosphere.ai.ConfidenceRoute;
import org.atmosphere.ai.ConfidenceRouting;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
 * Decision confidence ({@link AiConfidence.Source#DECISION_LOGPROBS}) on the
 * Built-in chat-completions path, driven end to end through
 * {@link BuiltInAgentRuntime} against hand-authored SSE fixtures in the
 * chat-completions streaming shape
 * ({@code src/test/resources/fixtures/logprobs/*.sse}, one token per chunk
 * with {@code top_logprobs}). The logprob values are chosen by hand to pin
 * the arithmetic; none of them is a captured provider response, so the
 * scoring has not yet been checked against a live provider's
 * {@code top_logprobs} under strict {@code json_schema}.
 *
 * <p>The point of the source: a long, fluent structured answer whose one
 * decisive token was a coin flip must not score as confident. The split
 * fixture pins exactly that — its whole-response {@code LOGPROBS_NATIVE}
 * mean is above the default act threshold while its decision distribution
 * escalates.</p>
 */
class OpenAiCompatibleClientDecisionConfidenceTest {

    private static final double EPS = 1e-9;

    /** Probability of the fixtures' {@code "\":"} / {@code "\":\""} token after the key. */
    private static final double SEPARATOR = Math.exp(-0.0003);

    enum Verdict { APPROVE, REJECT, DEFER }

    record Triage(Verdict verdict, String reason) { }

    enum Action { CONFIRM, CANCEL, ESCALATE }

    record Ticket(Action action, String note) { }

    record Approval(boolean approved, String reason) { }

    private static HttpServer server;
    private static int port;
    private static final List<String> BODIES = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            BODIES.add(request);
            // Marker-driven stub: the user message selects the recorded payload.
            String fixture;
            if (request.contains("case-tool")) {
                fixture = request.contains("\"role\":\"tool\"") ? "decision-split" : "decision-tool-round";
            } else if (request.contains("case-confident")) {
                fixture = "decision-confident";
            } else if (request.contains("case-multitoken")) {
                fixture = "decision-multitoken";
            } else if (request.contains("case-boolean")) {
                fixture = "decision-boolean";
            } else if (request.contains("case-quote-split")) {
                fixture = "decision-split-at-quote";
            } else if (request.contains("case-spacing")) {
                fixture = "decision-spacing-variant";
            } else if (request.contains("case-no-top")) {
                fixture = "decision-no-top-logprobs";
            } else {
                fixture = "decision-split";
            }
            respond(exchange, fixture(fixture));
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

    @Test
    void confidentEnumScoresTheDecisionDistribution() {
        var session = run("case-confident", Triage.class, decisionOn("verdict"));

        assertTrue(lastBody().contains("\"logprobs\":true"), lastBody());
        // Three allowed values + three formatting slots.
        assertTrue(lastBody().contains("\"top_logprobs\":6"),
                "a designated enum decision must request top_logprobs: " + lastBody());

        var confidence = session.confidence.get();
        assertNotNull(confidence, "a decision confidence must reach the session");
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, confidence.source());
        var decision = confidence.decision().orElseThrow();
        assertEquals("verdict", decision.field());
        assertEquals(List.of("APPROVE", "REJECT", "DEFER"), List.copyOf(decision.probabilities().keySet()),
                "the distribution covers exactly the schema's values, in schema order");

        // APPROVE collects its own token and the "AP" prefix alternative; the
        // lowercase "approve" alternative matches no allowed value and is
        // unobserved. The '":' rival at the separator is ambiguous. Both land
        // on the least likely value, DEFER.
        var approve = SEPARATOR * (Math.exp(-0.01) + Math.exp(-7.5));
        var reject = SEPARATOR * Math.exp(-5.0);
        assertEquals(approve, decision.probabilities().get("APPROVE"), EPS);
        assertEquals(reject, decision.probabilities().get("REJECT"), EPS);
        assertEquals(1 - approve - reject, decision.probabilities().get("DEFER"), EPS);
        assertEquals(approve + reject + SEPARATOR * Math.exp(-6.0) + Math.exp(-8.4),
                decision.observedMass(), EPS);
        assertEquals("APPROVE", decision.mostLikely());
        assertEquals((3 * approve - 1) / 2, confidence.aggregate().getAsDouble(), EPS);
        assertEquals(List.of("APPROVE"), confidence.tokens().stream().map(t -> t.token()).toList(),
                "tokens carry the decision value, not the whole response");
        assertEquals(ConfidenceRoute.ACT, ConfidenceRouting.defaults().route(confidence));
    }

    @Test
    void splitEnumEscalatesWhereTheFluencyMeanWouldAct() {
        var decisionSession = run("case-split", Triage.class, decisionOn("verdict"));
        var confidence = decisionSession.confidence.get();
        assertNotNull(confidence);
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, confidence.source());

        var approve = SEPARATOR * Math.exp(-0.69);
        var reject = SEPARATOR * Math.exp(-0.70);
        var decision = confidence.decision().orElseThrow();
        assertEquals(approve, decision.probabilities().get("APPROVE"), EPS);
        assertEquals(reject, decision.probabilities().get("REJECT"), EPS);
        assertEquals(1 - approve - reject, decision.probabilities().get("DEFER"), EPS,
                "a value the provider never offered holds only the unobserved mass");
        assertEquals(approve + reject, decision.observedMass(), EPS);
        assertEquals((3 * approve - 1) / 2, confidence.aggregate().getAsDouble(), EPS);
        assertTrue(confidence.aggregate().getAsDouble() < 0.3,
                "a coin flip between two of three values is weakly concentrated: "
                        + confidence.aggregate());
        assertEquals(ConfidenceRoute.ESCALATE, ConfidenceRouting.defaults().route(confidence));

        // The same recorded response without a decision field keeps the
        // historical whole-response mean — which is fluent enough to ACT.
        var meanSession = run("case-split", Triage.class, AiConfidenceElicitation.defaults());
        assertFalse(lastBody().contains("top_logprobs"),
                "no decision field means no top_logprobs on the wire: " + lastBody());
        var mean = meanSession.confidence.get();
        assertNotNull(mean);
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, mean.source(),
                "existing callers keep LOGPROBS_NATIVE semantics");
        assertTrue(mean.decision().isEmpty());
        assertTrue(mean.aggregate().getAsDouble() >= ConfidenceRouting.DEFAULT_ACT_AT,
                "the fluency mean hides the coin flip: " + mean.aggregate());
        assertEquals(ConfidenceRoute.ACT, ConfidenceRouting.defaults().route(mean));
    }

    @Test
    void multiTokenEnumValuesWalkTheSampledPath() {
        var session = run("case-multitoken", Ticket.class, decisionOn("action"));
        var confidence = session.confidence.get();
        assertNotNull(confidence);
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, confidence.source());
        var decision = confidence.decision().orElseThrow();

        // "C" (sampled) is a prefix of CONFIRM and CANCEL; "ESC" only of
        // ESCALATE. At the next position "ON" (sampled) resolves CONFIRM and
        // "AN" resolves CANCEL, each weighted by the probability of "C". The
        // unlisted mass at "ON" can only be CONFIRM or CANCEL and goes to the
        // lighter one, CANCEL; the unlisted mass before it goes to ESCALATE.
        var escalate = SEPARATOR * Math.exp(-2.4);
        var confirm = SEPARATOR * Math.exp(-0.1) * Math.exp(-0.5);
        var cancel = SEPARATOR * Math.exp(-0.1) * Math.exp(-1.0);
        var unlistedAtOn = SEPARATOR * Math.exp(-0.1) * (1 - Math.exp(-0.5) - Math.exp(-1.0));
        assertEquals(confirm, decision.probabilities().get("CONFIRM"), EPS);
        assertEquals(cancel + unlistedAtOn, decision.probabilities().get("CANCEL"), EPS);
        assertEquals(1 - confirm - cancel - unlistedAtOn, decision.probabilities().get("ESCALATE"), EPS);
        assertEquals(escalate + confirm + cancel, decision.observedMass(), EPS);
        assertEquals("CONFIRM", decision.mostLikely());
        assertEquals((3 * confirm - 1) / 2, confidence.aggregate().getAsDouble(), EPS);
        assertEquals(List.of("C", "ON"), confidence.tokens().stream().map(t -> t.token()).toList(),
                "the walk stops once the value is determined");
    }

    @Test
    void booleanDecisionScoresTrueAgainstFalse() {
        var session = run("case-boolean", Approval.class, decisionOn("approved"));
        // Two allowed values + three formatting slots.
        assertTrue(lastBody().contains("\"top_logprobs\":5"), lastBody());

        var confidence = session.confidence.get();
        assertNotNull(confidence);
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, confidence.source());
        var decision = confidence.decision().orElseThrow();
        // " false" and the unspaced "false" both count for false; " null" is
        // off-schema and unobserved, so it lands on the lighter value, false.
        var yes = SEPARATOR * Math.exp(-0.3);
        var no = SEPARATOR * (Math.exp(-1.6) + Math.exp(-4.0));
        assertEquals(yes, decision.probabilities().get("true"), EPS);
        assertEquals(1 - yes, decision.probabilities().get("false"), EPS);
        assertEquals(yes + no, decision.observedMass(), EPS);
        assertEquals(2 * yes - 1, confidence.aggregate().getAsDouble(), EPS);
    }

    /**
     * The decision is split at the token carrying the opening quote
     * ({@code " \""} sampled against {@code " \"REJECT"}); the APPROVE token
     * after it is certain. Scoring alternatives only from the value's first
     * character on reported 1.0 and routed a coin flip to ACT.
     */
    @Test
    void decisionSplitAtTheQuoteTokenEscalates() {
        var session = run("case-quote-split", Triage.class, decisionOn("verdict"));
        var confidence = session.confidence.get();
        assertNotNull(confidence);
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, confidence.source());
        var decision = confidence.decision().orElseThrow();
        assertEquals(SEPARATOR * Math.exp(-0.70) * Math.exp(-0.0001),
                decision.probabilities().get("APPROVE"), EPS);
        assertEquals(SEPARATOR * Math.exp(-0.71), decision.probabilities().get("REJECT"), EPS);
        assertTrue(confidence.aggregate().getAsDouble() < ConfidenceRouting.DEFAULT_CONFIRM_AT,
                "a coin flip made at the quote token must escalate: " + confidence.aggregate());
        assertEquals(ConfidenceRoute.ESCALATE, ConfidenceRouting.defaults().route(confidence));
    }

    /**
     * The rival spells the separator differently from the sampled token
     * ({@code " \"REJECT"} against a sampled {@code "\"APPROVE"}). Matching
     * alternatives on the sampled token's exact prefix dropped it and scored
     * APPROVE 1.0.
     */
    @Test
    void rivalWithDifferentSeparatorSpacingEscalates() {
        var session = run("case-spacing", Triage.class, decisionOn("verdict"));
        var confidence = session.confidence.get();
        assertNotNull(confidence);
        var decision = confidence.decision().orElseThrow();
        assertEquals(SEPARATOR * Math.exp(-0.70), decision.probabilities().get("APPROVE"), EPS);
        assertEquals(SEPARATOR * Math.exp(-0.71), decision.probabilities().get("REJECT"), EPS);
        assertEquals(ConfidenceRoute.ESCALATE, ConfidenceRouting.defaults().route(confidence));
    }

    @Test
    void missingTopLogprobsEmitsNoNativeConfidence() {
        var session = run("case-no-top", Triage.class, decisionOn("verdict"));

        assertTrue(lastBody().contains("\"top_logprobs\":6"),
                "the field was requested; the provider ignored it: " + lastBody());
        // Staying silent leaves the pipeline's model-reported-field fallback
        // in charge; emitting the fluency mean would route on the wrong signal.
        assertNull(session.confidence.get(),
                "a decision that cannot be scored must not fall back to the fluency mean");
        assertTrue(session.completed, "the stream must still complete normally");
    }

    @Test
    void decisionFieldIsIgnoredWithoutAStructuredResponseType() {
        var session = run("case-split", null, decisionOn("verdict"));

        assertFalse(lastBody().contains("top_logprobs"),
                "free-text responses have no decision to score: " + lastBody());
        var confidence = session.confidence.get();
        assertNotNull(confidence);
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, confidence.source());
    }

    @Test
    void decisionFieldThatIsNotEnumOrBooleanIsIgnored() {
        var session = run("case-split", Triage.class, decisionOn("reason"));

        assertFalse(lastBody().contains("top_logprobs"),
                "a free string field has no closed value set: " + lastBody());
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, session.confidence.get().source());
    }

    /**
     * The decision is read from the final round only: the tool round's text
     * also carries a confident {@code "verdict":"REJECT"}, and scoring it
     * would report the wrong decision with the wrong confidence.
     */
    @Test
    void toolLoopScoresTheFinalRoundAndKeepsTopLogprobs() {
        var lookup = org.atmosphere.ai.tool.ToolDefinition
                .builder("lookup", "Look up a record")
                .parameter("id", "record id", "string")
                .executor(args -> "{\"status\":\"open\"}")
                .build();
        var before = BODIES.size();
        var session = run("case-tool", Triage.class, decisionOn("verdict"), List.of(lookup));

        var bodies = List.copyOf(BODIES.subList(before, BODIES.size()));
        assertEquals(2, bodies.size(), "a tool round and a final round");
        assertTrue(bodies.get(1).contains("\"top_logprobs\":6"),
                "the follow-up round must still request top_logprobs: " + bodies.get(1));

        var confidence = session.confidence.get();
        assertNotNull(confidence);
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, confidence.source());
        var decision = confidence.decision().orElseThrow();
        assertEquals(SEPARATOR * Math.exp(-0.69),
                decision.probabilities().get("APPROVE"), EPS,
                "the final round's split decision, not the tool round's REJECT");
    }

    /**
     * The whole consumer path: an {@code AiPipeline} with a structured
     * response type, a decision-field elicitation and a routing. The split
     * decision reaches the routing handler as DECISION_LOGPROBS with its
     * distribution and escalates; the model-reported field is not parsed on
     * top of it.
     */
    @Test
    void pipelineRoutesTheDecisionConfidence() {
        var pipeline = new org.atmosphere.ai.AiPipeline(configuredRuntime(), "You are a triage agent",
                "gpt-5-mini", null, null, List.of(), List.of(),
                org.atmosphere.ai.AiMetrics.NOOP, Triage.class);
        var decisions = new java.util.concurrent.CopyOnWriteArrayList<org.atmosphere.ai.ConfidenceDecision>();
        pipeline.setDefaultConfidenceElicitation(decisionOn("verdict"));
        pipeline.setDefaultConfidenceRouting(ConfidenceRouting.defaults().withHandler(decisions::add));

        var session = new CapturingSession();
        pipeline.execute("c1", "case-split", session);
        session.await();

        // The pipeline applies provider-native structured output by default,
        // so this is the strict json_schema path OpenAI enforces.
        assertTrue(lastBody().contains("\"json_schema\""), lastBody());
        assertTrue(lastBody().contains("\"top_logprobs\":6"), lastBody());
        assertEquals(1, decisions.size(), "exactly one routed decision per turn");
        var decision = decisions.get(0);
        assertEquals(ConfidenceRoute.ESCALATE, decision.route());
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, decision.confidence().source());
        assertEquals("verdict", decision.confidence().decision().orElseThrow().field());
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, session.confidence.get().source(),
                "the leaf session sees the same single confidence event");
    }

    /**
     * Mode scope (Correctness Invariant #7): the Responses API body never
     * requests logprobs or top_logprobs, so decision confidence is a
     * chat-completions-only signal, as modules/ai/README.md documents.
     */
    @Test
    void responsesApiPathNeverRequestsTopLogprobs() throws Exception {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("sk-test")
                .build();
        var field = DecisionField.fromSchema(
                org.atmosphere.ai.NativeStructuredOutput.schemaFor(Triage.class), "verdict").orElseThrow();
        var request = ChatCompletionRequest.builder("gpt-5-mini")
                .user("case-confident")
                .conversationId("conv-1")
                .logprobs(true)
                .decisionField(field)
                .build();
        var m = OpenAiCompatibleClient.class.getDeclaredMethod(
                "buildResponsesApiBody", ChatCompletionRequest.class, String.class);
        m.setAccessible(true);
        var body = (String) m.invoke(client, request, null);
        assertFalse(body.contains("logprobs"), "the Responses API body carries no logprobs: " + body);
    }

    // ---------------------------------------------------------------- helpers

    private static AiConfidenceElicitation decisionOn(String field) {
        return AiConfidenceElicitation.defaults().withDecisionField(field);
    }

    private static String lastBody() {
        return BODIES.get(BODIES.size() - 1);
    }

    private CapturingSession run(String message, Class<?> responseType,
                                 AiConfidenceElicitation elicitation) {
        return run(message, responseType, elicitation, List.of());
    }

    private static BuiltInAgentRuntime configuredRuntime() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/v1")
                .apiKey("sk-test")
                .build();
        var runtime = new BuiltInAgentRuntime();
        runtime.configure(new org.atmosphere.ai.AiConfig.LlmSettings(
                client, "gpt-5-mini", "remote", null, "sk-test",
                PromptCacheKeyMode.AUTO, org.atmosphere.ai.GenerationParams.defaults()));
        return runtime;
    }

    private CapturingSession run(String message, Class<?> responseType,
                                 AiConfidenceElicitation elicitation,
                                 List<org.atmosphere.ai.tool.ToolDefinition> tools) {
        var runtime = configuredRuntime();
        var context = new AgentExecutionContext(
                message, "You are a triage agent", "gpt-5-mini",
                null, "session-1", "user-1", "conv-1",
                tools, null, null, List.of(),
                Map.of(AiConfidenceElicitation.METADATA_KEY, elicitation),
                List.of(), responseType, null);
        var session = new CapturingSession();
        runtime.execute(context, session);
        session.await();
        return session;
    }

    private static String fixture(String name) {
        try (var in = OpenAiCompatibleClientDecisionConfidenceTest.class
                .getResourceAsStream("/fixtures/logprobs/" + name + ".sse")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        private final AtomicReference<AiConfidence> confidence = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean closed;
        private volatile boolean completed;

        @Override public String sessionId() { return "test-session"; }

        @Override public void send(String t) { }

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
                throw new IllegalStateException("interrupted while awaiting stream", ie);
            }
        }
    }
}
