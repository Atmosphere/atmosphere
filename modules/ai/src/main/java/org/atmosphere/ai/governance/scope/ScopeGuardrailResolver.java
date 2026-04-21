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
package org.atmosphere.ai.governance.scope;

import org.atmosphere.ai.annotation.AgentScope;

import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@link ScopeGuardrail} for a given {@link AgentScope.Tier}.
 * {@link AgentScope.Tier#RULE_BASED} always resolves to
 * {@link RuleBasedScopeGuardrail} (no dependencies). Embedding-similarity and
 * LLM-classifier tiers resolve through {@link ServiceLoader} — their impls
 * ship in follow-up commits and register via
 * {@code META-INF/services/org.atmosphere.ai.governance.scope.ScopeGuardrail}.
 */
public final class ScopeGuardrailResolver {

    private static final ConcurrentHashMap<AgentScope.Tier, ScopeGuardrail> CACHE = new ConcurrentHashMap<>();

    private ScopeGuardrailResolver() { }

    /**
     * Returns the configured guardrail for the given tier. Rule-based tier
     * always resolves; higher tiers fall back to the rule-based guardrail
     * (with a warning logged by the caller's wiring layer) when no
     * ServiceLoader entry is present — this is the "degrade gracefully"
     * choice that v4 §9 flagged as the tuning risk.
     *
     * <p>Throws {@link IllegalStateException} if the rule-based tier itself
     * fails to instantiate — that's a classpath pathology, not a user-fixable
     * misconfiguration.</p>
     */
    public static ScopeGuardrail resolve(AgentScope.Tier tier) {
        if (tier == null) {
            tier = AgentScope.Tier.EMBEDDING_SIMILARITY;
        }
        var effectiveTier = tier;
        return CACHE.computeIfAbsent(tier, t -> findOrFallback(effectiveTier));
    }

    private static ScopeGuardrail findOrFallback(AgentScope.Tier requested) {
        if (requested == AgentScope.Tier.RULE_BASED) {
            return new RuleBasedScopeGuardrail();
        }
        // Look for ServiceLoader-registered impl for the requested tier.
        for (var candidate : ServiceLoader.load(ScopeGuardrail.class)) {
            if (candidate.tier() == requested) {
                return candidate;
            }
        }
        // Fall through to rule-based — the framework keeps working, operator
        // sees a warning at wiring time.
        return new RuleBasedScopeGuardrail();
    }

    /** True when a dedicated impl exists for this tier (not the rule-based fallback). */
    public static boolean hasNativeImpl(AgentScope.Tier tier) {
        if (tier == AgentScope.Tier.RULE_BASED) return true;
        for (var candidate : ServiceLoader.load(ScopeGuardrail.class)) {
            if (candidate.tier() == tier) return true;
        }
        return false;
    }

    /** Testing / reload hook — clears the cache so a new tier impl can be picked up. */
    public static void reset() {
        CACHE.clear();
    }
}
