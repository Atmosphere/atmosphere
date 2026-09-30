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

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.DecisionDistribution;
import org.atmosphere.ai.governance.rag.LlmClassifierInjectionClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoulGateTest {

    /** One threshold pair across every LLM safety tier (injection, scope, moderation). */
    @Test
    void defaultsMatchTheInjectionTier() {
        assertEquals(LlmClassifierInjectionClassifier.DEFAULT_INJECTED_AT, NoulGate.DEFAULT_FLAGGED_AT);
        assertEquals(LlmClassifierInjectionClassifier.DEFAULT_SAFE_BELOW, NoulGate.DEFAULT_CLEAR_BELOW);
    }

    @Test
    void thresholdsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new NoulGate(0.2, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new NoulGate(1.5, 0.2));
        assertThrows(IllegalArgumentException.class, () -> new NoulGate(0.5, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new NoulGate(Double.NaN, 0.2));
    }

    @Test
    void measuredBeliefs() {
        assertEquals(NoulGate.Outcome.FLAGGED, judge(measured(true, 0.5)).outcome());
        var clear = judge(measured(false, 0.1));
        assertEquals(NoulGate.Outcome.CLEAR, clear.outcome());
        assertEquals(0.9, clear.confidence(), 1e-9);
        assertEquals(NoulGate.Outcome.UNCERTAIN, judge(measured(false, 0.2)).outcome(), "0.2 is not below 0.2");
        var contradicted = judge(measured(true, 0.1));
        assertEquals(NoulGate.Outcome.UNCERTAIN, contradicted.outcome());
        assertTrue(contradicted.reason().contains("answer is true"), contradicted.reason());
    }

    @Test
    void reportedBeliefs() {
        assertEquals(NoulGate.Outcome.FLAGGED, judge(reported(true, 0.6)).outcome());
        assertEquals(NoulGate.Outcome.CLEAR, judge(reported(false, 0.9)).outcome());
        assertEquals(NoulGate.Outcome.UNCERTAIN, judge(reported(false, 0.6)).outcome());
        assertEquals(NoulGate.Outcome.UNCERTAIN, judge(reported(true, 0.1)).outcome());
        var unknownTrue = judge(new Answer.Noul("q", true, OptionalDouble.empty(),
                AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD)));
        assertEquals(NoulGate.Outcome.FLAGGED, unknownTrue.outcome());
        assertTrue(Double.isNaN(unknownTrue.confidence()));
        assertEquals(NoulGate.Outcome.UNCERTAIN, judge(new Answer.Noul("q", false, OptionalDouble.empty(),
                AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD))).outcome());
    }

    @Test
    void failedMissingAndForeignAnswersAreUncertain() {
        for (var reason : Answer.Failed.Reason.values()) {
            assertEquals(NoulGate.Outcome.UNCERTAIN, judge(new Answer.Failed("q", reason, "x")).outcome());
        }
        assertEquals(NoulGate.Outcome.UNCERTAIN, judge(null).outcome());
        assertEquals(NoulGate.Outcome.UNCERTAIN, judge(new Answer.Choice("q", "a", Map.of(),
                AiConfidence.reported(0.99))).outcome());
    }

    private static NoulGate.Verdict judge(Answer answer) {
        return NoulGate.DEFAULTS.judge(answer, "label");
    }

    private static Answer.Noul measured(boolean value, double p) {
        var distribution = new DecisionDistribution("answer", Map.of("true", p, "false", 1.0 - p), 1.0);
        return new Answer.Noul("q", value, OptionalDouble.of(p), AiConfidence.fromDecision(distribution, List.of()));
    }

    private static Answer.Noul reported(boolean value, double confidence) {
        return new Answer.Noul("q", value, OptionalDouble.empty(), AiConfidence.reported(confidence));
    }
}
