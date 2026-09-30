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
 * <p>Only a non-empty result is cached. Resolution can run before the
 * application's {@link org.atmosphere.ai.AiConfig} is installed, when only the
 * demo fallback is available; caching that empty answer would pin it for the
 * life of the JVM (the same rule {@link org.atmosphere.ai.EmbeddingRuntimeResolver}
 * follows).</p>
 */
public final class DecisionModelResolver {

    private static final Logger logger = LoggerFactory.getLogger(DecisionModelResolver.class);

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
        var fallback = new RuntimeDecisionModel(AgentRuntimeResolver.resolve());
        try {
            if (fallback.isAvailable()) {
                return Optional.of(fallback);
            }
        } catch (RuntimeException e) {
            logger.debug("Skipping {}: availability check threw", fallback.name(), e);
        }
        return Optional.empty();
    }
}
