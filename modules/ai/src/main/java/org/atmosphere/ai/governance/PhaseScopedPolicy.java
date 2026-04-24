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

import java.util.EnumSet;
import java.util.Set;

/**
 * Decorator that restricts an inner {@link GovernancePolicy} to run only
 * on specified {@link PolicyContext.Phase} values. Evaluations on other
 * phases bypass the delegate entirely and return
 * {@link PolicyDecision#admit()}.
 *
 * <p>Collapses the boilerplate every policy currently writes:</p>
 * <pre>{@code
 * public PolicyDecision evaluate(PolicyContext ctx) {
 *     if (ctx.phase() == PolicyContext.Phase.POST_RESPONSE) {
 *         return PolicyDecision.admit();
 *     }
 *     // ... real logic ...
 * }
 * }</pre>
 *
 * <p>Equivalent using this decorator:</p>
 * <pre>{@code
 * var gated = PhaseScopedPolicy.preAdmissionOnly(new MyRealPolicy(...));
 * }</pre>
 *
 * <p>Useful when composing via {@link PolicyRing} or direct
 * {@code AiPipeline} wiring, or when lifting a guardrail that only cares
 * about one direction into a policy position.</p>
 */
public final class PhaseScopedPolicy implements GovernancePolicy {

    private final GovernancePolicy delegate;
    private final Set<PolicyContext.Phase> activePhases;

    public PhaseScopedPolicy(GovernancePolicy delegate, Set<PolicyContext.Phase> activePhases) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (activePhases == null || activePhases.isEmpty()) {
            throw new IllegalArgumentException("activePhases must be non-empty");
        }
        this.delegate = delegate;
        this.activePhases = EnumSet.copyOf(activePhases);
    }

    /** Convenience — run the delegate only during {@code PRE_ADMISSION}. */
    public static PhaseScopedPolicy preAdmissionOnly(GovernancePolicy delegate) {
        return new PhaseScopedPolicy(delegate, EnumSet.of(PolicyContext.Phase.PRE_ADMISSION));
    }

    /** Convenience — run the delegate only during {@code POST_RESPONSE}. */
    public static PhaseScopedPolicy postResponseOnly(GovernancePolicy delegate) {
        return new PhaseScopedPolicy(delegate, EnumSet.of(PolicyContext.Phase.POST_RESPONSE));
    }

    @Override public String name() { return delegate.name(); }
    @Override public String source() { return delegate.source(); }
    @Override public String version() { return delegate.version(); }

    public GovernancePolicy delegate() { return delegate; }
    public Set<PolicyContext.Phase> activePhases() { return EnumSet.copyOf(activePhases); }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (!activePhases.contains(context.phase())) {
            return PolicyDecision.admit();
        }
        return delegate.evaluate(context);
    }
}
