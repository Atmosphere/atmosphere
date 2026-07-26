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
package org.atmosphere.ai.cost;

import org.atmosphere.ai.TokenUsage;

/**
 * Converts a {@link TokenUsage} sample into a dollar cost. The framework
 * is billing-agnostic; applications plug in a {@code TokenPricing} that
 * matches their provider's rate sheet so the
 * {@link org.atmosphere.ai.guardrails.CostCeilingGuardrail}
 * observability → enforcement loop has actual numbers to work with.
 *
 * <p>Pricing is intentionally single-method so downstream callers can
 * encode any model — per-token input/output rates, provider-specific cached
 * token discounts, reserved-capacity flat fees — without the framework
 * shipping a one-size-fits-all table that drifts monthly.</p>
 */
@FunctionalInterface
public interface TokenPricing {

    /**
     * @return cost in whatever dollar unit the application feeds to
     *         {@link org.atmosphere.ai.guardrails.CostCeilingGuardrail#addCost(String, double)}.
     *         Return {@code 0} when the usage record carries no counts or the
     *         model is not priced.
     */
    double costUsd(TokenUsage usage, String model);

    /**
     * A simple two-rate pricing: {@code inputUsdPerMillion} multiplied by
     * input tokens + {@code outputUsdPerMillion} multiplied by output
     * tokens, divided by one million. Covers the common OpenAI-shaped
     * pricing sheet without modelling cached-input discounts or
     * provider-specific extras.
     *
     * <p>For a prompt-cache read discount use
     * {@link #flatWithCachedDiscount(double, double, double)}; apps with more
     * complex pricing (reserved capacity, vision rates) implement
     * {@link TokenPricing} directly rather than constructing this.</p>
     */
    static TokenPricing flat(double inputUsdPerMillion, double outputUsdPerMillion) {
        if (inputUsdPerMillion < 0 || outputUsdPerMillion < 0) {
            throw new IllegalArgumentException(
                    "prices must be non-negative: in=" + inputUsdPerMillion
                    + " out=" + outputUsdPerMillion);
        }
        return (usage, model) -> {
            if (usage == null || !usage.hasCounts()) {
                return 0.0;
            }
            return (usage.input() * inputUsdPerMillion
                    + usage.output() * outputUsdPerMillion) / 1_000_000.0;
        };
    }

    /**
     * Three-rate pricing that discounts prompt-cache hits: cached input
     * tokens are billed at {@code cachedInputUsdPerMillion} and the
     * remaining (uncached) input tokens at {@code inputUsdPerMillion}.
     * Follows the OpenAI-shaped usage report, where the cached count is a
     * <em>subset</em> of {@link TokenUsage#input()} — the cached count is
     * clamped to the input count, so a provider that reports cache reads
     * disjoint from input (Anthropic's {@code cache_read_input_tokens},
     * for example) never produces a negative uncached remainder. For
     * disjoint-count billing, implement {@link TokenPricing} directly.
     *
     * <p>{@code flat(in, out)} is unchanged and equivalent to
     * {@code flatWithCachedDiscount(in, in, out)}.</p>
     *
     * @param inputUsdPerMillion       USD per million uncached input tokens
     * @param cachedInputUsdPerMillion USD per million cached input tokens
     *                                 (the provider's prompt-cache read rate)
     * @param outputUsdPerMillion      USD per million output tokens
     */
    static TokenPricing flatWithCachedDiscount(double inputUsdPerMillion,
                                               double cachedInputUsdPerMillion,
                                               double outputUsdPerMillion) {
        if (inputUsdPerMillion < 0 || cachedInputUsdPerMillion < 0 || outputUsdPerMillion < 0) {
            throw new IllegalArgumentException(
                    "prices must be non-negative: in=" + inputUsdPerMillion
                    + " cached=" + cachedInputUsdPerMillion
                    + " out=" + outputUsdPerMillion);
        }
        return (usage, model) -> {
            if (usage == null || !usage.hasCounts()) {
                return 0.0;
            }
            long cached = Math.min(Math.max(usage.cachedInput(), 0L), usage.input());
            long uncached = usage.input() - cached;
            return (uncached * inputUsdPerMillion
                    + cached * cachedInputUsdPerMillion
                    + usage.output() * outputUsdPerMillion) / 1_000_000.0;
        };
    }

    /** Pricing that always reports zero — useful for tests and observability-only deployments. */
    TokenPricing ZERO = (usage, model) -> 0.0;
}
