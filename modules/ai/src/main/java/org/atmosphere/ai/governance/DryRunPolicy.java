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
package org.atmosphere.ai.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shadow wrapper: evaluates the delegate policy, records what it WOULD have
 * decided into the audit trail + metrics, but always returns
 * {@link PolicyDecision#admit()}. The rollout-safety primitive every
 * governance plane needs — operators run a new rule in dry-run for N days,
 * inspect {@code GET /api/admin/governance/decisions} to see what traffic
 * it would have blocked, then flip to enforcement.
 *
 * <p>The wrapped decision lands in the {@link GovernanceDecisionLog} with
 * {@code decision} prefixed {@code "dry-run:"} so the admin surface can
 * distinguish shadow-mode entries from enforced ones. Counters on this
 * wrapper instance expose the hit rate without scanning the audit log.</p>
 *
 * <p>Composition: wrap <i>any</i> {@link GovernancePolicy}, including the
 * framework-provided ones (scope, kill-switch, confidence). The delegate's
 * identity (name/source/version) is preserved so audit entries remain
 * indexable by policy name — we just prefix the name with {@code "dry-run:"}
 * so shadow traffic is never conflated with enforced traffic.</p>
 */
public final class DryRunPolicy implements GovernancePolicy {

    private static final Logger logger = LoggerFactory.getLogger(DryRunPolicy.class);

    /** Prefix applied to the delegate's name on this wrapper's own identity. */
    public static final String NAME_PREFIX = "dry-run:";

    /** Decision-label prefix recorded into the audit trail for dry-run entries. */
    public static final String AUDIT_DECISION_PREFIX = "dry-run:";

    private final GovernancePolicy delegate;
    private final AtomicLong shadowAdmits = new AtomicLong();
    private final AtomicLong shadowDenies = new AtomicLong();
    private final AtomicLong shadowTransforms = new AtomicLong();
    private final AtomicLong delegateErrors = new AtomicLong();

    public DryRunPolicy(GovernancePolicy delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (delegate instanceof DryRunPolicy) {
            throw new IllegalArgumentException(
                    "double-wrapping DryRunPolicy is a no-op and usually a mistake");
        }
        this.delegate = delegate;
    }

    @Override public String name() { return NAME_PREFIX + delegate.name(); }
    @Override public String source() { return delegate.source(); }
    @Override public String version() { return delegate.version(); }

    /** Exposed so admin surfaces can render "wraps X" in the policy list. */
    public GovernancePolicy delegate() {
        return delegate;
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        PolicyDecision shadow;
        try {
            shadow = delegate.evaluate(context);
        } catch (RuntimeException e) {
            // Delegate threw — in a real enforced chain this would be fail-closed.
            // In dry-run we admit and count the error so operators notice broken
            // policies before they promote to enforcement.
            delegateErrors.incrementAndGet();
            logger.warn("DryRunPolicy delegate '{}' threw during evaluate — shadow recording only, admitting",
                    delegate.name(), e);
            recordShadow("error", "delegate threw: " + e.getClass().getSimpleName(), context);
            return PolicyDecision.admit();
        }

        switch (shadow) {
            case PolicyDecision.Admit ignored -> {
                shadowAdmits.incrementAndGet();
                recordShadow("admit", "", context);
            }
            case PolicyDecision.Deny deny -> {
                shadowDenies.incrementAndGet();
                recordShadow("deny", deny.reason(), context);
                logger.info("DryRunPolicy '{}' would have DENIED: {}", delegate.name(), deny.reason());
            }
            case PolicyDecision.Transform ignored -> {
                shadowTransforms.incrementAndGet();
                recordShadow("transform", "would-rewrite", context);
            }
        }

        // Always admit — that's the whole point of dry-run.
        return PolicyDecision.admit();
    }

    public long shadowAdmits() { return shadowAdmits.get(); }
    public long shadowDenies() { return shadowDenies.get(); }
    public long shadowTransforms() { return shadowTransforms.get(); }
    public long delegateErrors() { return delegateErrors.get(); }

    /** Total shadow evaluations (admit + deny + transform + error). */
    public long totalEvaluations() {
        return shadowAdmits.get() + shadowDenies.get() + shadowTransforms.get() + delegateErrors.get();
    }

    /**
     * Reset the counters — intended for tests / ops runbooks that want to
     * re-baseline the dry-run window. Does NOT remove audit entries.
     */
    public void resetCounters() {
        shadowAdmits.set(0);
        shadowDenies.set(0);
        shadowTransforms.set(0);
        delegateErrors.set(0);
    }

    private void recordShadow(String shadowDecision, String reason, PolicyContext context) {
        var log = GovernanceDecisionLog.installed();
        var entry = GovernanceDecisionLog.entry(
                delegate,
                context,
                AUDIT_DECISION_PREFIX + shadowDecision,
                reason,
                0.0);
        log.record(entry);
    }
}
