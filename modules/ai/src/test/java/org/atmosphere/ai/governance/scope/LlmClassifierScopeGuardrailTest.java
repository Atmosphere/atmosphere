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
package org.atmosphere.ai.governance.scope;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.DecisionDistribution;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.atmosphere.ai.decision.NoulGate;
import org.atmosphere.ai.decision.Question;
import org.atmosphere.ai.decision.ScriptedDecisionRuntime;
import org.atmosphere.ai.decision.TestDecisionModels;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClassifierScopeGuardrailTest {

    private static final AiRequest REQUEST = new AiRequest("where is my order?");

    @Test
    void tierIsLlmClassifier() {
        assertEquals(AgentScope.Tier.LLM_CLASSIFIER,
                new LlmClassifierScopeGuardrail((AgentRuntime) null).tier());
    }

    @Test
    void measuredBeliefsMapThroughTheThresholds() {
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, classify(measured(0.05)).outcome());
        var rejected = classify(measured(0.9));
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, rejected.outcome());
        assertTrue(rejected.reason().contains("off-topic") && rejected.reason().contains("DECISION_LOGPROBS"),
                rejected.reason());
        var band = classify(measured(0.35));
        assertEquals(ScopeGuardrail.Outcome.ERROR, band.outcome(), "the band between the thresholds");
        assertTrue(band.reason().contains("P(off-topic)=0.350"), band.reason());
    }

    @Test
    void reportedConfidenceReadsAsTheSameBelief() {
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, classify(reported(false, 0.95)).outcome());
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, classify(reported(true, 0.9)).outcome());
        assertEquals(ScopeGuardrail.Outcome.ERROR, classify(reported(false, 0.7)).outcome(),
                "P(off-topic)=0.3 sits in the uncertain band");
        assertEquals(ScopeGuardrail.Outcome.ERROR, classify(reported(true, 0.1)).outcome(),
                "a true answer is never cleared by a belief that disagrees with it");
    }

    @Test
    void aFalseAnswerWithNoConfidenceIsAnErrorAndATrueOneRejects() {
        var unknown = AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD);
        var cleared = classify(answering(new Answer.Noul(LlmClassifierScopeGuardrail.QUESTION_ID, false,
                OptionalDouble.empty(), unknown)));
        assertEquals(ScopeGuardrail.Outcome.ERROR, cleared.outcome());
        assertTrue(cleared.reason().contains("no confidence"), cleared.reason());
        var flagged = classify(answering(new Answer.Noul(LlmClassifierScopeGuardrail.QUESTION_ID, true,
                OptionalDouble.empty(), unknown)));
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, flagged.outcome());
    }

    @Test
    void everyFailedAnswerIsAnError() {
        for (var reason : Answer.Failed.Reason.values()) {
            var decision = classify(answering(new Answer.Failed(LlmClassifierScopeGuardrail.QUESTION_ID,
                    reason, "detail")));
            assertEquals(ScopeGuardrail.Outcome.ERROR, decision.outcome(), reason.name());
            assertTrue(decision.reason().contains(reason.name().toLowerCase(java.util.Locale.ROOT)),
                    decision.reason());
        }
    }

    @Test
    void aThrowingModelOrAMissingAnswerIsAnError() {
        var throwing = classify(model(r -> {
            throw new IllegalStateException("backend down");
        }));
        assertEquals(ScopeGuardrail.Outcome.ERROR, throwing.outcome());
        assertTrue(throwing.reason().contains("backend down"), throwing.reason());
        var missing = classify(model(r -> new DecisionResult("m", Map.of(), Optional.empty(), Duration.ZERO)));
        assertEquals(ScopeGuardrail.Outcome.ERROR, missing.outcome());
        assertTrue(missing.reason().contains("no answer"), missing.reason());
    }

    @Test
    void oversizeRequestIsAnErrorWithoutAModelCall() {
        var calls = new AtomicInteger();
        var guardrail = new LlmClassifierScopeGuardrail(model(r -> {
            calls.incrementAndGet();
            return TestDecisionModels.measured(r, 0.0);
        }), null, null, false);
        var huge = new AiRequest("x".repeat(DecisionRequest.MAX_STATE_CHARS + 1));
        var decision = guardrail.evaluate(huge, supportConfig());
        assertEquals(ScopeGuardrail.Outcome.ERROR, decision.outcome());
        assertTrue(decision.reason().contains(String.valueOf(DecisionRequest.MAX_STATE_CHARS)), decision.reason());
        assertEquals(0, calls.get());
    }

    @Test
    void failOpenAdmitsAnUncertainVerdictButStillRejectsAFlaggedOne() {
        var uncertain = new LlmClassifierScopeGuardrail(answering(new Answer.Failed(
                LlmClassifierScopeGuardrail.QUESTION_ID, Answer.Failed.Reason.TIMEOUT, "late")), null, null, true)
                .evaluate(REQUEST, supportConfig());
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, uncertain.outcome());
        assertTrue(uncertain.reason().startsWith("fail-open"), uncertain.reason());
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE,
                new LlmClassifierScopeGuardrail(measured(0.9), null, null, true)
                        .evaluate(REQUEST, supportConfig()).outcome());
    }

    @Test
    void failOpenPropertyIsReadByTheRuntimeConstructors() {
        var empty = new ScriptedDecisionRuntime((ctx, s) -> s.complete());
        System.setProperty(LlmClassifierScopeGuardrail.FAIL_OPEN_PROPERTY, "true");
        try {
            assertEquals(ScopeGuardrail.Outcome.IN_SCOPE,
                    new LlmClassifierScopeGuardrail(empty).evaluate(REQUEST, supportConfig()).outcome());
        } finally {
            System.clearProperty(LlmClassifierScopeGuardrail.FAIL_OPEN_PROPERTY);
        }
        assertEquals(ScopeGuardrail.Outcome.ERROR,
                new LlmClassifierScopeGuardrail(empty).evaluate(REQUEST, supportConfig()).outcome());
    }

    @Test
    void customThresholdsApply() {
        var strict = new LlmClassifierScopeGuardrail(measured(0.3), null, new NoulGate(0.25, 0.1), false);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, strict.evaluate(REQUEST, supportConfig()).outcome());
    }

    @Test
    void unrestrictedAndBlankRequestsMakeNoCall() {
        var calls = new AtomicInteger();
        var guardrail = new LlmClassifierScopeGuardrail(model(r -> {
            calls.incrementAndGet();
            return TestDecisionModels.measured(r, 0.9);
        }), null, null, false);
        var unrestricted = new ScopeConfig("", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.LLM_CLASSIFIER, 0.45, false, true, "LLM playground");
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, guardrail.evaluate(REQUEST, unrestricted).outcome());
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE,
                guardrail.evaluate(new AiRequest("  "), supportConfig()).outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void asksOneBooleanQuestionCarryingThePurposeAndForbiddenTopics() {
        var captured = new AtomicReference<DecisionRequest>();
        var config = new ScopeConfig("order support", List.of("medical", "legal"),
                AgentScope.Breach.DENY, "", AgentScope.Tier.LLM_CLASSIFIER, 0.45, false, false, "");
        new LlmClassifierScopeGuardrail(model(r -> {
            captured.set(r);
            return TestDecisionModels.measured(r, 0.0);
        }), Duration.ofMillis(1234), null, false).evaluate(REQUEST, config);

        var request = captured.get();
        assertEquals(REQUEST.message(), request.state(), "the request is the state, never the instructions");
        assertEquals(Duration.ofMillis(1234), request.timeout());
        assertEquals(List.of(LlmClassifierScopeGuardrail.QUESTION_ID), List.copyOf(request.questions().keySet()));
        var question = assertInstanceOf(Question.Noul.class,
                request.questions().get(LlmClassifierScopeGuardrail.QUESTION_ID));
        assertTrue(question.instructions().contains("order support"), question.instructions());
        assertTrue(question.instructions().contains("- medical"), question.instructions());
        assertTrue(question.instructions().contains("- legal"), question.instructions());
        assertTrue(question.whenTrue().contains("off-topic"), question.whenTrue());
    }

    /**
     * The historical {@code (AgentRuntime)} constructor goes through a
     * RuntimeDecisionModel: a JSON verdict classifies, and the measured
     * distribution is what the thresholds read.
     */
    @Test
    void runtimeConstructorGoesThroughTheDecisionModel() {
        var inScope = new ScriptedDecisionRuntime((ctx, s) ->
                ScriptedDecisionRuntime.reply(s, "{\"answer\":false,\"confidence\":0.97}"));
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE,
                new LlmClassifierScopeGuardrail(inScope).evaluate(REQUEST, supportConfig()).outcome());

        var measuredOffTopic = new ScriptedDecisionRuntime((ctx, s) -> {
            s.send("{\"answer\":false,\"confidence\":0.99}");
            s.confidence(AiConfidence.fromDecision(new DecisionDistribution("answer",
                    Map.of("true", 0.8, "false", 0.2), 1.0), List.of()));
            s.complete();
        });
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE,
                new LlmClassifierScopeGuardrail(measuredOffTopic).evaluate(REQUEST, supportConfig()).outcome(),
                "the measured distribution, not the sampled answer or its self-report, decides");
    }

    private static ScopeGuardrail.Decision classify(DecisionModel model) {
        return new LlmClassifierScopeGuardrail(model, null, null, false).evaluate(REQUEST, supportConfig());
    }

    private static ScopeConfig supportConfig() {
        return new ScopeConfig(
                "customer support for orders and billing",
                List.of(),
                AgentScope.Breach.DENY, "",
                AgentScope.Tier.LLM_CLASSIFIER, 0.45, false, false, "");
    }

    private static DecisionModel measured(double p) {
        return model(r -> TestDecisionModels.measured(r, p));
    }

    private static DecisionModel reported(boolean value, double confidence) {
        return answering(new Answer.Noul(LlmClassifierScopeGuardrail.QUESTION_ID, value, OptionalDouble.empty(),
                AiConfidence.reported(confidence)));
    }

    private static DecisionModel answering(Answer answer) {
        return model(r -> new DecisionResult("m", Map.of(answer.id(), answer), Optional.empty(), Duration.ZERO));
    }

    private static DecisionModel model(Function<DecisionRequest, DecisionResult> behaviour) {
        return new DecisionModel() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public DecisionResult decide(DecisionRequest request) {
                return behaviour.apply(request);
            }
        };
    }
}
