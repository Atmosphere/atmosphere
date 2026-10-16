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

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
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
 *       {@link CompositeInjectionClassifier}) bound to the application's
 *       {@link org.atmosphere.ai.EmbeddingRuntime}. When it has none the
 *       resolver downgrades to rule-based and logs a warning.</li>
 *   <li>{@link InjectionClassifier.Tier#LLM_CLASSIFIER} resolves a
 *       {@link LlmClassifierInjectionClassifier} on the same rule-based floor,
 *       bound to the {@link org.atmosphere.ai.decision.DecisionModel} that
 *       {@link DecisionModelResolver#resolve(AiEnvironment)} returns for the
 *       application. When none can answer (only the demo runtime is installed,
 *       and no registered DecisionModel is available at that moment) the
 *       resolver downgrades to rule-based and logs a warning.</li>
 * </ul>
 *
 * <h2>Per application</h2>
 * Each application resolves its own classifiers ({@link #resolve(AiEnvironment, InjectionClassifier.Tier)}),
 * over its own embedding runtime and decision model, so two applications in
 * one JVM never screen with each other's model. They are cached per
 * application and tier, and an application's are dropped when its
 * {@link AiEnvironment} closes, so the cache is bounded by the open
 * applications times the three tiers. {@link #resolve(InjectionClassifier.Tier)}
 * resolves for {@link AiEnvironment#current()}: code with no application.
 *
 * <p>Each result, a downgrade included, is cached until {@link #reset()} or the
 * application closes. A downgraded {@code LLM_CLASSIFIER} therefore stays
 * {@code RULE_BASED} even if a registered DecisionModel (an external endpoint
 * that was down at boot, say) becomes available later and
 * {@link DecisionModelResolver} starts returning it. That is deliberate: the
 * consumers ({@code SafetyContextProvider}, {@code MemorySafetyConfig}) keep the
 * classifier they were built with, and {@code MemorySafetyConfig.publishActive}
 * resolves again to report the tier in force, so a resolver that upgraded
 * behind them would report a tier nothing runs (Correctness Invariant #5).
 * Call {@link #reset()} and rebuild the consumers to pick the model up.</p>
 *
 * <p>The rule-based floor under the higher tiers is what keeps the screen from
 * silently failing open: the cheap probes always catch the canonical injection
 * vectors even when the higher tier is weak or is moved by adversarial text.
 * Higher tiers only add recall, never subtract coverage.</p>
 *
 * <p>Custom implementations can be registered via
 * {@code META-INF/services/org.atmosphere.ai.governance.rag.InjectionClassifier}
 * and take precedence over the built-in resolver for their declared tier. Such
 * an implementation is built by its no-argument constructor, so it cannot know
 * an application: one instance per tier serves the whole JVM.</p>
 */
public final class InjectionClassifierResolver {

    private static final Logger logger = LoggerFactory.getLogger(InjectionClassifierResolver.class);

    /** The built-in classifiers: built per application, never through {@link ServiceLoader}. */
    private static final Set<Class<?>> BUILT_IN = Set.of(RuleBasedInjectionClassifier.class,
            EmbeddingInjectionClassifier.class, LlmClassifierInjectionClassifier.class,
            CompositeInjectionClassifier.class);

    /**
     * One classifier per tier for each application that resolved one; an
     * application's entry is removed when its environment closes.
     */
    private static final ConcurrentHashMap<AiEnvironment, Map<InjectionClassifier.Tier, InjectionClassifier>>
            CACHES = new ConcurrentHashMap<>();

    /** The registered classifier other than a built-in one, per tier, for the JVM; empty when none. */
    private static final ConcurrentHashMap<InjectionClassifier.Tier, Optional<InjectionClassifier>> REGISTERED =
            new ConcurrentHashMap<>();

    /** The built-in rule-based classifier: stateless, so one serves the JVM. */
    private static final InjectionClassifier BUILT_IN_RULE_BASED = new RuleBasedInjectionClassifier();

    private InjectionClassifierResolver() { }

    /**
     * Returns the configured classifier for the given tier. Higher tiers
     * fall through to rule-based when their runtime dependency is absent;
     * this degrades gracefully so RAG keeps working on a bare-JVM setup.
     * Resolves for {@link AiEnvironment#current()}; code that holds an
     * application calls {@link #resolve(AiEnvironment, InjectionClassifier.Tier)}.
     */
    public static InjectionClassifier resolve(InjectionClassifier.Tier tier) {
        return resolve(AiEnvironment.current(), tier);
    }

    /**
     * The {@link InjectionClassifier.Tier#RULE_BASED} classifier, which uses
     * no model and so belongs to no application: the registered
     * {@code RULE_BASED} implementation when there is one, else the built-in
     * {@link RuleBasedInjectionClassifier}. Reads no {@link AiEnvironment}, so
     * framework code with no application at hand (the tool-output screen)
     * calls it instead of {@link #resolve(InjectionClassifier.Tier)}.
     *
     * @return the classifier, never {@code null}
     */
    public static InjectionClassifier ruleBased() {
        return REGISTERED.computeIfAbsent(InjectionClassifier.Tier.RULE_BASED,
                InjectionClassifierResolver::findRegistered).orElse(BUILT_IN_RULE_BASED);
    }

    /**
     * The classifier of one application for the given tier ({@code null} means
     * {@link InjectionClassifier.Tier#EMBEDDING_SIMILARITY}), cached per
     * application and tier until {@link #reset()} or the application closes.
     * A closed application gets a classifier that is not cached, built
     * without its runtimes (so rule-based).
     *
     * @param environment the application's environment
     * @param tier        the tier
     * @return the classifier, never {@code null}
     */
    public static InjectionClassifier resolve(AiEnvironment environment, InjectionClassifier.Tier tier) {
        Objects.requireNonNull(environment, "environment");
        var effectiveTier = tier == null ? InjectionClassifier.Tier.EMBEDDING_SIMILARITY : tier;
        return cacheOf(environment).computeIfAbsent(effectiveTier, t -> findOrFallback(environment, t));
    }

    /**
     * The application's cache. The close action registered with a new one
     * removes it; on an environment already closed it runs at once, so a
     * closed application's cache is never kept.
     */
    private static Map<InjectionClassifier.Tier, InjectionClassifier> cacheOf(AiEnvironment environment) {
        var cache = CACHES.get(environment);
        if (cache != null) {
            return cache;
        }
        var created = new ConcurrentHashMap<InjectionClassifier.Tier, InjectionClassifier>();
        cache = CACHES.putIfAbsent(environment, created);
        if (cache != null) {
            return cache;
        }
        environment.onClose(() -> CACHES.remove(environment, created));
        return created;
    }

    private static InjectionClassifier findOrFallback(AiEnvironment environment,
                                                      InjectionClassifier.Tier requested) {
        // Registered impls (other than the built-ins) take precedence for their declared tier.
        var registered = REGISTERED.computeIfAbsent(requested, InjectionClassifierResolver::findRegistered);
        if (registered.isPresent()) {
            return registered.get();
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
                var runtime = environment.isClosed()
                        ? Optional.<EmbeddingRuntime>empty()
                        : environment.embeddings().resolve();
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
                // The LLM tier asks a DecisionModel one boolean question per
                // document. DecisionModelResolver returns a model only when one
                // can genuinely answer: a registered DecisionModel, or a
                // RuntimeDecisionModel over the application's runtime when it is
                // not the demo fallback (a keyless local model counts). The demo
                // runtime answers every prompt with a canned script, so with only
                // it installed there is no LLM layer to add — the tier downgrades
                // and reports RULE_BASED instead of advertising a composite whose
                // LLM layer cannot clear a document (Correctness Invariant #5).
                // When a model is present it still runs on the rule-based floor:
                // adversarial text can move any model's answer.
                var model = DecisionModelResolver.resolve(environment);
                if (model.isPresent()) {
                    yield new CompositeInjectionClassifier(List.of(
                            new RuleBasedInjectionClassifier(),
                            new LlmClassifierInjectionClassifier(model.get(),
                                    LlmClassifierInjectionClassifier.DEFAULT_TIMEOUT,
                                    LlmClassifierInjectionClassifier.DEFAULT_INJECTED_AT,
                                    LlmClassifierInjectionClassifier.DEFAULT_SAFE_BELOW)));
                }
                logger.warn("RAG injection-safety tier LLM_CLASSIFIER requested but no DecisionModel "
                        + "can answer now (only the demo runtime is available, and no registered "
                        + "DecisionModel reports itself available) — downgrading to RULE_BASED so "
                        + "retrieval stays fail-closed. The downgrade is kept until "
                        + "InjectionClassifierResolver.reset(), even if a registered DecisionModel "
                        + "becomes available later. Configure a reachable model (LLM_MODE=local or an "
                        + "API key), make a registered DecisionModel available (its key, its endpoint), "
                        + "or set the tier to RULE_BASED to silence this warning.");
                yield new RuleBasedInjectionClassifier();
            }
        };
    }

    /** The first registered classifier of {@code tier} that is not a built-in one; the built-ins are not instantiated. */
    private static Optional<InjectionClassifier> findRegistered(InjectionClassifier.Tier tier) {
        var providers = ServiceLoader.load(InjectionClassifier.class).stream().iterator();
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

    /** True when a dedicated impl exists for this tier (not the rule-based fallback). */
    public static boolean hasNativeImpl(InjectionClassifier.Tier tier) {
        if (tier == InjectionClassifier.Tier.RULE_BASED) return true;
        for (var candidate : ServiceLoader.load(InjectionClassifier.class)) {
            if (candidate.tier() == tier) return true;
        }
        return false;
    }

    /** Whether {@code environment} has cached classifiers: test hook for the release on close. */
    static boolean caches(AiEnvironment environment) {
        return CACHES.containsKey(environment);
    }

    /**
     * Testing / reload hook — clears the cache so a new tier impl can be
     * picked up: every application's classifiers, the registered impls and
     * the {@link DecisionModelResolver} cache.
     */
    public static void reset() {
        CACHES.values().forEach(Map::clear);
        REGISTERED.clear();
        DecisionModelResolver.reset();
    }
}
