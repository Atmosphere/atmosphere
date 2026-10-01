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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Resolves the {@link DecisionModel} to use, in order:
 * <ol>
 *   <li>the available {@code META-INF/services/org.atmosphere.ai.decision.DecisionModel}
 *       registration with the highest {@link DecisionModel#priority()};</li>
 *   <li>otherwise a {@link RuntimeDecisionModel} over the resolved
 *       {@link org.atmosphere.ai.AgentRuntime}, when it is available — that is,
 *       when the runtime is not the {@code DemoAgentRuntime} canned fallback;</li>
 *   <li>otherwise empty: no model can answer.</li>
 * </ol>
 *
 * <p>The fallback {@link RuntimeDecisionModel} is one instance shared by every
 * consumer that resolves through here (the LLM injection, scope and moderation
 * tiers, and an {@link org.atmosphere.ai.intent.IntentRouting} with no decision
 * model of its own, which asks one question per admitted request), so its
 * concurrency bound is shared too. It allows
 * {@link RuntimeDecisionModel#DEFAULT_MAX_CONCURRENCY} questions in flight unless
 * the {@value #MAX_CONCURRENCY_PROPERTY} JVM system property sets another
 * positive integer; the property is read when the fallback is built, the first
 * time a resolution finds it (and again after {@link #reset()}). A registered
 * {@link DecisionModel} sizes itself.</p>
 *
 * <p>Only a non-empty result is cached. Resolution can run before the
 * application's {@link org.atmosphere.ai.AiConfig} is installed, when only the
 * demo fallback is available; caching that empty answer would pin it for the
 * life of the JVM (the same rule {@link org.atmosphere.ai.EmbeddingRuntimeResolver}
 * follows).</p>
 */
public final class DecisionModelResolver {

    private static final Logger logger = LoggerFactory.getLogger(DecisionModelResolver.class);

    /**
     * JVM system property: questions in flight on the fallback
     * {@link RuntimeDecisionModel}, a positive integer. Unset, or not a positive
     * integer (logged), means {@link RuntimeDecisionModel#DEFAULT_MAX_CONCURRENCY}.
     */
    public static final String MAX_CONCURRENCY_PROPERTY = "org.atmosphere.ai.decision.max-concurrency";

    /** Bound on broken provider entries skipped in one scan. */
    private static final int MAX_LOAD_ERRORS = 64;

    private static volatile DecisionModel cached;

    private DecisionModelResolver() {
    }

    /** The decision model to use, or empty when none can answer. */
    public static Optional<DecisionModel> resolve() {
        var model = cached;
        if (model != null) {
            return Optional.of(model);
        }
        synchronized (DecisionModelResolver.class) {
            model = cached;
            if (model != null) {
                return Optional.of(model);
            }
            var found = scan();
            found.ifPresent(m -> {
                cached = m;
                logger.info("DecisionModel resolved: {}", m.name());
            });
            return found;
        }
    }

    /** Test hook: forget the cached model so the next {@link #resolve()} rescans. */
    public static void reset() {
        cached = null;
    }

    private static Optional<DecisionModel> scan() {
        DecisionModel best = null;
        var iterator = ServiceLoader.load(DecisionModel.class).iterator();
        var loadErrors = 0;
        while (loadErrors <= MAX_LOAD_ERRORS) {
            DecisionModel candidate;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                candidate = iterator.next();
            } catch (ServiceConfigurationError e) {
                // ServiceLoader keeps going after a broken provider.
                logger.debug("Skipping a DecisionModel provider that failed to load", e);
                loadErrors++;
                continue;
            }
            try {
                if (candidate.isAvailable() && (best == null || candidate.priority() > best.priority())) {
                    best = candidate;
                }
            } catch (RuntimeException e) {
                logger.debug("Skipping DecisionModel {}: availability check threw",
                        candidate.getClass().getName(), e);
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        var fallback = new RuntimeDecisionModel(AgentRuntimeResolver.resolve(), fallbackMaxConcurrency(),
                RuntimeDecisionModel.DEFAULT_MIN_OBSERVED_MASS);
        try {
            if (fallback.isAvailable()) {
                return Optional.of(fallback);
            }
        } catch (RuntimeException e) {
            logger.debug("Skipping {}: availability check threw", fallback.name(), e);
        }
        return Optional.empty();
    }

    /** The fallback's concurrency: {@value #MAX_CONCURRENCY_PROPERTY}, or the default. */
    static int fallbackMaxConcurrency() {
        var configured = System.getProperty(MAX_CONCURRENCY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return RuntimeDecisionModel.DEFAULT_MAX_CONCURRENCY;
        }
        try {
            var value = Integer.parseInt(configured.trim());
            if (value >= 1) {
                return value;
            }
        } catch (NumberFormatException e) {
            logger.debug("{} is not an integer", MAX_CONCURRENCY_PROPERTY, e);
        }
        logger.warn("Ignoring {}={}: not a positive integer; using {}", MAX_CONCURRENCY_PROPERTY,
                configured, RuntimeDecisionModel.DEFAULT_MAX_CONCURRENCY);
        return RuntimeDecisionModel.DEFAULT_MAX_CONCURRENCY;
    }
}
