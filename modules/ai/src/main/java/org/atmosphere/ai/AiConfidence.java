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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Per-response confidence signal — the data primitive behind Bonér's
 * "dynamic routing" pattern (high-confidence turns auto-execute,
 * low-confidence turns escalate to human review). {@link ConfidenceRouting}
 * is what acts on it: install one and each completed turn is routed to
 * {@link ConfidenceRoute#ACT}, {@link ConfidenceRoute#CONFIRM} or
 * {@link ConfidenceRoute#ESCALATE}. Emitting the signal alone routes nothing.
 *
 * <p>Four sources, each documenting how the value was derived so callers
 * can weight it appropriately:</p>
 * <ul>
 *   <li>{@link Source#DECISION_LOGPROBS} — the provider returned token
 *       logprobs <em>with</em> {@code top_logprobs} alternatives, and the
 *       runtime located the tokens carrying the value of the decision field
 *       designated by {@link AiConfidenceElicitation#decisionField()} (a
 *       top-level enum or boolean property of a structured response). The
 *       {@code aggregate} is the margin of the value the model emitted,
 *       {@link DecisionDistribution#marginOf(String)}: {@code 0} unless that
 *       value is strictly the most likely one, otherwise
 *       {@code (k * p(value) - 1) / (k - 1)}; the distribution itself is in
 *       {@link #decision()}. This scores how sure the model was of the
 *       <em>answer it gave</em>: a coin flip between two values scores
 *       {@code 0} however fluent the surrounding text, and so does a value
 *       sampled against a more likely rival. {@link #tokens()} holds the
 *       sampled tokens of the decision value up to the one that determined
 *       it. Emitted today by the Built-in runtime on its chat-completions
 *       path only.</li>
 *   <li>{@link Source#LOGPROBS_NATIVE} — provider returned token-level
 *       log probabilities (e.g. OpenAI {@code logprobs: true}); the
 *       {@code aggregate} is the arithmetic mean of {@code exp(logprob)}
 *       over every response token, range {@code [0, 1]}. It measures how
 *       fluent the text was, not how sure the model was of a decision the
 *       text carries: a long answer whose one decisive token was a coin flip
 *       still scores high. Token-level breakdown lives in {@link #tokens()}.</li>
 *   <li>{@link Source#MODEL_REPORTED_FIELD} — provider was prompted to
 *       emit a {@code "confidence": 0.x} field in its response and the
 *       framework parsed it. Universally available because the elicitation
 *       happens at the pipeline layer; quality depends on the model's
 *       calibration. {@link #tokens()} is empty.</li>
 *   <li>{@link Source#HEURISTIC} — runtime computed a confidence value
 *       from in-band signals (response length, refusal pattern, etc.).
 *       Lower-quality fallback. {@link #tokens()} is empty.</li>
 * </ul>
 *
 * <p>{@link #aggregate()} returns {@link OptionalDouble#empty()} when the
 * value could not be determined — for example, the model did not emit a
 * confidence field, or no tokens were present in a logprobs response.
 * Consumers should treat empty as "unknown", not "low".</p>
 *
 * @param aggregate scalar confidence in {@code [0, 1]}, or empty when unknown
 * @param tokens    per-token log probabilities; empty unless source is
 *                  {@link Source#LOGPROBS_NATIVE} (every response token) or
 *                  {@link Source#DECISION_LOGPROBS} (the decision value's
 *                  sampled tokens, up to the one that determined the value)
 * @param source    how the aggregate was derived
 * @param decision  the distribution over the decision field's allowed values;
 *                  present exactly when source is {@link Source#DECISION_LOGPROBS}
 */
public record AiConfidence(
        OptionalDouble aggregate,
        List<TokenLogprob> tokens,
        Source source,
        Optional<DecisionDistribution> decision
) {

    /** Metadata key used by {@link StreamingSession#confidence(AiConfidence)}'s
     * default sink to emit the aggregate as a wire signal. Mirrors the
     * naming of {@code ai.tokens.*}. */
    public static final String AGGREGATE_METADATA_KEY = "ai.confidence.aggregate";

    /** Metadata key for the {@link Source} of the confidence value. */
    public static final String SOURCE_METADATA_KEY = "ai.confidence.source";

    /** Metadata key for the count of token-level entries (informational). */
    public static final String TOKENS_METADATA_KEY = "ai.confidence.tokens";

    /** How a confidence value was derived. */
    public enum Source {
        /** Mean native token probability over the whole response (fluency). */
        LOGPROBS_NATIVE,
        /** Margin of the emitted value in the native distribution over a decision field's allowed values. */
        DECISION_LOGPROBS,
        /** Model-emitted confidence field elicited via system prompt. */
        MODEL_REPORTED_FIELD,
        /** Runtime-computed heuristic. */
        HEURISTIC
    }

    public AiConfidence {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(source, "source");
        tokens = tokens != null ? List.copyOf(tokens) : List.of();
        decision = decision != null ? decision : Optional.empty();
        if (decision.isPresent() && source != Source.DECISION_LOGPROBS) {
            throw new IllegalArgumentException(
                    "a decision distribution is carried only by DECISION_LOGPROBS, got " + source);
        }
        if (aggregate.isPresent()) {
            var v = aggregate.getAsDouble();
            if (v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException(
                        "aggregate must be in [0, 1], got " + v);
            }
        }
    }

    /**
     * Three-component form (no decision distribution), kept so every caller
     * written before {@link Source#DECISION_LOGPROBS} existed compiles
     * unchanged.
     */
    public AiConfidence(OptionalDouble aggregate, List<TokenLogprob> tokens, Source source) {
        this(aggregate, tokens, source, Optional.empty());
    }

    /** Build a model-reported confidence (the universal-fallback path). */
    public static AiConfidence reported(double aggregate) {
        return new AiConfidence(
                OptionalDouble.of(aggregate), List.of(), Source.MODEL_REPORTED_FIELD);
    }

    /** Build a confidence from native logprobs. The aggregate is the
     * arithmetic mean of {@code exp(logprob)} over the supplied tokens
     * — empty when {@code tokens} is empty. It scores the fluency of the
     * whole text; {@link #fromDecision} scores a decision. */
    public static AiConfidence fromLogprobs(List<TokenLogprob> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return new AiConfidence(OptionalDouble.empty(), List.of(), Source.LOGPROBS_NATIVE);
        }
        var sum = 0.0;
        for (var tok : tokens) {
            sum += Math.exp(tok.logprob());
        }
        return new AiConfidence(
                OptionalDouble.of(sum / tokens.size()),
                tokens,
                Source.LOGPROBS_NATIVE);
    }

    /**
     * Build a decision confidence: the aggregate is the distribution's
     * {@link DecisionDistribution#normalizedMargin() normalised margin} — the
     * concentration around its most likely value — and the distribution rides
     * along in {@link #decision()}. When the answer the model gave is known,
     * use {@link #fromDecision(DecisionDistribution, String, List)}, which
     * scores that answer rather than the most likely one.
     *
     * @param distribution the model's distribution over the decision field's
     *                     allowed values
     * @param valueTokens  the sampled tokens that carry the decision value
     */
    public static AiConfidence fromDecision(DecisionDistribution distribution,
                                            List<TokenLogprob> valueTokens) {
        Objects.requireNonNull(distribution, "distribution");
        return new AiConfidence(
                OptionalDouble.of(distribution.normalizedMargin()),
                valueTokens,
                Source.DECISION_LOGPROBS,
                Optional.of(distribution));
    }

    /**
     * Build a decision confidence for the answer the model gave: the
     * aggregate is {@link DecisionDistribution#marginOf(String)
     * marginOf(answer)} — {@code 0} unless {@code answer} is strictly the
     * most likely value — and the distribution rides along in
     * {@link #decision()}.
     *
     * @param distribution the model's distribution over the decision field's
     *                     allowed values
     * @param answer       the value the model emitted, one of the
     *                     distribution's values
     * @param valueTokens  the sampled tokens that carry the decision value
     */
    public static AiConfidence fromDecision(DecisionDistribution distribution, String answer,
                                            List<TokenLogprob> valueTokens) {
        Objects.requireNonNull(distribution, "distribution");
        Objects.requireNonNull(answer, "answer");
        return new AiConfidence(
                OptionalDouble.of(distribution.marginOf(answer)),
                valueTokens,
                Source.DECISION_LOGPROBS,
                Optional.of(distribution));
    }

    /** Build a heuristic confidence — caller computed the value some
     * other way and is reporting it for routing purposes. */
    public static AiConfidence heuristic(double aggregate) {
        return new AiConfidence(
                OptionalDouble.of(aggregate), List.of(), Source.HEURISTIC);
    }

    /** Empty / unknown signal — emit when elicitation was attempted but
     * the model did not comply. Lets consumers distinguish "unknown"
     * from "never asked." */
    public static AiConfidence unknown(Source source) {
        return new AiConfidence(OptionalDouble.empty(), List.of(), source);
    }
}
