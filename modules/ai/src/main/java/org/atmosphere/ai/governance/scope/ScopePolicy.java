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

    /**
     * Metadata key on {@link AiRequest#metadata()} carrying a per-request
     * {@link ScopeConfig}. When {@link org.atmosphere.ai.AiPipeline#execute}
     * finds this key, it installs a transient {@link ScopePolicy} ahead of
     * the endpoint-level policy chain so that interceptors (e.g., a path-aware
     * {@code RoomContextInterceptor}) can narrow scope per-request without
     * mutating the endpoint configuration. The endpoint's static
     * {@code @AgentScope} still applies; {@code unrestricted = true} on the
     * endpoint contributes nothing, so the per-request scope is the sole
     * enforcement on that path — the common pattern for multi-persona
     * endpoints where a single {@code @AgentScope} can't express the variance.
     */
    public static final String REQUEST_SCOPE_METADATA_KEY = "atmosphere.scope.request";

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
        if (config.unrestricted()) {
            return PolicyDecision.admit();
        }
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return evaluatePostResponse(context);
        }
        return evaluatePreAdmission(context);
    }

    private PolicyDecision evaluatePreAdmission(PolicyContext context) {
        ScopeGuardrail.Decision decision;
        try {
            decision = guardrail.evaluate(context.request(), config);
        } catch (RuntimeException e) {
            logger.error("ScopeGuardrail {} threw during pre-admission evaluate — fail-closed",
                    guardrail.getClass().getSimpleName(), e);
            return PolicyDecision.deny("scope check failed: " + e.getMessage());
        }
        return switch (decision.outcome()) {
            case IN_SCOPE -> PolicyDecision.admit();
            case ERROR -> PolicyDecision.deny("scope check errored: " + decision.reason());
            case OUT_OF_SCOPE -> breachDecision(context.request(), decision);
        };
    }

    /**
     * Classify the accumulated response text against the declared purpose.
     * Disabled by default — {@link ScopeConfig#postResponseCheck()} gates
     * this path because the extra classifier call costs latency and the
     * pre-admission gate catches most hijacking at zero additional cost.
     * Operators enable it for high-stakes scopes where a drifted response
     * is a compliance hit.
     *
     * <p>OUT_OF_SCOPE responses can only become {@link PolicyDecision#deny}
     * — streamed bytes already on the wire are not retroactively rewritable,
     * so {@link AgentScope.Breach#POLITE_REDIRECT} downgrades to Deny with a
     * prefix note. Errors fail-OPEN on the response path because bytes are
     * already flowing; the admin trail still records the error via
     * {@link org.atmosphere.ai.AiPipeline}'s guardrail-wrapping seam.</p>
     */
    private PolicyDecision evaluatePostResponse(PolicyContext context) {
        if (!config.postResponseCheck()) {
            return PolicyDecision.admit();
        }
        var response = context.accumulatedResponse();
        if (response == null || response.isBlank()) {
            return PolicyDecision.admit();
        }
        // Reuse the same classifier against the response text — the purpose
        // is symmetric, "is this text in scope?" works the same on request
        // or response text.
        var syntheticRequest = context.request().withMessage(response);
        ScopeGuardrail.Decision decision;
        try {
            decision = guardrail.evaluate(syntheticRequest, config);
        } catch (RuntimeException e) {
            logger.error("ScopeGuardrail {} threw during post-response evaluate — admitting "
                    + "(bytes already on the wire)", guardrail.getClass().getSimpleName(), e);
            return PolicyDecision.admit();
        }
        return switch (decision.outcome()) {
            case IN_SCOPE, ERROR -> PolicyDecision.admit();
            case OUT_OF_SCOPE -> {
                var reason = decision.reason().isEmpty()
                        ? "response drifted outside endpoint scope"
                        : decision.reason();
                logger.warn("Scope post-response breach on '{}' (tier={}, similarity={}): {}",
                        name, config.tier(), decision.similarity(), reason);
                yield PolicyDecision.deny("post-response: " + reason);
            }
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
