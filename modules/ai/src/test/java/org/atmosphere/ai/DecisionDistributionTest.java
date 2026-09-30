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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DecisionDistribution}, the {@link AiConfidence.Source#DECISION_LOGPROBS}
 * factory, and the decision-field designation on
 * {@link AiConfidenceElicitation}.
 */
class DecisionDistributionTest {

    private static final double EPS = 1e-12;

    @Test
    void normalizedMarginIsZeroForAnEvenSplitAndOneForCertainty() {
        // A two-way coin flip scores 0, not the 0.5 its pMax suggests.
        assertEquals(0.0, dist(0.5, 0.5).normalizedMargin(), EPS);
        assertEquals(1.0, dist(1.0, 0.0).normalizedMargin(), EPS);
        assertEquals(0.0, dist(1.0 / 3, 1.0 / 3, 1.0 / 3).normalizedMargin(), EPS);
        // (3 * 0.6 - 1) / 2
        assertEquals(0.4, dist(0.6, 0.3, 0.1).normalizedMargin(), EPS);
        // 2 * 0.75 - 1
        assertEquals(0.5, dist(0.75, 0.25).normalizedMargin(), EPS);
        assertEquals(1.0, dist(1.0).normalizedMargin(), EPS, "a single value is a forced choice");
    }

    @Test
    void mostLikelyPrefersSchemaOrderOnATie() {
        assertEquals("V0", dist(0.5, 0.5).mostLikely());
        assertEquals("V1", dist(0.2, 0.7, 0.1).mostLikely());
    }

    @Test
    void validationRejectsMalformedDistributions() {
        assertThrows(IllegalArgumentException.class, () -> dist(0.5, 0.4), "must sum to 1");
        assertThrows(IllegalArgumentException.class, () -> dist(1.2, -0.2), "each in [0, 1]");
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionDistribution("f", Map.of(), 1.0), "non-empty");
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionDistribution("f", Map.of("A", 1.0), 0.0), "observed mass > 0");
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionDistribution("f", Map.of("A", 1.0), 1.5), "observed mass <= 1");
    }

    @Test
    void probabilitiesAreAnImmutableCopy() {
        var source = new LinkedHashMap<String, Double>();
        source.put("A", 1.0);
        var d = new DecisionDistribution("f", source, 1.0);
        source.put("B", 0.0);
        assertEquals(1, d.probabilities().size());
        assertThrows(UnsupportedOperationException.class, () -> d.probabilities().put("C", 0.0));
    }

    @Test
    void fromDecisionCarriesTheDistributionAndTheMargin() {
        var d = dist(0.8, 0.2);
        var confidence = AiConfidence.fromDecision(d, List.of(new TokenLogprob("A", -0.2)));
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, confidence.source());
        assertEquals(0.6, confidence.aggregate().getAsDouble(), EPS);
        assertEquals(d, confidence.decision().orElseThrow());
        assertEquals(1, confidence.tokens().size());
    }

    @Test
    void onlyDecisionLogprobsMayCarryADistribution() {
        assertThrows(IllegalArgumentException.class, () -> new AiConfidence(
                OptionalDouble.of(0.5), List.of(), AiConfidence.Source.LOGPROBS_NATIVE,
                Optional.of(dist(0.75, 0.25))));
        // The three-component form and the existing factories carry none.
        assertTrue(new AiConfidence(OptionalDouble.of(0.5), List.of(),
                AiConfidence.Source.HEURISTIC).decision().isEmpty());
        assertTrue(AiConfidence.reported(0.7).decision().isEmpty());
        assertTrue(AiConfidence.fromLogprobs(List.of(new TokenLogprob("a", -0.1))).decision().isEmpty());
    }

    @Test
    void elicitationDesignatesADecisionFieldWithoutChangingTheCue() {
        var base = AiConfidenceElicitation.defaults();
        assertNull(base.decisionField(), "no decision field by default");
        var withDecision = base.withDecisionField("verdict");
        assertEquals("verdict", withDecision.decisionField());
        assertEquals(base.fieldName(), withDecision.fieldName());
        assertEquals(base.effectiveCue(), withDecision.effectiveCue());
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfidenceElicitation("confidence", null, " "));
    }

    private static DecisionDistribution dist(double... probabilities) {
        var map = new LinkedHashMap<String, Double>();
        for (int i = 0; i < probabilities.length; i++) {
            map.put("V" + i, probabilities[i]);
        }
        return new DecisionDistribution("field", map, 1.0);
    }
}
