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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Builds a {@link ScopePolicy} from an {@link AgentScope} annotation declared on
 * an agent-bearing class (a {@code @AiEndpoint} or a {@code @Coordinator}). Shared
 * so every dispatch entry point installs scope confinement the same way and a
 * {@code @Coordinator} admits against the same chain as a plain
 * {@code @AiEndpoint} (Correctness Invariant #7, Mode Parity).
 */
public final class ScopePolicyBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ScopePolicyBuilder.class);

    private ScopePolicyBuilder() {
    }

    /**
     * Build a {@link ScopePolicy} from the {@link AgentScope} annotation on an
     * agent-bearing class. Returns {@link Optional#empty()} when the annotation
     * is invalid (e.g. {@code unrestricted = false} with a blank purpose) and
     * logs the reason — the agent keeps working without scope enforcement, which
     * is the "refuse to break at startup" behaviour callers expect; the
     * sample-hygiene CI lint catches this class of misconfiguration before it
     * ships.
     *
     * @param annotation the {@code @AgentScope} declared on {@code agentClass}
     * @param agentClass the annotated {@code @AiEndpoint} / {@code @Coordinator} class
     * @param path       the registration path of the agent (used for log context)
     * @return the resolved policy, or {@link Optional#empty()} when the
     *         annotation is invalid
     */
    public static Optional<ScopePolicy> build(AgentScope annotation,
                                              Class<?> agentClass,
                                              String path) {
        try {
            var config = ScopeConfig.fromAnnotation(annotation);
            var guardrail = ScopeGuardrailResolver.resolve(config.tier());
            if (guardrail.tier() != config.tier()) {
                logger.warn("No {} ScopeGuardrail impl on the classpath for {} — "
                                + "falling back to rule-based tier; install atmosphere-ai-scope-<tier> for the "
                                + "intended behaviour", config.tier(), path);
            }
            var name = "scope::" + agentClass.getSimpleName();
            var source = "annotation:" + agentClass.getName();
            return Optional.of(new ScopePolicy(name, source, "1.0",
                    // rebuild config against the guardrail we actually resolved
                    // so we don't advertise EMBEDDING_SIMILARITY when only the
                    // RULE_BASED fallback is installed
                    new ScopeConfig(config.purpose(), config.forbiddenTopics(),
                            config.onBreach(), config.redirectMessage(),
                            guardrail.tier(), config.similarityThreshold(),
                            config.postResponseCheck(), config.unrestricted(),
                            config.justification()),
                    guardrail));
        } catch (IllegalArgumentException e) {
            logger.error("Invalid @AgentScope on {} ({}) — agent will run WITHOUT scope "
                    + "enforcement; fix the annotation or add unrestricted = true with a justification",
                    agentClass.getName(), path, e);
            return Optional.empty();
        }
    }
}
