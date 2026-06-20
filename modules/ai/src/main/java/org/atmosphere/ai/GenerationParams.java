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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Provider-neutral generation parameters carried on
 * {@link AiConfig.LlmSettings#generation()} so deployers can set
 * {@code temperature}, {@code maxTokens}, {@code topP}, and {@code stop}
 * once at the framework level instead of inventing per-adapter knobs.
 *
 * <p><strong>Opt-in / null = unset.</strong> Every component is a boxed,
 * nullable type. A {@code null} component means "no override" — the runtime
 * leaves that parameter exactly as it would today. {@link #defaults()} (all
 * {@code null}) therefore keeps every wire request byte-identical to the
 * pre-{@code GenerationParams} behavior.</p>
 *
 * <p><strong>Boundary validation (Correctness Invariant #4).</strong> The
 * canonical constructor sanitizes nonsensical values at the boundary instead
 * of letting them corrupt a downstream request:</p>
 * <ul>
 *   <li>{@code maxTokens} {@code <= 0} is dropped (treated as unset) — a
 *       zero/negative token cap is meaningless and would 400 at the provider.</li>
 *   <li>{@code temperature} and {@code topP} are clamped to {@code [0.0, 2.0]}
 *       and {@code [0.0, 1.0]} respectively (the widest range any supported
 *       provider accepts), so a fat-fingered {@code 50} becomes {@code 2.0}
 *       rather than a provider-side rejection.</li>
 *   <li>{@code stop} is copied to an unmodifiable list with {@code null} /
 *       blank entries removed; an empty result collapses to {@code null}
 *       (unset) so we never emit an empty {@code stop} array.</li>
 * </ul>
 *
 * <p>This type only describes <em>which</em> overrides are requested.
 * Whether a given runtime actually forwards each one to its provider's wire
 * request is a per-runtime concern documented in {@code modules/ai/README.md}
 * (§ Generation parameters) — Runtime Truth (Correctness Invariant #5) means
 * a runtime that cannot honor a param leaves it to the framework-native
 * config rather than silently dropping it.</p>
 *
 * @param temperature sampling temperature override, or {@code null} for unset
 * @param maxTokens   maximum tokens to generate, or {@code null} for unset
 * @param topP        nucleus-sampling cutoff override, or {@code null} for unset
 * @param stop        stop sequences, or {@code null} for unset
 */
public record GenerationParams(Double temperature, Integer maxTokens, Double topP, List<String> stop) {

    private static final Logger logger = LoggerFactory.getLogger(GenerationParams.class);

    private static final GenerationParams DEFAULTS = new GenerationParams(null, null, null, null);

    /** Widest temperature range any supported provider accepts. */
    private static final double TEMPERATURE_MIN = 0.0;
    private static final double TEMPERATURE_MAX = 2.0;
    /** topP is a probability mass, always in [0, 1]. */
    private static final double TOP_P_MIN = 0.0;
    private static final double TOP_P_MAX = 1.0;

    /**
     * Canonical constructor — validates and normalizes at the boundary so a
     * malformed value is corrected (clamp) or dropped (treated as unset)
     * rather than corrupting the downstream request.
     */
    public GenerationParams {
        temperature = sanitizeRange(temperature, TEMPERATURE_MIN, TEMPERATURE_MAX, "temperature");
        topP = sanitizeRange(topP, TOP_P_MIN, TOP_P_MAX, "top-p");
        if (maxTokens != null && maxTokens <= 0) {
            logger.warn("Ignoring non-positive max-tokens override {} (treating as unset)", maxTokens);
            maxTokens = null;
        }
        stop = sanitizeStop(stop);
    }

    /**
     * The unset sentinel: all components {@code null}. Using this (rather than
     * a {@code null} {@code GenerationParams}) lets every realization site call
     * {@link #hasAny()} / the accessors without a null guard, and guarantees
     * byte-identical wire output to the pre-feature behavior.
     *
     * @return a shared all-{@code null} instance
     */
    public static GenerationParams defaults() {
        return DEFAULTS;
    }

    /**
     * @return {@code true} when at least one component is set (a wire override
     *         is requested); {@code false} when this is equivalent to
     *         {@link #defaults()}
     */
    public boolean hasAny() {
        return temperature != null || maxTokens != null || topP != null
                || (stop != null && !stop.isEmpty());
    }

    /**
     * @return {@code true} when no component is set — the inverse of
     *         {@link #hasAny()}
     */
    public boolean isEmpty() {
        return !hasAny();
    }

    private static Double sanitizeRange(Double value, double min, double max, String label) {
        if (value == null) {
            return null;
        }
        if (value.isNaN()) {
            logger.warn("Ignoring NaN {} override (treating as unset)", label);
            return null;
        }
        if (value < min) {
            logger.warn("Clamping {} override {} up to {}", label, value, min);
            return min;
        }
        if (value > max) {
            logger.warn("Clamping {} override {} down to {}", label, value, max);
            return max;
        }
        return value;
    }

    private static List<String> sanitizeStop(List<String> stop) {
        if (stop == null || stop.isEmpty()) {
            return null;
        }
        var cleaned = stop.stream()
                .filter(s -> s != null && !s.isBlank())
                .toList();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
