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

import java.util.Map;
import java.util.Set;

/**
 * Pluggable content classifier behind {@link ModerationGuardrail}. A detector
 * inspects a piece of text and reports which {@link ModerationCategory category}
 * (if any) it falls into.
 *
 * <p>Two implementations ship in-tree:</p>
 * <ul>
 *   <li>{@link RuleBasedModerationDetector} — zero-dependency, deterministic
 *       phrase matching. The default tier; cheap enough to run on every
 *       streamed chunk.</li>
 *   <li>{@link LlmModerationDetector} — delegates a single zero-shot
 *       classification call to the installed {@code AgentRuntime}, so every
 *       runtime adapter participates identically. The accurate tier; one model
 *       round-trip per inspection.</li>
 * </ul>
 *
 * <p>Provider-native moderation endpoints (OpenAI {@code /moderations}, Azure
 * Content Safety) plug in by implementing this same interface — the guardrail
 * pipeline above is detector-agnostic.</p>
 *
 * <p>Implementations MUST be thread-safe: a single detector instance is shared
 * across all concurrent requests when wired as a framework guardrail.</p>
 */
@FunctionalInterface
public interface ModerationDetector {

    /**
     * Classify {@code text} against the moderation taxonomy.
     *
     * @param text content to inspect; implementations treat {@code null}/blank
     *             as {@link ModerationResult#clean()}
     * @return the categories the text was flagged for (possibly empty)
     */
    ModerationResult detect(String text);

    /**
     * Outcome of a {@link #detect(String)} call.
     *
     * @param flagged the categories the text matched (never {@code null})
     * @param scores  per-category confidence in {@code [0.0, 1.0]}; may be empty
     *                even when {@code flagged} is non-empty (rule-based tiers
     *                report no graded score)
     * @param errored {@code true} when the detector could not complete (timeout,
     *                runtime error) — the guardrail's fail-closed policy decides
     *                what to do with an errored result
     * @param detail  human-readable explanation, used in audit logs and block
     *                reasons; never {@code null}
     */
    record ModerationResult(Set<ModerationCategory> flagged,
                            Map<ModerationCategory, Double> scores,
                            boolean errored,
                            String detail) {

        public ModerationResult {
            flagged = flagged == null ? Set.of() : Set.copyOf(flagged);
            scores = scores == null ? Map.of() : Map.copyOf(scores);
            detail = detail == null ? "" : detail;
        }

        /** @return {@code true} when at least one category matched. */
        public boolean isFlagged() {
            return !flagged.isEmpty();
        }

        /** Nothing matched and the detector ran successfully. */
        public static ModerationResult clean() {
            return new ModerationResult(Set.of(), Map.of(), false, "");
        }

        /** Categories matched. */
        public static ModerationResult flagged(Set<ModerationCategory> categories,
                                               Map<ModerationCategory, Double> scores,
                                               String detail) {
            return new ModerationResult(categories, scores, false, detail);
        }

        /** The detector could not complete; the guardrail's fail policy applies. */
        public static ModerationResult error(String detail) {
            return new ModerationResult(Set.of(), Map.of(), true, detail);
        }
    }
}
