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
 *   <li>{@link LlmModerationDetector} — asks a
 *       {@link org.atmosphere.ai.decision.DecisionModel} one boolean question per
 *       category (over the installed {@code AgentRuntime} by default), so every
 *       runtime adapter participates. The accurate tier; one parallel batch of
 *       model calls per inspection, failing closed on any undecided category the
 *       guardrail blocks.</li>
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
     * Classify {@code text} against only {@code categories} — the ones the
     * caller acts on. {@link ModerationGuardrail} calls this with its blocked
     * categories, so a detector that pays per category (such as
     * {@link LlmModerationDetector}, one model call each) asks nothing the
     * guardrail would ignore. The default classifies against every category.
     *
     * @param text       content to inspect
     * @param categories the categories the caller acts on; {@code null} or
     *                   empty means every category
     * @return the outcome; a detector may report categories outside
     *         {@code categories}, which the caller ignores
     */
    default ModerationResult detect(String text, Set<ModerationCategory> categories) {
        return detect(text);
    }

    /**
     * Outcome of a {@link #detect(String)} call.
     *
     * @param flagged the categories the text matched (never {@code null})
     * @param scores  per-category confidence in {@code [0.0, 1.0]}; may be empty
     *                even when {@code flagged} is non-empty (rule-based tiers
     *                report no graded score)
     * @param errored {@code true} when the detector could not complete (timeout,
     *                runtime error) or could not decide every category — the
     *                guardrail's fail-closed policy decides what to do with an
     *                errored result. An errored result may still list the
     *                categories it did flag; the guardrail blocks on those
     *                whatever its fail policy
     * @param detail  human-readable explanation, used in audit logs and block
     *                reasons; never {@code null}
     * @param undecided the categories the detector could not decide (never
     *                {@code null}); non-empty implies {@code errored}. An
     *                errored result with no undecided category is a failure of
     *                the whole detector. {@link ModerationGuardrail} applies its
     *                fail policy to a whole-detector failure and to an undecided
     *                category it blocks, never to one it does not block
     */
    record ModerationResult(Set<ModerationCategory> flagged,
                            Map<ModerationCategory, Double> scores,
                            boolean errored,
                            String detail,
                            Set<ModerationCategory> undecided) {

        public ModerationResult {
            flagged = flagged == null ? Set.of() : Set.copyOf(flagged);
            scores = scores == null ? Map.of() : Map.copyOf(scores);
            detail = detail == null ? "" : detail;
            undecided = undecided == null ? Set.of() : Set.copyOf(undecided);
            errored = errored || !undecided.isEmpty();
        }

        /** A result with no undecided category. */
        public ModerationResult(Set<ModerationCategory> flagged,
                                Map<ModerationCategory, Double> scores,
                                boolean errored,
                                String detail) {
            this(flagged, scores, errored, detail, Set.of());
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

        /**
         * The detector decided some categories and not {@code undecided}; it
         * may still have flagged some. Errored, with the undecided categories
         * named so the guardrail can tell which of them it blocks.
         */
        public static ModerationResult undecided(Set<ModerationCategory> flagged,
                                                 Map<ModerationCategory, Double> scores,
                                                 Set<ModerationCategory> undecided,
                                                 String detail) {
            return new ModerationResult(flagged, scores, true, detail, undecided);
        }
    }
}
