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
import java.util.OptionalDouble;

/**
 * Per-response confidence signal — the data primitive behind Bonér's
 * "dynamic routing" pattern (high-confidence turns auto-execute,
 * low-confidence turns escalate to human review).
 *
 * <p>Three sources, each documenting how the value was derived so callers
 * can weight it appropriately:</p>
 * <ul>
 *   <li>{@link Source#LOGPROBS_NATIVE} — provider returned token-level
 *       log probabilities (e.g. OpenAI {@code logprobs: true}); the
 *       {@code aggregate} is the arithmetic mean of {@code exp(logprob)}
 *       over the response tokens, range {@code [0, 1]}. The richest
 *       signal — token-level breakdown lives in {@link #tokens()}.</li>
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
 *                  {@link Source#LOGPROBS_NATIVE}
 * @param source    how the aggregate was derived
 */
public record AiConfidence(
        OptionalDouble aggregate,
        List<TokenLogprob> tokens,
        Source source
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
        /** Native token-level log probabilities from the provider. */
        LOGPROBS_NATIVE,
        /** Model-emitted confidence field elicited via system prompt. */
        MODEL_REPORTED_FIELD,
        /** Runtime-computed heuristic. */
        HEURISTIC
    }

    public AiConfidence {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(source, "source");
        tokens = tokens != null ? List.copyOf(tokens) : List.of();
        if (aggregate.isPresent()) {
            var v = aggregate.getAsDouble();
            if (v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException(
                        "aggregate must be in [0, 1], got " + v);
            }
        }
    }

    /** Build a model-reported confidence (the universal-fallback path). */
    public static AiConfidence reported(double aggregate) {
        return new AiConfidence(
                OptionalDouble.of(aggregate), List.of(), Source.MODEL_REPORTED_FIELD);
    }

    /** Build a confidence from native logprobs. The aggregate is the
     * arithmetic mean of {@code exp(logprob)} over the supplied tokens
     * — empty when {@code tokens} is empty. */
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
