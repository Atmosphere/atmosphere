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
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;

import java.util.Objects;

/**
 * Built-in {@link CostAccountant} that feeds
 * {@link CostCeilingGuardrail#addCost(String, double)} automatically.
 * Converts {@link TokenUsage} to dollars via a {@link TokenPricing}
 * and pushes the result through the ceiling bucket keyed by the turn's
 * {@code business.tenant.id} MDC tag.
 *
 * <p>This is the production consumer that moves
 * {@code CostCeilingGuardrail.addCost} from "API + reference impl,
 * integration pending" to a working observability → enforcement loop.
 * Host starters (Spring Boot, Quarkus) install one when both
 * {@link CostCeilingGuardrail} and {@link TokenPricing} beans are
 * present; operators with custom attribution wire their own
 * {@link CostAccountant} directly.</p>
 */
public final class CostCeilingAccountant implements CostAccountant {

    private final CostCeilingGuardrail guardrail;
    private final TokenPricing pricing;

    public CostCeilingAccountant(CostCeilingGuardrail guardrail, TokenPricing pricing) {
        this.guardrail = Objects.requireNonNull(guardrail, "guardrail");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
    }

    @Override
    public void record(String tenantId, TokenUsage usage, String model) {
        if (usage == null || !usage.hasCounts()) {
            return;
        }
        double cost = pricing.costUsd(usage, model);
        if (cost > 0) {
            guardrail.addCost(tenantId, cost);
        }
    }
}
