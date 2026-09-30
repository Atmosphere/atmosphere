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
package org.atmosphere.ai.guardrails;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.atmosphere.ai.decision.Question;
import org.atmosphere.ai.decision.ScriptedDecisionRuntime;
import org.atmosphere.ai.decision.TestDecisionModels;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmModerationDetectorTest {

    private static final String TEXT = "text the phrase rules do not match";

    @Test
    void everyCategoryClearedIsClean() {
        var result = detect(overriding(Map.of()));
        assertFalse(result.isFlagged());
        assertFalse(result.errored());
    }

    @Test
    void aFlaggedCategoryCarriesItsMeasuredProbabilityAsTheScore() {
        var result = detect(overriding(Map.of(ModerationCategory.VIOLENCE, 0.9)));
        assertFalse(result.errored(), result.detail());
        assertEquals(Set.of(ModerationCategory.VIOLENCE), result.flagged());
        assertEquals(0.9, result.scores().get(ModerationCategory.VIOLENCE), 1e-9);
        assertTrue(result.detail().contains("P(violence)=0.900"), result.detail());
    }

    @Test
    void anUncertainCategoryMakesTheResultAnError() {
        var result = detect(overriding(Map.of(ModerationCategory.HATE, 0.3)));
        assertTrue(result.errored(), "a category in the uncertain band is not cleared");
        assertTrue(result.flagged().isEmpty());
        assertTrue(result.detail().contains("HATE"), result.detail());
    }

    @Test
    void aFlaggedCategoryIsKeptWhenAnotherIsUncertain() {
        var result = detect(model(r -> {
            var answers = new LinkedHashMap<>(TestDecisionModels.measured(r, 0.0).answers());
            answers.put("violence", TestDecisionModels.measured(r, 0.95).answers().get("violence"));
            answers.put("illicit", new Answer.Failed("illicit", Answer.Failed.Reason.TIMEOUT, "late"));
            return new DecisionResult("m", answers, Optional.empty(), Duration.ZERO);
        }));
        assertTrue(result.errored());
        assertEquals(Set.of(ModerationCategory.VIOLENCE), result.flagged());
        assertTrue(result.detail().contains("ILLICIT") && result.detail().contains("timeout"), result.detail());
    }

    @Test
    void reportedConfidenceReadsAsTheSameBeliefAndUnknownConfidenceHasNoScore() {
        var reported = detect(answeringFor(ModerationCategory.SEXUAL,
                new Answer.Noul("sexual", true, OptionalDouble.empty(), AiConfidence.reported(0.8))));
        assertEquals(Set.of(ModerationCategory.SEXUAL), reported.flagged());
        assertEquals(0.8, reported.scores().get(ModerationCategory.SEXUAL), 1e-9);

        var unknown = detect(answeringFor(ModerationCategory.SEXUAL, new Answer.Noul("sexual", true,
                OptionalDouble.empty(), AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD))));
        assertEquals(Set.of(ModerationCategory.SEXUAL), unknown.flagged());
        assertTrue(unknown.scores().isEmpty(), "no score is invented for an unmeasured flag");

        var unsureFalse = detect(answeringFor(ModerationCategory.SEXUAL, new Answer.Noul("sexual", false,
                OptionalDouble.empty(), AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD))));
        assertTrue(unsureFalse.errored(), "a false answer with no confidence does not clear the category");
    }

    @Test
    void everyFailedAnswerIsAnError() {
        for (var reason : Answer.Failed.Reason.values()) {
            var result = detect(answeringFor(ModerationCategory.HARASSMENT,
                    new Answer.Failed("harassment", reason, "detail")));
            assertTrue(result.errored(), reason.name());
            assertTrue(result.detail().contains(reason.name().toLowerCase(Locale.ROOT)), result.detail());
        }
    }

    @Test
    void aThrowingModelOrMissingAnswersAreAnError() {
        var throwing = detect(model(r -> {
            throw new IllegalStateException("backend down");
        }));
        assertTrue(throwing.errored());
        assertTrue(throwing.detail().contains("backend down"), throwing.detail());
        var missing = detect(model(r -> new DecisionResult("m", Map.of(), Optional.empty(), Duration.ZERO)));
        assertTrue(missing.errored());
        assertTrue(missing.detail().contains("no answer"), missing.detail());
    }

    @Test
    void oversizeTextIsAnErrorAndBlankTextIsCleanWithoutAModelCall() {
        var calls = new AtomicInteger();
        var detector = new LlmModerationDetector(model(r -> {
            calls.incrementAndGet();
            return TestDecisionModels.measured(r, 0.0);
        }), null, null);
        var huge = detector.detect("x".repeat(DecisionRequest.MAX_STATE_CHARS + 1));
        assertTrue(huge.errored());
        assertTrue(huge.detail().contains(String.valueOf(DecisionRequest.MAX_STATE_CHARS)), huge.detail());
        assertFalse(detector.detect("   ").errored());
        assertFalse(detector.detect(null).errored());
        assertEquals(0, calls.get());
    }

    @Test
    void asksOneBooleanQuestionPerCategoryInOneRequest() {
        var captured = new AtomicReference<DecisionRequest>();
        new LlmModerationDetector(model(r -> {
            captured.set(r);
            return TestDecisionModels.measured(r, 0.0);
        }), Duration.ofMillis(1234), null).detect(TEXT);
        var request = captured.get();
        assertEquals(TEXT, request.state(), "the text is the state, never the instructions");
        assertEquals(Duration.ofMillis(1234), request.timeout());
        var expectedIds = Arrays.stream(ModerationCategory.values())
                .map(c -> c.name().toLowerCase(Locale.ROOT)).toList();
        assertEquals(expectedIds, List.copyOf(request.questions().keySet()));
        for (var category : ModerationCategory.values()) {
            var question = assertInstanceOf(Question.Noul.class,
                    request.questions().get(category.name().toLowerCase(Locale.ROOT)));
            assertTrue(question.instructions().contains("'" + category.label() + "'"), question.instructions());
        }
    }

    /**
     * The historical {@code (AgentRuntime)} constructor goes through a
     * RuntimeDecisionModel end to end: each category is its own JSON verdict,
     * and a flagged one blocks the turn through the guardrail.
     */
    @Test
    void runtimeConstructorGoesThroughTheDecisionModel() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> ScriptedDecisionRuntime.reply(s,
                ctx.systemPrompt().contains("'violence'")
                        ? "{\"answer\":true,\"confidence\":0.95}"
                        : "{\"answer\":false,\"confidence\":0.97}"));
        var detector = new LlmModerationDetector(runtime);
        var result = detector.detect(TEXT);
        assertFalse(result.errored(), result.detail());
        assertEquals(Set.of(ModerationCategory.VIOLENCE), result.flagged());
        assertEquals(ModerationCategory.values().length, runtime.contexts().size(),
                "one isolated dispatch per category");
        var blocked = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                new ModerationGuardrail(detector).inspectRequest(new AiRequest(TEXT)));
        assertTrue(blocked.reason().contains("VIOLENCE"), blocked.reason());
    }

    private static ModerationDetector.ModerationResult detect(DecisionModel model) {
        return new LlmModerationDetector(model, null, null).detect(TEXT);
    }

    /** Every category measured at {@code P=0} except the given overrides. */
    private static DecisionModel overriding(Map<ModerationCategory, Double> probabilities) {
        return model(r -> {
            var answers = new LinkedHashMap<>(TestDecisionModels.measured(r, 0.0).answers());
            probabilities.forEach((category, p) -> {
                var id = category.name().toLowerCase(Locale.ROOT);
                answers.put(id, TestDecisionModels.measured(r, p).answers().get(id));
            });
            return new DecisionResult("m", answers, Optional.empty(), Duration.ZERO);
        });
    }

    /** Every category measured at {@code P=0} except {@code category}, which gets {@code answer}. */
    private static DecisionModel answeringFor(ModerationCategory category, Answer answer) {
        return model(r -> {
            var answers = new LinkedHashMap<>(TestDecisionModels.measured(r, 0.0).answers());
            answers.put(category.name().toLowerCase(Locale.ROOT), answer);
            return new DecisionResult("m", answers, Optional.empty(), Duration.ZERO);
        });
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
