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

import org.atmosphere.ai.ContextProvider;

import java.util.List;

/**
 * Layered classifier that runs a cheap {@link RuleBasedInjectionClassifier}
 * <em>floor</em> ahead of a higher-recall tier (embedding-similarity or
 * LLM-classifier). The first layer to return {@link Outcome#INJECTED} wins, so
 * the canonical injection vectors the rule-based probes catch are dropped even
 * when the higher tier's runtime is weak (e.g. a no-key fallback model) or
 * returns an ambiguous verdict. This is defense in depth: the higher tier only
 * ever <em>adds</em> recall, it can never subtract the rule-based floor's
 * coverage — which is what keeps the screen from silently failing open.
 *
 * <p>Evaluation semantics across layers, in order:</p>
 * <ul>
 *   <li>any layer returns {@link Outcome#INJECTED} → return it immediately;</li>
 *   <li>otherwise, if any layer returned {@link Outcome#ERROR} → return the
 *       first error (the wrapping {@link SafetyContextProvider} then applies the
 *       fail-closed breach policy);</li>
 *   <li>otherwise all layers said SAFE → return the highest tier's SAFE
 *       decision (preserving its confidence telemetry).</li>
 * </ul>
 *
 * <p>{@link #tier()} reports the highest (last) layer's tier so discovery
 * surfaces advertise the tier actually doing the deepest check, with the
 * rule-based floor as an implementation detail.</p>
 */
final class CompositeInjectionClassifier implements InjectionClassifier {

    private final List<InjectionClassifier> layers;
    private final Tier reportedTier;

    /**
     * @param layers ordered cheapest-first; the last element's {@link #tier()}
     *               becomes this classifier's reported tier. Must be non-empty.
     */
    CompositeInjectionClassifier(List<InjectionClassifier> layers) {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("layers must be non-empty");
        }
        this.layers = List.copyOf(layers);
        this.reportedTier = this.layers.get(this.layers.size() - 1).tier();
    }

    @Override
    public Tier tier() {
        return reportedTier;
    }

    @Override
    public Decision evaluate(ContextProvider.Document document) {
        Decision firstError = null;
        Decision lastSafe = Decision.safe(1.0);
        for (var layer : layers) {
            var decision = layer.evaluate(document);
            switch (decision.outcome()) {
                case INJECTED -> {
                    return decision;
                }
                case ERROR -> {
                    if (firstError == null) {
                        firstError = decision;
                    }
                }
                case SAFE -> lastSafe = decision;
                default -> firstError = firstError != null
                        ? firstError
                        : Decision.error("unknown outcome " + decision.outcome());
            }
        }
        return firstError != null ? firstError : lastSafe;
    }
}
