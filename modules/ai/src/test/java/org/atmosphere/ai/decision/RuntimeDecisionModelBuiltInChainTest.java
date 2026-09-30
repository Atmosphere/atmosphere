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
package org.atmosphere.ai.decision;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ConfidenceRoute;
import org.atmosphere.ai.ConfidenceRouting;
import org.atmosphere.ai.GenerationParams;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.ai.llm.PromptCacheKeyMode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision chain in one run, with nothing scripted between the model and
 * the answer: {@link RuntimeDecisionModel} → {@link BuiltInAgentRuntime}
 * (decision field resolved from the stamped schema) → the chat-completions
 * client's {@code top_logprobs} → the decision scorer → the typed answer's
 * probabilities and confidence. The provider is a loopback stub serving
 * hand-authored SSE fixtures ({@code fixtures/logprobs/decision-model-*.sse});
 * the logprob values are chosen by hand, not captured from a live provider.
 */
class RuntimeDecisionModelBuiltInChainTest {

    private static final double EPS = 1e-9;

    /** Probability of the fixtures' separator token after the {@code answer} key. */
    private static final double SEPARATOR = Math.exp(-0.0003);

    private static HttpServer server;
    private static final List<String> BODIES = Collections.synchronizedList(new ArrayList<>());
    private static RuntimeDecisionModel model;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            BODIES.add(body);
            String fixture;
            if (body.contains("case-minority")) {
                fixture = "decision-model-choice-minority";
            } else if (body.contains("OPTIONS")) {
                fixture = "decision-model-choice";
            } else {
                fixture = "decision-model-noul";
            }
            respond(exchange, fixture(fixture));
        });
        server.start();
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("sk-test")
                .build();
        var runtime = new BuiltInAgentRuntime();
        runtime.configure(new AiConfig.LlmSettings(client, "gpt-5-mini", "remote", null, "sk-test",
                PromptCacheKeyMode.AUTO, GenerationParams.defaults()));
        model = new RuntimeDecisionModel(runtime);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void noulCarriesTheMeasuredProbabilityOfTrue() {
        var result = model.decide(DecisionRequest.of("case-noul", "q",
                new Question.Noul("Is it raining?", "wet", "dry")));
        var answer = assertInstanceOf(Answer.Noul.class, result.answers().get("q"),
                String.valueOf(result.answers().get("q")));
        assertTrue(answer.value());
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, answer.confidence().source());
        var yes = SEPARATOR * Math.exp(-0.1);
        assertEquals(yes, answer.probabilityTrue().getAsDouble(), EPS);
        assertEquals(2 * yes - 1, answer.confidence().aggregate().getAsDouble(), EPS);
        var body = BODIES.stream().filter(b -> b.contains("case-noul")).findFirst().orElseThrow();
        assertTrue(body.contains("\"top_logprobs\""), "the decision must request top_logprobs: " + body);
        assertTrue(body.contains("\"boolean\""), "the question's schema reaches the provider: " + body);
    }

    @Test
    void codedChoiceMapsTheDistributionBackToItsOptions() {
        var result = model.decide(DecisionRequest.of("case-choice", "q",
                new Question.Choice("Pick one", options())));
        var answer = assertInstanceOf(Answer.Choice.class, result.answers().get("q"),
                String.valueOf(result.answers().get("q")));
        assertEquals("reject", answer.choice());
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, answer.confidence().source());
        assertEquals(List.of("approve", "reject", "defer"), List.copyOf(answer.probabilities().keySet()));
        var reject = SEPARATOR * Math.exp(-0.2);
        assertEquals(reject, answer.probabilities().get("reject"), EPS);
        assertEquals((3 * reject - 1) / 2, answer.confidence().aggregate().getAsDouble(), EPS);
        assertEquals(ConfidenceRoute.CONFIRM, result.route("q", ConfidenceRouting.defaults()));
    }

    /** A value sampled against the distribution scores 0 on the real path too. */
    @Test
    void sampledMinorityChoiceEscalates() {
        var result = model.decide(DecisionRequest.of("case-minority", "q",
                new Question.Choice("Pick one", options())));
        var answer = assertInstanceOf(Answer.Choice.class, result.answers().get("q"),
                String.valueOf(result.answers().get("q")));
        assertEquals("approve", answer.choice());
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, answer.confidence().source());
        assertTrue(answer.probabilities().get("reject") > answer.probabilities().get("approve"),
                answer.probabilities().toString());
        assertEquals(0.0, answer.confidence().aggregate().getAsDouble(), EPS);
        assertEquals(ConfidenceRoute.ESCALATE, result.route("q", ConfidenceRouting.defaults()));
    }

    private static LinkedHashMap<String, String> options() {
        var options = new LinkedHashMap<String, String>();
        options.put("approve", "");
        options.put("reject", "");
        options.put("defer", "");
        return options;
    }

    private static String fixture(String name) {
        try (var in = RuntimeDecisionModelBuiltInChainTest.class
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
}
