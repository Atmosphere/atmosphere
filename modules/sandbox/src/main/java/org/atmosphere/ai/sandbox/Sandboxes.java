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
package org.atmosphere.ai.sandbox;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Tier-aware {@link SandboxProvider} selection. Replaces the ad-hoc
 * "first available provider" loops with a policy-grade chooser: callers (and
 * governance admission) declare the minimum {@link IsolationTier} the work
 * warrants, and {@link #select(IsolationTier)} returns an available backend that
 * meets the floor — never weaker.
 *
 * <p>Among the providers that satisfy the floor, the <strong>strongest</strong>
 * available isolation is returned, so a request can never be silently downgraded
 * to a weaker sandbox than something stronger that was on the classpath and
 * usable.</p>
 */
public final class Sandboxes {

    private Sandboxes() {
    }

    /**
     * The strongest-isolation available {@link SandboxProvider} whose
     * {@link SandboxProvider#tier()} is at least {@code minTier}.
     *
     * @param minTier the minimum isolation the caller requires;
     *                {@code null} is treated as {@link IsolationTier#PROCESS}
     * @return the chosen provider, or empty when no available provider meets the
     *         floor (the caller must then refuse to run, not fall back to a
     *         weaker sandbox — Security Invariant #6, fail-closed)
     */
    public static Optional<SandboxProvider> select(IsolationTier minTier) {
        return select(minTier, ServiceLoader.load(SandboxProvider.class));
    }

    /** Selection over an explicit provider source — for tests and custom wiring. */
    public static Optional<SandboxProvider> select(IsolationTier minTier,
                                                   Iterable<SandboxProvider> providers) {
        var floor = minTier != null ? minTier : IsolationTier.PROCESS;
        SandboxProvider best = null;
        for (var provider : providers) {
            if (provider.isAvailable() && provider.tier().isAtLeast(floor)
                    && (best == null || provider.tier().ordinal() > best.tier().ordinal())) {
                best = provider;
            }
        }
        return Optional.ofNullable(best);
    }
}
