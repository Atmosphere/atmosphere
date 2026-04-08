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
package org.atmosphere.coordinator.evaluation;

import java.util.Map;

/**
 * Result of evaluating an agent result via a {@link ResultEvaluator}.
 *
 * @param score    quality score between 0.0 (worst) and 1.0 (best)
 * @param passed   whether the result meets the evaluator's threshold
 * @param reason   human-readable explanation of the evaluation
 * @param metadata additional evaluator-specific data
 */
public record Evaluation(
        double score,
        boolean passed,
        String reason,
        Map<String, Object> metadata
) {

    public Evaluation {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException(
                    "Score must be between 0.0 and 1.0, got: " + score);
        }
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /** Create a passing evaluation with the given score and reason. */
    public static Evaluation pass(double score, String reason) {
        return new Evaluation(score, true, reason, Map.of());
    }

    /** Create a passing evaluation with the given score, reason, and metadata. */
    public static Evaluation pass(double score, String reason, Map<String, Object> metadata) {
        return new Evaluation(score, true, reason, metadata);
    }

    /** Create a failing evaluation with the given score and reason. */
    public static Evaluation fail(double score, String reason) {
        return new Evaluation(score, false, reason, Map.of());
    }

    /** Create a failing evaluation with the given score, reason, and metadata. */
    public static Evaluation fail(double score, String reason, Map<String, Object> metadata) {
        return new Evaluation(score, false, reason, metadata);
    }
}
