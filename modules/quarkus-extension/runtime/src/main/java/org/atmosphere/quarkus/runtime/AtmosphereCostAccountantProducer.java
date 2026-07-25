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
package org.atmosphere.quarkus.runtime;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.cost.CostAccountant;
import org.atmosphere.ai.cost.CostAccountantHolder;
import org.atmosphere.ai.cost.CostCeilingAccountant;
import org.atmosphere.ai.cost.TokenPricing;
import org.atmosphere.ai.cost.TokenPricingHolder;
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Quarkus port of the Spring Boot starter's {@code CostAccountantInstaller}
 * (in {@code AtmosphereAiAutoConfiguration}). Closes the per-tenant
 * cost-ceiling observability → enforcement loop on Quarkus with the same
 * semantics as Spring Boot (Mode Parity, Correctness Invariant #7):
 *
 * <ol>
 *   <li>A user-supplied {@link CostAccountant} CDI bean wins (custom
 *       attribution — ledger, Micrometer, third-party billing).</li>
 *   <li>Otherwise, a {@link CostCeilingGuardrail} (a user CDI bean, or the
 *       built-in one constructed from
 *       {@code quarkus.atmosphere.ai.guardrails.cost.enabled=true} +
 *       {@code budget-usd} — the mirror of Spring's
 *       {@code atmosphere.ai.guardrails.cost.*} keys) composed with a
 *       {@link TokenPricing} CDI bean yields the built-in
 *       {@link CostCeilingAccountant}.</li>
 *   <li>With neither path wired the {@link CostAccountantHolder} stays at
 *       its no-op default and {@code CostAccountingSession} never wraps —
 *       zero overhead and no phantom enforcement (Runtime Truth,
 *       Correctness Invariant #5).</li>
 * </ol>
 *
 * <p>The block half of the loop needs the guardrail inside each endpoint's
 * inspection chain, and {@code AiEndpointProcessor} freezes that chain
 * during annotation processing — which on Quarkus happens at servlet init,
 * <em>before</em> {@link StartupEvent} observers run. So the guardrail is
 * bridged into the framework-scoped {@link AiGuardrail#GUARDRAILS_PROPERTY}
 * by {@link QuarkusAtmosphereServlet} via {@link #bridgeGuardrail} between
 * framework creation and {@code framework.init()} — the Quarkus equivalent
 * of the Spring registrar's bean-list bridge. The accountant itself is read
 * per-dispatch from the holder, so it installs at {@link StartupEvent}.</p>
 *
 * <p>A {@link TokenPricing} CDI bean is additionally installed into
 * {@link TokenPricingHolder} — even without a guardrail — so the
 * {@code atmosphere.ai.cost} meter at the shared metrics seam is fed
 * (observability without a ceiling), matching the Spring installer.</p>
 *
 * <p>{@link #onShutdown(ShutdownEvent)} resets only the holders this bean
 * installed (Ownership, Correctness Invariant #1) so a Quarkus dev-mode
 * live reload does not leave stale accountant or pricing references behind
 * — symmetric to {@code DisposableBean.destroy()} in the Spring Boot
 * configuration.</p>
 */
@ApplicationScoped
public class AtmosphereCostAccountantProducer {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereCostAccountantProducer.class);

    @Inject
    AtmosphereConfig config;

    // Instance<> is always satisfiable, so absent user beans do not make these
    // injections unresolvable; isResolvable() == exactly one bean present.
    @Inject
    Instance<CostAccountant> userAccountant;

    @Inject
    Instance<CostCeilingGuardrail> userGuardrail;

    @Inject
    Instance<TokenPricing> userPricing;

    // The effective guardrail — a user CDI bean or the config-built one. The
    // same instance must be bridged into the inspection chain (block half) and
    // fed by the accountant (accrual half), so it is resolved once and shared.
    private volatile CostCeilingGuardrail effectiveGuardrail;
    private boolean guardrailResolved;

    // Non-null only when this bean installed into the process-wide holder —
    // so onShutdown() resets only what it installed (Correctness Invariant #1).
    private volatile CostAccountant installedAccountant;
    private volatile TokenPricing installedPricing;
    private volatile String source = "not-started";

    /**
     * Bridges the effective {@link CostCeilingGuardrail} into the
     * framework-scoped {@link AiGuardrail#GUARDRAILS_PROPERTY} bag so
     * {@code AiEndpointProcessor} merges it into every {@code @AiEndpoint}
     * inspection chain. Called by {@link QuarkusAtmosphereServlet} after the
     * framework is created but before {@code framework.init()} runs
     * annotation processing; a no-op when neither a user guardrail bean nor
     * {@code quarkus.atmosphere.ai.guardrails.cost.enabled=true} is present.
     *
     * @param framework the created (not yet initialized) framework
     */
    void bridgeGuardrail(AtmosphereFramework framework) {
        var guardrail = resolveGuardrail();
        if (guardrail == null || framework == null) {
            return;
        }
        var properties = framework.getAtmosphereConfig().properties();
        var merged = new ArrayList<AiGuardrail>();
        // Merge additively — never clobber a list another integration bridged.
        if (properties.get(AiGuardrail.GUARDRAILS_PROPERTY) instanceof List<?> existing) {
            for (var g : existing) {
                if (g instanceof AiGuardrail ai) {
                    merged.add(ai);
                }
            }
        }
        if (!merged.contains(guardrail)) {
            merged.add(guardrail);
        }
        properties.put(AiGuardrail.GUARDRAILS_PROPERTY, List.copyOf(merged));
        logger.info("Bridged CostCeilingGuardrail into framework guardrail properties "
                + "(quarkus.atmosphere.ai.guardrails.cost)");
    }

    /**
     * Installs the {@link CostAccountant} (and any {@link TokenPricing} bean)
     * into the process-wide holders on application startup, mirroring the
     * Spring installer's priority: user accountant bean, else guardrail +
     * pricing composed into the built-in {@link CostCeilingAccountant}, else
     * the holder stays at NOOP.
     *
     * @param event the Quarkus startup event (unused, present so Arc fires
     *              the observer eagerly)
     */
    public void onStart(@Observes @Priority(130) StartupEvent event) {
        if (installedAccountant != null) {
            return;
        }
        var pricing = userPricing.isResolvable() ? userPricing.get() : null;
        CostAccountant accountant = null;
        if (userAccountant.isResolvable()) {
            accountant = userAccountant.get();
            source = "user-bean";
        } else {
            var guardrail = resolveGuardrail();
            if (guardrail != null && pricing != null) {
                accountant = new CostCeilingAccountant(guardrail, pricing);
                source = "CostCeilingAccountant(guardrail+pricing)";
            } else {
                source = "NOOP (no CostAccountant, guardrail, or pricing bean)";
            }
        }
        if (accountant != null) {
            CostAccountantHolder.install(accountant);
            installedAccountant = accountant;
            logger.info("CostAccountant installed ({}): {}",
                    source, accountant.getClass().getSimpleName());
        } else {
            logger.debug("AtmosphereCostAccountantProducer: {} — holder stays at NOOP", source);
        }
        if (pricing != null) {
            TokenPricingHolder.install(pricing);
            installedPricing = pricing;
            logger.info("TokenPricing installed — the atmosphere.ai.cost meter is live");
        }
    }

    /**
     * Resets the process-wide holders this bean installed into — and only
     * those (Ownership, Correctness Invariant #1) — so a dev-mode live
     * reload or test-context restart does not carry stale references.
     *
     * @param event the Quarkus shutdown event (unused, present so Arc fires
     *              the observer)
     */
    public void onShutdown(@Observes ShutdownEvent event) {
        if (installedAccountant != null) {
            CostAccountantHolder.reset();
            installedAccountant = null;
        }
        if (installedPricing != null) {
            TokenPricingHolder.reset();
            installedPricing = null;
        }
    }

    /**
     * Resolves the effective guardrail exactly once: a user-supplied
     * {@link CostCeilingGuardrail} CDI bean wins (mirroring Spring's
     * {@code @ConditionalOnMissingBean}); otherwise the built-in guardrail is
     * constructed when {@code quarkus.atmosphere.ai.guardrails.cost.enabled}
     * is {@code true}. Returns {@code null} when neither is configured.
     */
    private synchronized CostCeilingGuardrail resolveGuardrail() {
        if (guardrailResolved) {
            return effectiveGuardrail;
        }
        guardrailResolved = true;
        if (userGuardrail.isResolvable()) {
            effectiveGuardrail = userGuardrail.get();
        } else if (config.ai().guardrails().cost().enabled()) {
            var budgetUsd = config.ai().guardrails().cost().budgetUsd();
            effectiveGuardrail = new CostCeilingGuardrail(budgetUsd);
            logger.info("CostCeilingGuardrail registered (budgetUsd={})", budgetUsd);
        }
        return effectiveGuardrail;
    }

    /**
     * Accessor used by tests to confirm which install path fired.
     *
     * @return the resolved source label
     */
    public String source() {
        return source;
    }

    /**
     * Accessor used by tests to assert against the exact guardrail instance
     * bridged into the chain and fed by the accountant.
     *
     * @return the effective guardrail, or {@code null} when none is configured
     */
    public CostCeilingGuardrail effectiveGuardrail() {
        return effectiveGuardrail;
    }

    /**
     * Accessor used by tests to confirm what the startup observer installed.
     *
     * @return the installed accountant, or {@code null} when the holder stayed at NOOP
     */
    public CostAccountant installedAccountant() {
        return installedAccountant;
    }
}
