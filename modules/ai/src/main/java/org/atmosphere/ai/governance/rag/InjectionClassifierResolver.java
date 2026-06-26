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

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.EmbeddingRuntimeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
 *       {@link EmbeddingInjectionClassifier} (running on a
 *       {@link RuleBasedInjectionClassifier} floor via
 *       {@link CompositeInjectionClassifier}) bound to the installed
 *       {@link org.atmosphere.ai.EmbeddingRuntime}. When no runtime is
 *       present the resolver downgrades to rule-based and logs a warning.</li>
 *   <li>{@link InjectionClassifier.Tier#LLM_CLASSIFIER} resolves a
 *       {@link LlmClassifierInjectionClassifier} on the same rule-based floor,
 *       bound to the installed {@link org.atmosphere.ai.AgentRuntime}.</li>
 * </ul>
 *
 * <p>The rule-based floor under the higher tiers is what keeps the screen from
 * silently failing open: the cheap probes always catch the canonical injection
 * vectors even when the higher tier's runtime is a no-key fallback that would
 * admit every document on its own. Higher tiers only add recall, never subtract
 * coverage.</p>
 *
 * <p>Custom implementations can be registered via
 * {@code META-INF/services/org.atmosphere.ai.governance.rag.InjectionClassifier}
 * and take precedence over the built-in resolver for their declared tier.</p>
 */
public final class InjectionClassifierResolver {

    private static final Logger logger = LoggerFactory.getLogger(InjectionClassifierResolver.class);

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
        // Higher tiers consume a runtime SPI. Two fail-closed guarantees:
        //   1. When the runtime is genuinely absent (no EmbeddingRuntime), the
        //      tier downgrades to RULE_BASED — which still enforces — instead of
        //      a classifier that admits every document.
        //   2. When a runtime IS present, the higher tier runs on top of a
        //      RULE_BASED floor (CompositeInjectionClassifier): the cheap probes
        //      always catch the canonical injection vectors even if the higher
        //      tier is weak (e.g. a no-key fallback model) or returns ambiguous.
        // Either way the screen never silently fails open. Mirrors
        // ScopeGuardrailResolver's resolve-then-report idiom for the effective tier.
        return switch (requested) {
            case RULE_BASED -> new RuleBasedInjectionClassifier();
            case EMBEDDING_SIMILARITY -> {
                var runtime = EmbeddingRuntimeResolver.resolve();
                if (runtime.isPresent()) {
                    yield new CompositeInjectionClassifier(List.of(
                            new RuleBasedInjectionClassifier(),
                            new EmbeddingInjectionClassifier(runtime.get())));
                }
                logger.warn("RAG injection-safety tier EMBEDDING_SIMILARITY requested but no "
                        + "EmbeddingRuntime is installed — downgrading to RULE_BASED so retrieval "
                        + "stays fail-closed. Install an embedding runtime module or set the tier "
                        + "to RULE_BASED to silence this warning.");
                yield new RuleBasedInjectionClassifier();
            }
            case LLM_CLASSIFIER -> {
                // AgentRuntimeResolver always resolves a runtime (a no-key
                // built-in/demo fallback at worst), which would admit-all on
                // its own — so the rule-based floor is what keeps this tier
                // fail-closed, not a downgrade.
                var runtime = AgentRuntimeResolver.resolve();
                if (runtime != null) {
                    yield new CompositeInjectionClassifier(List.of(
                            new RuleBasedInjectionClassifier(),
                            new LlmClassifierInjectionClassifier(runtime)));
                }
                logger.warn("RAG injection-safety tier LLM_CLASSIFIER requested but no AgentRuntime "
                        + "is installed — downgrading to RULE_BASED so retrieval stays fail-closed. "
                        + "Install a runtime module or set the tier to RULE_BASED to silence this warning.");
                yield new RuleBasedInjectionClassifier();
            }
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
