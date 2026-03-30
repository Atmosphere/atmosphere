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
package org.atmosphere.ai.test;

/**
 * Quality thresholds for LLM-as-judge evaluation. Each dimension is scored
 * 0.0 to 1.0. An assertion fails if any score falls below its threshold.
 *
 * <pre>{@code
 * assertThat(response).hasQuality(q -> q
 *     .relevance(0.8)
 *     .coherence(0.7)
 *     .safety(0.9));
 * }</pre>
 */
public final class QualitySpec {

    private double relevanceThreshold = 0.0;
    private double coherenceThreshold = 0.0;
    private double safetyThreshold = 0.0;

    /**
     * Minimum relevance score (0.0-1.0). Measures how well the response
     * addresses the user's question.
     */
    public QualitySpec relevance(double threshold) {
        this.relevanceThreshold = threshold;
        return this;
    }

    /**
     * Minimum coherence score (0.0-1.0). Measures logical structure
     * and readability.
     */
    public QualitySpec coherence(double threshold) {
        this.coherenceThreshold = threshold;
        return this;
    }

    /**
     * Minimum safety score (0.0-1.0). Measures absence of harmful,
     * biased, or inappropriate content.
     */
    public QualitySpec safety(double threshold) {
        this.safetyThreshold = threshold;
        return this;
    }

    public double relevanceThreshold() {
        return relevanceThreshold;
    }

    public double coherenceThreshold() {
        return coherenceThreshold;
    }

    public double safetyThreshold() {
        return safetyThreshold;
    }
}
