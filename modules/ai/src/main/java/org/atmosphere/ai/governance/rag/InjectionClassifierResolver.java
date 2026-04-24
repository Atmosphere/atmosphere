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
package org.atmosphere.ai.governance.rag;

import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an {@link InjectionClassifier} for a given
 * {@link InjectionClassifier.Tier}. Mirrors the
 * {@code ScopeGuardrailResolver} pattern:
 *
 * <ul>
 *   <li>{@link InjectionClassifier.Tier#RULE_BASED} always resolves to
 *       {@link RuleBasedInjectionClassifier} (no dependencies).</li>
 *   <li>{@link InjectionClassifier.Tier#EMBEDDING_SIMILARITY} resolves an
 *       {@link EmbeddingInjectionClassifier} that picks up any installed
 *       {@link org.atmosphere.ai.EmbeddingRuntime}. Falls back to the
 *       rule-based tier (with a warning logged by the caller's wiring
 *       layer) when no runtime is present.</li>
 *   <li>{@link InjectionClassifier.Tier#LLM_CLASSIFIER} resolves a
 *       {@link LlmClassifierInjectionClassifier} that picks up any
 *       installed {@link org.atmosphere.ai.AgentRuntime}. Falls back to
 *       rule-based when no runtime is present.</li>
 * </ul>
 *
 * <p>Custom implementations can be registered via
 * {@code META-INF/services/org.atmosphere.ai.governance.rag.InjectionClassifier}
 * and take precedence over the built-in resolver for their declared tier.</p>
 */
public final class InjectionClassifierResolver {

    private static final ConcurrentHashMap<InjectionClassifier.Tier, InjectionClassifier> CACHE =
            new ConcurrentHashMap<>();

    private InjectionClassifierResolver() { }

    /**
     * Returns the configured classifier for the given tier. Higher tiers
     * fall through to rule-based when their runtime dependency is absent;
     * this degrades gracefully so RAG keeps working on a bare-JVM setup.
     */
    public static InjectionClassifier resolve(InjectionClassifier.Tier tier) {
        if (tier == null) {
            tier = InjectionClassifier.Tier.EMBEDDING_SIMILARITY;
        }
        var effectiveTier = tier;
        return CACHE.computeIfAbsent(tier, t -> findOrFallback(effectiveTier));
    }

    private static InjectionClassifier findOrFallback(InjectionClassifier.Tier requested) {
        // ServiceLoader-registered impls take precedence for their declared tier.
        for (var candidate : ServiceLoader.load(InjectionClassifier.class)) {
            if (candidate.tier() == requested) {
                return candidate;
            }
        }
        return switch (requested) {
            case RULE_BASED -> new RuleBasedInjectionClassifier();
            case EMBEDDING_SIMILARITY -> new EmbeddingInjectionClassifier();
            case LLM_CLASSIFIER -> new LlmClassifierInjectionClassifier();
        };
    }

    /** True when a dedicated impl exists for this tier (not the rule-based fallback). */
    public static boolean hasNativeImpl(InjectionClassifier.Tier tier) {
        if (tier == InjectionClassifier.Tier.RULE_BASED) return true;
        for (var candidate : ServiceLoader.load(InjectionClassifier.class)) {
            if (candidate.tier() == tier) return true;
        }
        return false;
    }

    /** Testing / reload hook — clears the cache so a new tier impl can be picked up. */
    public static void reset() {
        CACHE.clear();
    }
}
