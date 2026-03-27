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
package org.atmosphere.ai.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-user/organization streaming text budget tracking. Recommends a fallback model
 * when usage approaches the configured threshold, and rejects usage when the budget
 * is exhausted. Thread-safe.
 *
 * @see org.atmosphere.ai.filter.CostMeteringFilter
 */
public final class StreamingTextBudgetManager {

    private static final Logger logger = LoggerFactory.getLogger(StreamingTextBudgetManager.class);

    /**
     * Budget configuration for an owner (user or organization).
     *
     * @param ownerId                the owner identifier
     * @param maxStreamingTexts              the maximum number of streaming texts allowed
     * @param fallbackModel          a cheaper model to switch to when approaching the limit (may be null)
     * @param degradationThreshold   fraction of budget used (0.0-1.0) at which to switch to fallback
     */
    public record Budget(String ownerId, long maxStreamingTexts, String fallbackModel, double degradationThreshold) {

        public Budget {
            if (maxStreamingTexts <= 0) {
                throw new IllegalArgumentException("maxStreamingTexts must be positive");
            }
            if (degradationThreshold < 0 || degradationThreshold > 1.0) {
                throw new IllegalArgumentException("degradationThreshold must be between 0.0 and 1.0");
            }
        }
    }

    private final ConcurrentHashMap<String, Budget> budgets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> usage = new ConcurrentHashMap<>();

    /**
     * Set a budget for an owner.
     *
     * @param budget the budget configuration
     */
    public void setBudget(Budget budget) {
        budgets.put(budget.ownerId(), budget);
    }

    /**
     * Remove a budget. The usage counter is also cleared.
     *
     * @param ownerId the owner identifier
     */
    public void removeBudget(String ownerId) {
        budgets.remove(ownerId);
        usage.remove(ownerId);
    }

    /**
     * Record streaming text usage for an owner.
     *
     * @param ownerId        the owner identifier
     * @param streamingTexts the number of streaming texts to record
     * @return {@code true} if the usage is within budget, {@code false} if budget exceeded
     */
    public boolean recordUsage(String ownerId, long streamingTexts) {
        var counter = usage.computeIfAbsent(ownerId, k -> new AtomicLong());
        var newTotal = counter.addAndGet(streamingTexts);

        var budget = budgets.get(ownerId);
        if (budget != null && newTotal > budget.maxStreamingTexts()) {
            logger.warn("Streaming text budget exceeded for {}: {} > {}", ownerId, newTotal, budget.maxStreamingTexts());
            return false;
        }
        return true;
    }

    /**
     * Get the remaining streaming text count for an owner.
     *
     * @param ownerId the owner identifier
     * @return remaining streaming texts, or {@code Long.MAX_VALUE} if no budget is set
     */
    public long remaining(String ownerId) {
        var budget = budgets.get(ownerId);
        if (budget == null) {
            return Long.MAX_VALUE;
        }
        var used = currentUsage(ownerId);
        return Math.max(0, budget.maxStreamingTexts() - used);
    }

    /**
     * Get the current usage for an owner.
     *
     * @param ownerId the owner identifier
     * @return the number of streaming texts used
     */
    public long currentUsage(String ownerId) {
        var counter = usage.get(ownerId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Get the recommended model for an owner based on their budget status.
     *
     * <ul>
     *   <li>If usage is below the degradation threshold: returns {@code Optional.empty()}
     *       (use the default/preferred model)</li>
     *   <li>If usage exceeds the threshold but budget remains: returns the fallback model</li>
     *   <li>If budget is exhausted: throws {@link BudgetExceededException}</li>
     * </ul>
     *
     * @param ownerId the owner identifier
     * @return the recommended fallback model, or empty if default model should be used
     * @throws BudgetExceededException if the budget is fully exhausted
     */
    public Optional<String> recommendedModel(String ownerId) {
        var budget = budgets.get(ownerId);
        if (budget == null) {
            return Optional.empty();
        }

        var used = currentUsage(ownerId);
        if (used >= budget.maxStreamingTexts()) {
            throw new BudgetExceededException(ownerId, budget.maxStreamingTexts(), used);
        }

        var usageRatio = (double) used / budget.maxStreamingTexts();
        if (usageRatio >= budget.degradationThreshold() && budget.fallbackModel() != null) {
            logger.debug("Recommending fallback model {} for {} (usage: {}%)",
                    budget.fallbackModel(), ownerId, (int) (usageRatio * 100));
            return Optional.of(budget.fallbackModel());
        }

        return Optional.empty();
    }

    /**
     * Reset usage for an owner (e.g., at the start of a new billing period).
     *
     * @param ownerId the owner identifier
     */
    public void resetUsage(String ownerId) {
        usage.remove(ownerId);
    }
}
