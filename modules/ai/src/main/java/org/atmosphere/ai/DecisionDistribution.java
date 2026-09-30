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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The model's probability distribution over the allowed values of one
 * decision field (an enum or boolean property of a structured response),
 * reconstructed from the provider's {@code top_logprobs} at the tokens that
 * carry the field's value. Attached to an {@link AiConfidence} with source
 * {@link AiConfidence.Source#DECISION_LOGPROBS}.
 *
 * <p>{@link #probabilities()} sums to {@code 1} over the model's whole
 * probability at the decision. Mass the top alternatives attribute to exactly
 * one value is that value's. Mass that could belong to several values — an
 * alternative whose continuation was never observed, or probability outside
 * the listed alternatives — is assigned so that the largest value's
 * probability is as small as possible, so {@link #normalizedMargin()} is the
 * lowest concentration consistent with the provider's output.
 * {@link #observedMass()} is the share carried by listed alternatives that
 * match at least one allowed value; a low observed mass means most of the
 * distribution is that worst-case assignment rather than evidence.</p>
 *
 * @param field         the decision field's JSON property name
 * @param probabilities allowed value → probability, in the schema's value
 *                      order
 * @param observedMass  share of the probability carried by listed
 *                      alternatives that match an allowed value, in
 *                      {@code (0, 1]}
 */
public record DecisionDistribution(String field, Map<String, Double> probabilities,
                                   double observedMass) {

    /** Tolerance on the probabilities summing to one. */
    private static final double SUM_TOLERANCE = 1e-6;

    public DecisionDistribution {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(probabilities, "probabilities");
        if (probabilities.isEmpty()) {
            throw new IllegalArgumentException("probabilities must not be empty");
        }
        var sum = 0.0;
        for (var entry : probabilities.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "value");
            var p = entry.getValue();
            if (p == null || !(p >= 0.0 && p <= 1.0)) {
                throw new IllegalArgumentException(
                        "probability of '" + entry.getKey() + "' must be in [0, 1], got " + p);
            }
            sum += p;
        }
        if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("probabilities must sum to 1, got " + sum);
        }
        if (!(observedMass > 0.0 && observedMass <= 1.0)) {
            throw new IllegalArgumentException("observedMass must be in (0, 1], got " + observedMass);
        }
        probabilities = Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
    }

    /** The allowed value with the highest probability (first in schema order on a tie). */
    public String mostLikely() {
        String best = null;
        var bestP = -1.0;
        for (var entry : probabilities.entrySet()) {
            if (entry.getValue() > bestP) {
                best = entry.getKey();
                bestP = entry.getValue();
            }
        }
        return best;
    }

    /**
     * Concentration of the distribution: {@code (k * pMax - 1) / (k - 1)} for
     * {@code k} allowed values. {@code 1.0} when all mass is on one value,
     * {@code 0.0} when it is spread evenly (a coin flip between two values
     * scores {@code 0}, not {@code 0.5}). A single allowed value is a forced
     * choice and scores {@code 1.0}.
     */
    public double normalizedMargin() {
        var k = probabilities.size();
        if (k == 1) {
            return 1.0;
        }
        var pMax = 0.0;
        for (var p : probabilities.values()) {
            pMax = Math.max(pMax, p);
        }
        var margin = (k * pMax - 1.0) / (k - 1.0);
        return Math.min(1.0, Math.max(0.0, margin));
    }
}
