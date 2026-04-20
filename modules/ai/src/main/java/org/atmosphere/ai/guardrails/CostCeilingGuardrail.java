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

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Blocks a tenant's outbound {@code @Prompt} when cumulative cost
 * exceeds a per-tenant dollar budget. Closes the "observability as
 * control plane" loop: {@code BusinessMetadata} tags every turn, the
 * framework totals token usage, and this guardrail turns the total
 * into an enforcement decision — a dashboard becomes a control plane.
 *
 * <h2>Cost model</h2>
 *
 * The guardrail is billing-agnostic: applications compute per-turn
 * cost (dollars × tokens) in whatever way their provider pricing
 * dictates and report it by calling {@link #addCost(String, double)}
 * after each completed LLM call. This keeps the guardrail zero-dep and
 * leaves provider-specific pricing wiring to the application or to a
 * {@code GatewayTraceExporter} that sits on the outbound call path.
 *
 * <h2>Tenant scoping</h2>
 *
 * Buckets are keyed by the {@code business.tenant.id} SLF4J MDC tag
 * published by {@code AiEndpointHandler.applyBusinessMdc}. Turns
 * without a tenant tag land in a shared {@code "__default__"} bucket
 * so single-tenant apps still get enforcement.
 *
 * <h2>Budget semantics</h2>
 *
 * The guardrail inspects the REQUEST side (before dispatch). When
 * cumulative tenant cost is already at or above
 * {@link #budgetUsd}, {@code Block} is returned and the call never
 * leaves. This is the tightest enforcement window the SPI offers —
 * after the LLM responds, tokens are spent. Application code must
 * observe returned {@code TokenUsage} (or
 * {@code Micrometer ai.tokens.total}) and feed the dollar total back
 * via {@link #addCost(String, double)}.
 *
 * <h2>Reset</h2>
 *
 * Call {@link #resetTenant(String)} or {@link #resetAll()} on a
 * schedule (daily / monthly) to roll the counter forward; the
 * guardrail itself is stateless about the reset cadence.
 */
public final class CostCeilingGuardrail implements AiGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(CostCeilingGuardrail.class);
    private static final String TENANT_MDC_KEY = "business.tenant.id";
    /** Shared bucket for turns without a {@code business.tenant.id} tag. */
    static final String DEFAULT_BUCKET = "__default__";

    private final double budgetUsd;
    private final ConcurrentHashMap<String, DoubleAdder> spentByTenant =
            new ConcurrentHashMap<>();

    /**
     * @param budgetUsd per-tenant ceiling in whatever unit the application
     *                  feeds to {@link #addCost}. {@code 0} disables
     *                  enforcement (useful for dev / observability-only
     *                  deployments).
     */
    public CostCeilingGuardrail(double budgetUsd) {
        if (budgetUsd < 0) {
            throw new IllegalArgumentException("budgetUsd must be >= 0, got " + budgetUsd);
        }
        this.budgetUsd = budgetUsd;
    }

    @Override
    public GuardrailResult inspectRequest(AiRequest request) {
        if (budgetUsd == 0) {
            return GuardrailResult.pass();
        }
        var tenant = resolveTenant();
        var spent = spentByTenant.getOrDefault(tenant, new DoubleAdder()).sum();
        if (spent >= budgetUsd) {
            logger.warn("Cost ceiling hit for tenant={} (spent={} budget={}) — blocking",
                    tenant, String.format("%.4f", spent), budgetUsd);
            return GuardrailResult.block(
                    "cost ceiling reached for tenant " + tenant
                            + " (spent " + String.format("%.4f", spent)
                            + " of budget " + budgetUsd + ")");
        }
        return GuardrailResult.pass();
    }

    /**
     * Record cost spent on this turn for a tenant. Applications call
     * this after observing {@code TokenUsage} from the runtime — the
     * guardrail does not know provider pricing.
     */
    public void addCost(String tenant, double cost) {
        if (cost <= 0) return;
        spentByTenant.computeIfAbsent(
                tenant != null && !tenant.isBlank() ? tenant : DEFAULT_BUCKET,
                k -> new DoubleAdder()).add(cost);
    }

    /** Snapshot the accumulated cost for a tenant. */
    public double spent(String tenant) {
        var adder = spentByTenant.get(
                tenant != null && !tenant.isBlank() ? tenant : DEFAULT_BUCKET);
        return adder == null ? 0.0 : adder.sum();
    }

    /** Reset the counter for one tenant (e.g. on monthly billing boundary). */
    public void resetTenant(String tenant) {
        spentByTenant.remove(
                tenant != null && !tenant.isBlank() ? tenant : DEFAULT_BUCKET);
    }

    /** Reset every tenant. */
    public void resetAll() {
        spentByTenant.clear();
    }

    private static String resolveTenant() {
        var raw = org.slf4j.MDC.get(TENANT_MDC_KEY);
        return raw != null && !raw.isBlank() ? raw : DEFAULT_BUCKET;
    }
}
