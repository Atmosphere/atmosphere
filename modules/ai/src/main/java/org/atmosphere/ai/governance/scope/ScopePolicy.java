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

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GovernancePolicy} that delegates scope classification to a
 * {@link ScopeGuardrail} chosen by {@link ScopeConfig#tier()}, then maps
 * the tier's decision to admission semantics per
 * {@link AgentScope.Breach}.
 *
 * <p>Maps:</p>
 * <ul>
 *   <li>{@link ScopeGuardrail.Outcome#IN_SCOPE} → {@link PolicyDecision#admit()}</li>
 *   <li>{@link ScopeGuardrail.Outcome#OUT_OF_SCOPE} → per {@link AgentScope.Breach}:
 *     <ul>
 *       <li>{@code POLITE_REDIRECT} / {@code CUSTOM_MESSAGE} → {@link PolicyDecision.Transform}
 *           that rewrites the user message so downstream handlers can surface
 *           the redirect text as the response (the framework-provided
 *           {@link org.atmosphere.ai.governance.PolicyAdmissionGate} honours
 *           this; direct {@code AiPipeline.execute} callers see it as a
 *           rewritten request to the runtime, which then stays on-topic)</li>
 *       <li>{@code DENY} → {@link PolicyDecision.Deny} with the breach reason</li>
 *     </ul>
 *   </li>
 *   <li>{@link ScopeGuardrail.Outcome#ERROR} → {@link PolicyDecision.Deny} (fail-closed)</li>
 * </ul>
 */
public final class ScopePolicy implements GovernancePolicy {

    private static final Logger logger = LoggerFactory.getLogger(ScopePolicy.class);

    /** Metadata key on {@link AiRequest#metadata()} carrying the breach-redirect message. */
    public static final String REDIRECT_METADATA_KEY = "atmosphere.scope.redirect";

    private final String name;
    private final String source;
    private final String version;
    private final ScopeConfig config;
    private final ScopeGuardrail guardrail;

    public ScopePolicy(String name, String source, String version,
                       ScopeConfig config, ScopeGuardrail guardrail) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (guardrail == null) {
            throw new IllegalArgumentException("guardrail must not be null");
        }
        if (guardrail.tier() != config.tier()) {
            throw new IllegalArgumentException(
                    "guardrail tier " + guardrail.tier() + " does not match config tier " + config.tier());
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.config = config;
        this.guardrail = guardrail;
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    /** Exposed so admin surfaces can render the configured scope. */
    public ScopeConfig config() {
        return config;
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (context.phase() != PolicyContext.Phase.PRE_ADMISSION) {
            // Post-response check is handled separately by a ScopePostResponseCheck
            // wrapper when AgentScope.postResponseCheck() is true — for now, admit.
            return PolicyDecision.admit();
        }
        if (config.unrestricted()) {
            return PolicyDecision.admit();
        }

        ScopeGuardrail.Decision decision;
        try {
            decision = guardrail.evaluate(context.request(), config);
        } catch (RuntimeException e) {
            logger.error("ScopeGuardrail {} threw during evaluate — fail-closed",
                    guardrail.getClass().getSimpleName(), e);
            return PolicyDecision.deny("scope check failed: " + e.getMessage());
        }

        return switch (decision.outcome()) {
            case IN_SCOPE -> PolicyDecision.admit();
            case ERROR -> PolicyDecision.deny("scope check errored: " + decision.reason());
            case OUT_OF_SCOPE -> breachDecision(context.request(), decision);
        };
    }

    private PolicyDecision breachDecision(AiRequest original, ScopeGuardrail.Decision decision) {
        var reason = decision.reason().isEmpty()
                ? "request falls outside endpoint scope"
                : decision.reason();
        logger.warn("Scope breach on policy '{}' (tier={}, similarity={}): {}",
                name, config.tier(), decision.similarity(), reason);
        return switch (config.onBreach()) {
            case DENY -> PolicyDecision.deny(reason);
            case POLITE_REDIRECT, CUSTOM_MESSAGE ->
                    PolicyDecision.transform(rewriteForRedirect(original));
        };
    }

    /**
     * Rewrite the request so downstream consumers (pipeline runtimes,
     * {@link org.atmosphere.ai.governance.PolicyAdmissionGate}) render the
     * redirect message instead of the original user prompt. The redirect
     * text is also tagged onto request metadata so callers that bypass
     * LLM dispatch (demo responders) can surface it directly.
     */
    private AiRequest rewriteForRedirect(AiRequest original) {
        var newMetadata = new java.util.LinkedHashMap<String, Object>(
                original.metadata() == null ? java.util.Map.of() : original.metadata());
        newMetadata.put(REDIRECT_METADATA_KEY, config.redirectMessage());
        return new AiRequest(
                config.redirectMessage(),
                original.systemPrompt(),
                original.model(),
                original.userId(),
                original.sessionId(),
                original.agentId(),
                original.conversationId(),
                java.util.Map.copyOf(newMetadata),
                original.history());
    }
}
