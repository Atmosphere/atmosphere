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

import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentResult;

import java.util.Map;
import java.util.Set;

/**
 * Built-in {@link ResultEvaluator} that scores agent results on structural
 * quality metrics — no LLM required, no API keys, works in CI.
 *
 * <p>Checks:</p>
 * <ul>
 *   <li><b>Length</b>: responses below {@code minWords} fail</li>
 *   <li><b>Error indicators</b>: responses containing error keywords get penalized</li>
 *   <li><b>Substance</b>: empty or whitespace-only responses fail</li>
 * </ul>
 *
 * <p>Register via {@code META-INF/services/org.atmosphere.coordinator.evaluation.ResultEvaluator}
 * or configure programmatically.</p>
 */
public final class SanityCheckEvaluator implements ResultEvaluator {

    private static final Set<String> ERROR_INDICATORS = Set.of(
            "error", "failed", "timeout", "exception", "unavailable",
            "circuit breaker open", "cancelled", "interrupted"
    );

    private final int minWords;
    private final double errorPenalty;

    /** Default: minimum 5 words, 0.3 penalty per error indicator. */
    public SanityCheckEvaluator() {
        this(5, 0.3);
    }

    /**
     * @param minWords     minimum word count for a passing result
     * @param errorPenalty score deduction per error indicator found (0.0-1.0)
     */
    public SanityCheckEvaluator(int minWords, double errorPenalty) {
        this.minWords = minWords;
        this.errorPenalty = errorPenalty;
    }

    @Override
    public String name() {
        return "sanity-check";
    }

    @Override
    public Evaluation evaluate(AgentResult result, AgentCall originalCall) {
        if (!result.success()) {
            return Evaluation.fail(0.0, "Agent call failed: " + result.text(),
                    Map.of("reason", "call_failed"));
        }

        var text = result.text();
        if (text == null || text.isBlank()) {
            return Evaluation.fail(0.0, "Empty response",
                    Map.of("reason", "empty_response"));
        }

        var words = text.trim().split("\\s+");
        var wordCount = words.length;

        if (wordCount < minWords) {
            return Evaluation.fail(wordCount / (double) minWords,
                    "Too short: " + wordCount + " words (minimum " + minWords + ")",
                    Map.of("reason", "too_short", "wordCount", wordCount));
        }

        // Check for error indicators
        var lowerText = text.toLowerCase();
        var errorMatches = ERROR_INDICATORS.stream()
                .filter(lowerText::contains)
                .toList();

        var score = 1.0;
        var reasons = new java.util.ArrayList<String>();
        reasons.add(wordCount + " words");

        if (!errorMatches.isEmpty()) {
            score -= errorPenalty * errorMatches.size();
            reasons.add("error indicators: " + errorMatches);
        }

        // Bonus for structured responses (has punctuation = complete sentences)
        if (text.contains(".") || text.contains("!") || text.contains("?")) {
            score = Math.min(1.0, score + 0.05);
            reasons.add("structured");
        }

        score = Math.max(0.0, Math.min(1.0, score));
        var passed = score >= 0.5;

        return passed
                ? Evaluation.pass(score, String.join(", ", reasons),
                        Map.of("wordCount", wordCount, "errorIndicators", errorMatches))
                : Evaluation.fail(score, String.join(", ", reasons),
                        Map.of("wordCount", wordCount, "errorIndicators", errorMatches));
    }
}
