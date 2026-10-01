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
 * {@link RuleBasedScopeGuardrail} (no dependencies). The other tiers resolve
 * an impl registered via
 * {@code META-INF/services/org.atmosphere.ai.governance.scope.ScopeGuardrail}
 * when one declares that tier, else the built-in impl:
 * {@link EmbeddingScopeGuardrail}, {@link SemanticIntentScopeGuardrail} or
 * {@link LlmClassifierScopeGuardrail}.
 */
public final class ScopeGuardrailResolver {

    private static final ConcurrentHashMap<AgentScope.Tier, ScopeGuardrail> CACHE = new ConcurrentHashMap<>();

    private ScopeGuardrailResolver() { }

    /**
     * Returns the configured guardrail for the given tier, cached per tier.
     * Every tier resolves to an impl of that tier. The embedding tiers
     * degrade to rule-based enforcement per request when no
     * {@code EmbeddingRuntime} is installed (the impl logs a warning) — the
     * "degrade gracefully" choice that v4 §9 flagged as the tuning risk; the
     * LLM tier fails closed when no decision model can answer.
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
        // ServiceLoader-registered impls take precedence for their declared tier.
        for (var candidate : ServiceLoader.load(ScopeGuardrail.class)) {
            if (candidate.tier() == requested) {
                return candidate;
            }
        }
        // Built-in fallback chain. SEMANTIC_INTENT and EMBEDDING_SIMILARITY
        // both use EmbeddingRuntime under the hood and degrade to rule-based
        // when it is absent (impl itself logs a warning). LLM_CLASSIFIER
        // resolves a DecisionModel and, when none can answer, reports ERROR,
        // which ScopePolicy denies (fail-closed).
        return switch (requested) {
            case RULE_BASED -> new RuleBasedScopeGuardrail();
            case EMBEDDING_SIMILARITY -> new EmbeddingScopeGuardrail();
            case SEMANTIC_INTENT -> new SemanticIntentScopeGuardrail();
            case LLM_CLASSIFIER -> new LlmClassifierScopeGuardrail();
        };
    }

    /**
     * True when {@link #resolve} returns an impl of this tier — a
     * ServiceLoader-registered one or the built-in one — rather than an impl
     * of another tier. It reports the resolved impl only, not whether that
     * impl's backing ({@code EmbeddingRuntime}, {@code DecisionModel}) can
     * answer: the embedding tiers still degrade to rule-based per request
     * without a runtime.
     */
    public static boolean hasNativeImpl(AgentScope.Tier tier) {
        return tier != null && resolve(tier).tier() == tier;
    }

    /** Testing / reload hook — clears the cache so a new tier impl can be picked up. */
    public static void reset() {
        CACHE.clear();
    }
}
