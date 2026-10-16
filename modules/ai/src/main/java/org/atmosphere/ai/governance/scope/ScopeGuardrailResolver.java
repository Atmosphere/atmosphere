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

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.annotation.AgentScope;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
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
 *
 * <h2>Per application</h2>
 * Each application resolves its own guardrails ({@link #resolve(AiEnvironment, AgentScope.Tier)}):
 * the built-in embedding tiers are built over that application's
 * {@link org.atmosphere.ai.EmbeddingRuntime} and the built-in LLM tier asks that
 * application's {@link org.atmosphere.ai.decision.DecisionModel}, so two
 * applications in one JVM never screen with each other's model. The guardrails
 * are cached per application and tier, and an application's are dropped when
 * its {@link AiEnvironment} closes, so the cache is bounded by the open
 * applications times the four tiers. {@link #resolve(AgentScope.Tier)} resolves
 * for {@link AiEnvironment#current()}: code with no application.
 *
 * <p>A registered impl other than the built-in ones (the built-ins are
 * registered too, and are always built per application) takes precedence for
 * its declared tier. It is built by its no-argument constructor, so it cannot
 * know an application: one instance per tier serves the whole JVM, as
 * before.</p>
 */
public final class ScopeGuardrailResolver {

    /** The built-in impls: built per application instead of through {@link ServiceLoader}. */
    private static final Set<Class<?>> BUILT_IN = Set.of(RuleBasedScopeGuardrail.class,
            EmbeddingScopeGuardrail.class, SemanticIntentScopeGuardrail.class,
            LlmClassifierScopeGuardrail.class);

    /**
     * One guardrail per tier for each application that resolved one; an
     * application's entry is removed when its environment closes.
     */
    private static final ConcurrentHashMap<AiEnvironment, Map<AgentScope.Tier, ScopeGuardrail>> CACHES =
            new ConcurrentHashMap<>();

    /** The registered impl other than a built-in one, per tier, for the JVM; empty when none. */
    private static final ConcurrentHashMap<AgentScope.Tier, Optional<ScopeGuardrail>> REGISTERED =
            new ConcurrentHashMap<>();

    private ScopeGuardrailResolver() { }

    /**
     * Returns the configured guardrail for the given tier, cached per tier.
     * Every tier resolves to an impl of that tier. The embedding tiers
     * degrade to rule-based enforcement per request when no
     * {@code EmbeddingRuntime} is installed (the impl logs a warning) — the
     * "degrade gracefully" choice that v4 §9 flagged as the tuning risk; the
     * LLM tier fails closed when no decision model can answer.
     *
     * <p>Resolves for {@link AiEnvironment#current()}: the application bound to
     * the calling thread, else the only one running, else the process
     * default. Code that holds an application calls
     * {@link #resolve(AiEnvironment, AgentScope.Tier)}.</p>
     *
     * <p>Throws {@link IllegalStateException} if the rule-based tier itself
     * fails to instantiate — that's a classpath pathology, not a user-fixable
     * misconfiguration.</p>
     */
    public static ScopeGuardrail resolve(AgentScope.Tier tier) {
        return resolve(AiEnvironment.current(), tier);
    }

    /**
     * The guardrail of one application for the given tier ({@code null} means
     * {@link AgentScope.Tier#EMBEDDING_SIMILARITY}), cached per application and
     * tier until the application closes. The built-in embedding tiers use the
     * {@code EmbeddingRuntime} the application has when the guardrail is
     * built, and degrade to rule-based enforcement per request without one;
     * the built-in LLM tier resolves the application's decision model on each
     * request and fails closed when none can answer. A closed application gets
     * a guardrail that is not cached, built without its runtimes.
     *
     * @param environment the application's environment
     * @param tier        the tier
     * @return the guardrail, never {@code null}
     */
    public static ScopeGuardrail resolve(AiEnvironment environment, AgentScope.Tier tier) {
        Objects.requireNonNull(environment, "environment");
        var effectiveTier = tier == null ? AgentScope.Tier.EMBEDDING_SIMILARITY : tier;
        return cacheOf(environment).computeIfAbsent(effectiveTier, t -> findOrFallback(environment, t));
    }

    /**
     * The application's cache. The close action registered with a new one
     * removes it; on an environment already closed it runs at once, so a
     * closed application's cache is never kept.
     */
    private static Map<AgentScope.Tier, ScopeGuardrail> cacheOf(AiEnvironment environment) {
        var cache = CACHES.get(environment);
        if (cache != null) {
            return cache;
        }
        var created = new ConcurrentHashMap<AgentScope.Tier, ScopeGuardrail>();
        cache = CACHES.putIfAbsent(environment, created);
        if (cache != null) {
            return cache;
        }
        environment.onClose(() -> CACHES.remove(environment, created));
        return created;
    }

    private static ScopeGuardrail findOrFallback(AiEnvironment environment, AgentScope.Tier requested) {
        if (requested == AgentScope.Tier.RULE_BASED) {
            return new RuleBasedScopeGuardrail();
        }
        // Registered impls (other than the built-ins) take precedence for their declared tier.
        var registered = REGISTERED.computeIfAbsent(requested, ScopeGuardrailResolver::findRegistered);
        if (registered.isPresent()) {
            return registered.get();
        }
        // Built-in fallback chain, bound to the application. SEMANTIC_INTENT and
        // EMBEDDING_SIMILARITY both use its EmbeddingRuntime and degrade to
        // rule-based when it has none (the impl itself logs a warning).
        // LLM_CLASSIFIER resolves the application's DecisionModel and, when none
        // can answer, reports ERROR, which ScopePolicy denies (fail-closed).
        return switch (requested) {
            case RULE_BASED -> new RuleBasedScopeGuardrail();
            case EMBEDDING_SIMILARITY -> new EmbeddingScopeGuardrail(embeddingRuntime(environment));
            case SEMANTIC_INTENT -> new SemanticIntentScopeGuardrail(embeddingRuntime(environment),
                    SemanticIntentScopeGuardrail.DEFAULT_MARGIN);
            case LLM_CLASSIFIER -> new LlmClassifierScopeGuardrail(environment);
        };
    }

    /** The application's embedding runtime, or {@code null}; a closed application loads none. */
    private static EmbeddingRuntime embeddingRuntime(AiEnvironment environment) {
        return environment.isClosed() ? null : environment.embeddings().resolve().orElse(null);
    }

    /** The first registered impl of {@code tier} that is not a built-in one; the built-ins are not instantiated. */
    private static Optional<ScopeGuardrail> findRegistered(AgentScope.Tier tier) {
        var providers = ServiceLoader.load(ScopeGuardrail.class).stream().iterator();
        while (providers.hasNext()) {
            var provider = providers.next();
            if (BUILT_IN.contains(provider.type())) {
                continue;
            }
            var candidate = provider.get();
            if (candidate.tier() == tier) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
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

    /**
     * {@link #hasNativeImpl(AgentScope.Tier)} for one application.
     *
     * @param environment the application's environment
     * @param tier        the tier
     * @return whether the application's guardrail for {@code tier} is of that tier
     */
    public static boolean hasNativeImpl(AiEnvironment environment, AgentScope.Tier tier) {
        return tier != null && resolve(environment, tier).tier() == tier;
    }

    /** Whether {@code environment} has cached guardrails: test hook for the release on close. */
    static boolean caches(AiEnvironment environment) {
        return CACHES.containsKey(environment);
    }

    /**
     * Testing / reload hook — clears the cache so a new tier impl can be
     * picked up: every application's guardrails and the registered impls.
     */
    public static void reset() {
        CACHES.values().forEach(Map::clear);
        REGISTERED.clear();
    }
}
