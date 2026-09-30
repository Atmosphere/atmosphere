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
package org.atmosphere.ai.governance.rag;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.DecisionDistribution;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LLM injection tier over a {@link DecisionModel}: a measured
 * {@code P(injection)} is thresholded with an uncertain band that fails
 * closed, and every failure — including the empty / ambiguous / timed-out
 * replies the free-text classifier used to admit — is an ERROR.
 */
class LlmClassifierInjectionClassifierTest {

    private static final ContextProvider.Document DOC =
            new ContextProvider.Document("Some retrieved text.", "docs/a.md", 1.0);

    @Test
    void measuredHighProbabilityIsInjected() {
        var decision = classify(measured(0.93));
        assertEquals(InjectionClassifier.Outcome.INJECTED, decision.outcome());
        assertEquals(0.93, decision.confidence(), 1e-9);
        assertTrue(decision.reason().contains("P(injection)=0.930"), decision.reason());
        assertTrue(decision.reason().contains("[DECISION_LOGPROBS]"), decision.reason());
    }

    @Test
    void measuredLowProbabilityIsSafe() {
        var decision = classify(measured(0.1));
        assertEquals(InjectionClassifier.Outcome.SAFE, decision.outcome());
        assertEquals(0.9, decision.confidence(), 1e-9);
    }

    @Test
    void measuredUncertainBandFailsClosed() {
        var decision = classify(measured(0.3));
        assertEquals(InjectionClassifier.Outcome.ERROR, decision.outcome());
        assertTrue(decision.reason().contains("uncertain"), decision.reason());
        // The injected threshold is inclusive.
        assertEquals(InjectionClassifier.Outcome.INJECTED, classify(measured(0.5)).outcome());
        assertEquals(InjectionClassifier.Outcome.SAFE, classify(measured(0.19)).outcome());
    }

    @Test
    void unmeasuredAnswerReadsTheReportedConfidenceAsTheSameBelief() {
        var injected = classify(reported(true, 0.7));
        assertEquals(InjectionClassifier.Outcome.INJECTED, injected.outcome());
        assertEquals(0.7, injected.confidence(), 1e-9);
        assertTrue(injected.reason().contains("MODEL_REPORTED_FIELD"), injected.reason());

        var safe = classify(reported(false, 0.95));
        assertEquals(InjectionClassifier.Outcome.SAFE, safe.outcome());
        assertEquals(0.95, safe.confidence(), 1e-9);

        // A true answer with no confidence is still flagged, never admitted as safe.
        var flaggedUnknown = classify(answering(new Answer.Noul("injection", true, OptionalDouble.empty(),
                AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD))));
        assertEquals(InjectionClassifier.Outcome.INJECTED, flaggedUnknown.outcome());
        assertTrue(Double.isNaN(flaggedUnknown.confidence()));
    }

    /**
     * Mode parity: an unsure or unmeasured "false" does not clear the document.
     * A 5%-confident false and a false with no confidence both fail closed, as
     * the uncertain band does on the measured path.
     */
    @Test
    void unsureOrUnknownFalseFailsClosed() {
        var unsure = classify(reported(false, 0.05));
        assertEquals(InjectionClassifier.Outcome.ERROR, unsure.outcome(), unsure.reason());
        assertTrue(unsure.reason().contains("uncertain"), unsure.reason());
        assertTrue(unsure.reason().contains("MODEL_REPORTED_FIELD"), unsure.reason());
        // Inside the uncertain band: 1 - 0.75 = 0.25 is not below safeBelow.
        assertEquals(InjectionClassifier.Outcome.ERROR, classify(reported(false, 0.75)).outcome());
        // A true answer the model barely believes never reads as safe either.
        assertEquals(InjectionClassifier.Outcome.ERROR, classify(reported(true, 0.1)).outcome());

        var unknown = classify(answering(new Answer.Noul("injection", false, OptionalDouble.empty(),
                AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD))));
        assertEquals(InjectionClassifier.Outcome.ERROR, unknown.outcome(), unknown.reason());
        assertTrue(unknown.reason().contains("uncertain"), unknown.reason());
    }

    /** The same belief gets the same verdict whether it was measured or self-reported. */
    @Test
    void measuredAndReportedBeliefsGetTheSameVerdict() {
        // P(injection) = 0.3: measured, or reported as false with confidence 0.7.
        assertEquals(InjectionClassifier.Outcome.ERROR, classify(measured(0.3)).outcome());
        assertEquals(InjectionClassifier.Outcome.ERROR, classify(reported(false, 0.7)).outcome());
        // P(injection) = 0.1: safe in both modes.
        assertEquals(InjectionClassifier.Outcome.SAFE, classify(measured(0.1)).outcome());
        assertEquals(InjectionClassifier.Outcome.SAFE, classify(reported(false, 0.9)).outcome());
        // P(injection) = 0.8: injected in both modes.
        assertEquals(InjectionClassifier.Outcome.INJECTED, classify(measured(0.8)).outcome());
        assertEquals(InjectionClassifier.Outcome.INJECTED, classify(reported(true, 0.8)).outcome());
    }

    @Test
    void everyFailedAnswerIsAnError() {
        for (var reason : Answer.Failed.Reason.values()) {
            var decision = classify(answering(new Answer.Failed("injection", reason, "detail")));
            assertEquals(InjectionClassifier.Outcome.ERROR, decision.outcome(), reason.name());
            assertTrue(decision.reason().contains(reason.name().toLowerCase(java.util.Locale.ROOT)),
                    decision.reason());
        }
    }

    @Test
    void aThrowingModelOrAMissingAnswerIsAnError() {
        DecisionModel throwing = model(r -> {
            throw new IllegalStateException("backend down");
        });
        assertEquals(InjectionClassifier.Outcome.ERROR,
                new LlmClassifierInjectionClassifier(throwing, null, 0.5, 0.2).evaluate(DOC).outcome());
        DecisionModel silent = model(r -> new DecisionResult("m", Map.of(), Optional.empty(), Duration.ZERO));
        assertEquals(InjectionClassifier.Outcome.ERROR,
                new LlmClassifierInjectionClassifier(silent, null, 0.5, 0.2).evaluate(DOC).outcome());
    }

    @Test
    void blankDocumentMakesNoCall() {
        var calls = new AtomicInteger();
        var classifier = new LlmClassifierInjectionClassifier(model(r -> {
            calls.incrementAndGet();
            return TestDecisionModels.measured(r, 1.0);
        }), null, 0.5, 0.2);
        assertEquals(InjectionClassifier.Outcome.SAFE,
                classifier.evaluate(new ContextProvider.Document("  ", "blank", 1.0)).outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void asksOneBooleanQuestionAboutTheDocumentWithTheConfiguredTimeout() {
        var seen = new java.util.concurrent.atomic.AtomicReference<DecisionRequest>();
        new LlmClassifierInjectionClassifier(model(r -> {
            seen.set(r);
            return TestDecisionModels.measured(r, 0.0);
        }), Duration.ofMillis(1234), 0.5, 0.2).evaluate(DOC);
        var request = seen.get();
        assertEquals(DOC.content(), request.state());
        assertEquals(Duration.ofMillis(1234), request.timeout());
        assertInstanceOf(Question.Noul.class, request.questions().get("injection"));
    }

    @Test
    void thresholdsAreValidated() {
        var model = model(r -> TestDecisionModels.measured(r, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new LlmClassifierInjectionClassifier(model, null, 0.2, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new LlmClassifierInjectionClassifier(model, null, 1.2, 0.5));
    }

    /**
     * The historical {@code (AgentRuntime)} constructor still works end to end:
     * the runtime is wrapped in a RuntimeDecisionModel, so a measured
     * distribution classifies, and replies the free-text classifier admitted
     * (empty, ambiguous prose, a timeout) now fail closed.
     */
    @Test
    void runtimeConstructorGoesThroughTheDecisionModel() {
        var measured = new ScriptedDecisionRuntime((ctx, s) -> {
            s.send("{\"answer\":true,\"confidence\":0.99}");
            s.confidence(AiConfidence.fromDecision(new DecisionDistribution("answer",
                    Map.of("true", 0.97, "false", 0.03), 1.0), List.of()));
            s.complete();
        });
        var injected = new LlmClassifierInjectionClassifier(measured).evaluate(DOC);
        assertEquals(InjectionClassifier.Outcome.INJECTED, injected.outcome());
        assertEquals(0.97, injected.confidence(), 1e-9);

        var empty = new ScriptedDecisionRuntime((ctx, s) -> s.complete());
        assertEquals(InjectionClassifier.Outcome.ERROR,
                new LlmClassifierInjectionClassifier(empty).evaluate(DOC).outcome(),
                "an empty reply used to admit the document");

        var ambiguous = new ScriptedDecisionRuntime((ctx, s) ->
                ScriptedDecisionRuntime.reply(s, "Maybe, it is hard to say."));
        assertEquals(InjectionClassifier.Outcome.ERROR,
                new LlmClassifierInjectionClassifier(ambiguous).evaluate(DOC).outcome(),
                "an ambiguous reply used to admit the document");

        var hung = new ScriptedDecisionRuntime((ctx, s) -> new java.util.concurrent.CountDownLatch(1).await());
        var timedOut = new LlmClassifierInjectionClassifier(hung, Duration.ofMillis(200)).evaluate(DOC);
        assertEquals(InjectionClassifier.Outcome.ERROR, timedOut.outcome(),
                "a timeout used to come back as an empty reply and admit the document");
        assertTrue(timedOut.reason().contains("timeout"), timedOut.reason());
    }

    private static InjectionClassifier.Decision classify(DecisionModel model) {
        return new LlmClassifierInjectionClassifier(model, null, 0.5, 0.2).evaluate(DOC);
    }

    private static DecisionModel measured(double p) {
        return model(r -> TestDecisionModels.measured(r, p));
    }

    private static DecisionModel reported(boolean value, double confidence) {
        return answering(new Answer.Noul("injection", value, OptionalDouble.empty(),
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
