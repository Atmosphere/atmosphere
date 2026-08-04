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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Accrual rolls over automatically once {@link #window} elapses, so a
 * long-running deployment is never permanently blocked by spend it
 * accumulated weeks ago. {@link #resetTenant(String)} and
 * {@link #resetAll()} remain for explicit billing boundaries.
 *
 * <h2>Bounded tenant tracking</h2>
 *
 * Buckets are keyed by an MDC tag, which is caller-influenced, so the
 * map is capped at {@link #DEFAULT_MAX_TRACKED_TENANTS}. Past the cap,
 * further tenants share the default bucket rather than allocating an
 * entry each — the ceiling still applies to them, just coarsely.
 * An uncapped map here would turn a request header into unbounded heap
 * growth (Correctness Invariant #3, Backpressure).
 */
public final class CostCeilingGuardrail implements AiGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(CostCeilingGuardrail.class);
    private static final String TENANT_MDC_KEY = "business.tenant.id";
    /** Shared bucket for turns without a {@code business.tenant.id} tag. */
    static final String DEFAULT_BUCKET = "__default__";

    /** Cap on distinct tenant buckets before new tenants fold into the default. */
    public static final int DEFAULT_MAX_TRACKED_TENANTS = 10_000;

    /** Accrual window after which a tenant's spend rolls over. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final double budgetUsd;
    private final int maxTrackedTenants;
    private final Duration window;
    private final ConcurrentHashMap<String, DoubleAdder> spentByTenant =
            new ConcurrentHashMap<>();
    private final AtomicBoolean capWarned = new AtomicBoolean();
    private volatile Instant windowStart = Instant.now();

    /**
     * @param budgetUsd per-tenant ceiling in whatever unit the application
     *                  feeds to {@link #addCost}. {@code 0} disables
     *                  enforcement (useful for dev / observability-only
     *                  deployments).
     */
    public CostCeilingGuardrail(double budgetUsd) {
        this(budgetUsd, DEFAULT_MAX_TRACKED_TENANTS, DEFAULT_WINDOW);
    }

    /**
     * @param budgetUsd         per-tenant ceiling; {@code 0} disables enforcement
     * @param maxTrackedTenants cap on distinct buckets; must be >= 1
     * @param window            accrual window before spend rolls over;
     *                          {@code null} or zero disables rollover
     */
    public CostCeilingGuardrail(double budgetUsd, int maxTrackedTenants, Duration window) {
        if (budgetUsd < 0) {
            throw new IllegalArgumentException("budgetUsd must be >= 0, got " + budgetUsd);
        }
        if (maxTrackedTenants < 1) {
            throw new IllegalArgumentException(
                    "maxTrackedTenants must be >= 1, got " + maxTrackedTenants);
        }
        this.budgetUsd = budgetUsd;
        this.maxTrackedTenants = maxTrackedTenants;
        this.window = window == null || window.isZero() || window.isNegative()
                ? null : window;
    }

    /**
     * Roll the accrual forward when the window has elapsed. Without this a
     * ceiling reached once would block every subsequent turn forever, because
     * nothing decays the counter — a cost guard that turns into a permanent
     * outage is worse than no guard.
     */
    private void rolloverIfElapsed() {
        var w = window;
        if (w == null) {
            return;
        }
        var start = windowStart;
        if (Instant.now().isAfter(start.plus(w))) {
            synchronized (this) {
                if (windowStart == start) {
                    spentByTenant.clear();
                    windowStart = Instant.now();
                    logger.info("Cost ceiling accrual window elapsed ({}) — counters rolled over", w);
                }
            }
        }
    }

    /**
     * Resolve the bucket for a tenant, folding overflow into the shared default
     * once the cap is reached so a caller-supplied tag cannot grow the map
     * without bound.
     */
    private String bucketFor(String tenant) {
        var key = tenant != null && !tenant.isBlank() ? tenant : DEFAULT_BUCKET;
        if (DEFAULT_BUCKET.equals(key) || spentByTenant.containsKey(key)) {
            return key;
        }
        if (spentByTenant.size() >= maxTrackedTenants) {
            if (capWarned.compareAndSet(false, true)) {
                logger.warn("Cost ceiling is tracking {} tenants (the cap) — further tenants "
                        + "share the default bucket. Raise the cap if this is a legitimate "
                        + "tenant count rather than an unbounded tag.", maxTrackedTenants);
            }
            return DEFAULT_BUCKET;
        }
        return key;
    }

    @Override
    public GuardrailResult inspectRequest(AiRequest request) {
        if (budgetUsd == 0) {
            return GuardrailResult.pass();
        }
        rolloverIfElapsed();
        var tenant = bucketFor(resolveTenant());
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
        if (cost <= 0) {
            return;
        }
        rolloverIfElapsed();
        spentByTenant.computeIfAbsent(bucketFor(tenant), k -> new DoubleAdder()).add(cost);
    }

    /** Snapshot the accumulated cost for a tenant. */
    public double spent(String tenant) {
        rolloverIfElapsed();
        var adder = spentByTenant.get(
                tenant != null && !tenant.isBlank() ? tenant : DEFAULT_BUCKET);
        return adder == null ? 0.0 : adder.sum();
    }

    /** Distinct tenant buckets currently tracked. Bounded by the configured cap. */
    public int trackedTenants() {
        return spentByTenant.size();
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
