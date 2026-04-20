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
 * Observes per-turn token counts after a completion and attributes cost
 * to a tenant. The bridge that closes the observability → enforcement
 * loop for {@link org.atmosphere.ai.guardrails.CostCeilingGuardrail}:
 * every runtime reports {@link TokenUsage} via
 * {@code StreamingSession.usage(...)}, the session decorator chain
 * invokes {@link #record}, and the accountant pushes the dollar total
 * into the guardrail's ceiling check before the next turn dispatches.
 *
 * <p>{@code tenantId} is the {@code business.tenant.id} MDC value at
 * usage-time (or {@code null} when the turn carried no tenant tag). The
 * accountant decides what to do with it — typically feed
 * {@link org.atmosphere.ai.guardrails.CostCeilingGuardrail#addCost}.</p>
 */
@FunctionalInterface
public interface CostAccountant {

    /**
     * Attribute the cost of this usage sample to {@code tenantId}.
     *
     * @param tenantId tenant identifier (may be {@code null} when the
     *                 turn had no {@code business.tenant.id} MDC tag)
     * @param usage    token counts reported by the runtime; never
     *                 {@code null}
     * @param model    model identifier the counts apply to (may be
     *                 {@code null})
     */
    void record(String tenantId, TokenUsage usage, String model);

    /** No-op accountant for deployments that don't track cost. */
    CostAccountant NOOP = (tenantId, usage, model) -> { };
}
