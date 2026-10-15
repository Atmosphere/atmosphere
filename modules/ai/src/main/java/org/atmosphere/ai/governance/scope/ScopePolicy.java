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
import org.atmosphere.ai.governance.GovernanceMetricsHolder;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
 *           that rewrites the user message to the redirect text and tags it
 *           under {@link #REDIRECT_METADATA_KEY}. The dispatch paths
 *           ({@code AiStreamingSession.stream}, {@code AiPipeline.execute})
 *           send that text to the client verbatim and end the turn without
 *           calling the model; {@link org.atmosphere.ai.governance.PolicyAdmissionGate}
 *           callers see it as the rewritten request (see {@link #redirectText})</li>
 *       <li>{@code DENY} → {@link PolicyDecision.Deny} with the breach reason</li>
 *     </ul>
 *   </li>
 *   <li>{@link ScopeGuardrail.Outcome#ERROR} → {@link PolicyDecision.Deny} (fail-closed)</li>
 * </ul>
 *
 * <p>A policy has one or more <em>legs</em> ({@link #legs()}), each a
 * {@link ScopeConfig} classified by the guardrail of its own tier. A request
 * is admitted only when every leg admits it: legs run on the original
 * request, cheapest tier first, and the first leg that does not answer
 * {@code IN_SCOPE} decides the turn. This is how two explicit scope
 * declarations whose tiers or purposes cannot be ordered against each other
 * (a skill's {@code scopeTier} and a class {@link AgentScope}) are both
 * enforced by one policy, so the breach mode, the redirect text and the
 * prompt-hardening block stay single. The legs share their forbidden topics,
 * breach mode, redirect message and {@code postResponseCheck}; only the
 * purpose, tier and threshold vary per leg.</p>
 */
public final class ScopePolicy implements GovernancePolicy {

    private static final Logger logger = LoggerFactory.getLogger(ScopePolicy.class);

    /** Metadata key on {@link AiRequest#metadata()} carrying the breach-redirect message. */
    public static final String REDIRECT_METADATA_KEY = "atmosphere.scope.redirect";

    /**
     * The redirect text a scope breach tagged on {@code request}, the response
     * the client gets verbatim instead of a model call.
     *
     * @param request the request as an admission step left it, may be {@code null}
     * @return the redirect text, or empty when the request was not redirected
     */
    public static java.util.Optional<String> redirectText(AiRequest request) {
        if (request == null || request.metadata() == null) {
            return java.util.Optional.empty();
        }
        return request.metadata().get(REDIRECT_METADATA_KEY) instanceof String text
                ? java.util.Optional.of(text) : java.util.Optional.empty();
    }

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
    /** The legs, sorted by tier ordinal (cheapest first); never empty. */
    private final List<ScopeConfig> legs;
    /** {@code guardrails.get(i)} classifies {@code legs.get(i)}. */
    private final List<ScopeGuardrail> guardrails;
    /** The primary leg, {@code legs.get(0)}; carries the settings every leg shares. */
    private final ScopeConfig config;

    public ScopePolicy(String name, String source, String version,
                       ScopeConfig config, ScopeGuardrail guardrail) {
        this(name, source, version, Collections.singletonList(config),
                Collections.singletonList(guardrail));
    }

    /**
     * A policy that admits a request only when every leg admits it.
     *
     * @param name       the policy name
     * @param source     the policy source for the decision log
     * @param version    the policy version
     * @param legs       one {@link ScopeConfig} per leg, non-empty. With more
     *                   than one leg, none may be {@code unrestricted} and all
     *                   must agree on {@link ScopeConfig#forbiddenTopics()},
     *                   {@link ScopeConfig#onBreach()},
     *                   {@link ScopeConfig#redirectMessage()} and
     *                   {@link ScopeConfig#postResponseCheck()}
     * @param guardrails the guardrail of each leg, same size and order as
     *                   {@code legs}, each of its leg's tier
     * @throws IllegalArgumentException when an argument breaks these rules
     */
    public ScopePolicy(String name, String source, String version,
                       List<ScopeConfig> legs, List<ScopeGuardrail> guardrails) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (legs == null || legs.isEmpty()) {
            throw new IllegalArgumentException("legs must not be empty");
        }
        if (guardrails == null || guardrails.size() != legs.size()) {
            throw new IllegalArgumentException("guardrails must have one entry per leg ("
                    + legs.size() + "), got " + (guardrails == null ? "null" : guardrails.size()));
        }
        var single = legs.size() == 1;
        var pairs = new ArrayList<Leg>(legs.size());
        for (int i = 0; i < legs.size(); i++) {
            var leg = legs.get(i);
            var guardrail = guardrails.get(i);
            if (leg == null) {
                throw new IllegalArgumentException(single
                        ? "config must not be null" : "leg " + i + " must not be null");
            }
            if (guardrail == null) {
                throw new IllegalArgumentException(single
                        ? "guardrail must not be null" : "guardrail " + i + " must not be null");
            }
            if (guardrail.tier() != leg.tier()) {
                throw new IllegalArgumentException(
                        "guardrail tier " + guardrail.tier() + " does not match config tier " + leg.tier());
            }
            pairs.add(new Leg(leg, guardrail));
        }
        if (!single) {
            var first = legs.get(0);
            for (var leg : legs) {
                if (leg.unrestricted()) {
                    throw new IllegalArgumentException(
                            "an unrestricted scope cannot be one leg of a multi-leg policy");
                }
                if (!leg.forbiddenTopics().equals(first.forbiddenTopics())
                        || leg.onBreach() != first.onBreach()
                        || !leg.redirectMessage().equals(first.redirectMessage())
                        || leg.postResponseCheck() != first.postResponseCheck()) {
                    throw new IllegalArgumentException("the legs of a multi-leg policy must share "
                            + "forbiddenTopics, onBreach, redirectMessage and postResponseCheck");
                }
            }
        }
        // Stable: legs of the same tier keep the order the caller gave them.
        pairs.sort(Comparator.comparingInt(pair -> pair.config().tier().ordinal()));
        this.name = name;
        this.source = source;
        this.version = version;
        this.legs = pairs.stream().map(Leg::config).toList();
        this.guardrails = pairs.stream().map(Leg::guardrail).toList();
        this.config = this.legs.get(0);
    }

    /** A leg's config and the guardrail that classifies it, kept together while sorting. */
    private record Leg(ScopeConfig config, ScopeGuardrail guardrail) { }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    /**
     * The primary leg ({@code legs().get(0)}, the cheapest tier). It carries
     * every setting the legs share, but not the purpose, tier or threshold of
     * the other legs: read {@link #legs()} to render the whole scope.
     */
    public ScopeConfig config() {
        return config;
    }

    /**
     * Every leg of this policy, sorted by tier (cheapest first), so admin
     * surfaces can render the scope that is actually enforced.
     *
     * @return the unmodifiable, non-empty list of legs
     */
    public List<ScopeConfig> legs() {
        return legs;
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

    /**
     * Every leg classifies the original request, cheapest first. The first
     * leg that does not admit decides: OUT_OF_SCOPE is the breach, ERROR or a
     * throw denies (fail-closed). Later legs are not evaluated.
     */
    private PolicyDecision evaluatePreAdmission(PolicyContext context) {
        for (int i = 0; i < legs.size(); i++) {
            var leg = legs.get(i);
            var guardrail = guardrails.get(i);
            ScopeGuardrail.Decision decision;
            try {
                decision = guardrail.evaluate(context.request(), leg);
            } catch (RuntimeException e) {
                logger.error("ScopeGuardrail {} threw during pre-admission evaluate — fail-closed",
                        guardrail.getClass().getSimpleName(), e);
                GovernanceMetricsHolder.get().recordSimilarity(name, leg.tier(), "error", Double.NaN);
                return PolicyDecision.deny("scope check failed: " + e.getMessage());
            }
            var policyDecision = switch (decision.outcome()) {
                case IN_SCOPE -> PolicyDecision.admit();
                case ERROR -> PolicyDecision.deny("scope check errored: " + decision.reason());
                case OUT_OF_SCOPE -> breachDecision(context.request(), decision, leg);
            };
            GovernanceMetricsHolder.get().recordSimilarity(name, leg.tier(),
                    decisionLabel(policyDecision), decision.similarity());
            if (!(policyDecision instanceof PolicyDecision.Admit)) {
                return policyDecision;
            }
        }
        return PolicyDecision.admit();
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
     *
     * <p>Every leg classifies the response: the first OUT_OF_SCOPE leg denies;
     * a leg that errors or throws admits on its own account and the next leg
     * still runs.</p>
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
        for (int i = 0; i < legs.size(); i++) {
            var leg = legs.get(i);
            var guardrail = guardrails.get(i);
            ScopeGuardrail.Decision decision;
            try {
                decision = guardrail.evaluate(syntheticRequest, leg);
            } catch (RuntimeException e) {
                logger.error("ScopeGuardrail {} threw during post-response evaluate — admitting "
                        + "(bytes already on the wire)", guardrail.getClass().getSimpleName(), e);
                GovernanceMetricsHolder.get().recordSimilarity(name, leg.tier(), "error", Double.NaN);
                continue;
            }
            var policyDecision = switch (decision.outcome()) {
                case IN_SCOPE, ERROR -> PolicyDecision.admit();
                case OUT_OF_SCOPE -> {
                    var reason = decision.reason().isEmpty()
                            ? "response drifted outside endpoint scope"
                            : decision.reason();
                    logger.warn("Scope post-response breach on '{}' (tier={}, similarity={}): {}",
                            name, leg.tier(), decision.similarity(), reason);
                    yield PolicyDecision.deny("post-response: " + reason);
                }
            };
            GovernanceMetricsHolder.get().recordSimilarity(name, leg.tier(),
                    decisionLabel(policyDecision), decision.similarity());
            if (policyDecision instanceof PolicyDecision.Deny) {
                return policyDecision;
            }
        }
        return PolicyDecision.admit();
    }

    private static String decisionLabel(PolicyDecision decision) {
        return switch (decision) {
            case PolicyDecision.Admit ignored -> "admit";
            case PolicyDecision.Deny ignored -> "deny";
            case PolicyDecision.Transform ignored -> "transform";
            case PolicyDecision.Prefer ignored -> "prefer";
        };
    }

    private PolicyDecision breachDecision(AiRequest original, ScopeGuardrail.Decision decision,
                                          ScopeConfig leg) {
        var reason = decision.reason().isEmpty()
                ? "request falls outside endpoint scope"
                : decision.reason();
        logger.warn("Scope breach on policy '{}' (tier={}, similarity={}): {}",
                name, leg.tier(), decision.similarity(), reason);
        return switch (config.onBreach()) {
            case DENY -> PolicyDecision.deny(reason);
            case POLITE_REDIRECT, CUSTOM_MESSAGE ->
                    PolicyDecision.transform(rewriteForRedirect(original));
        };
    }

    /**
     * Rewrite the request to the redirect message in place of the original
     * user prompt, and tag the redirect text onto the request metadata: the
     * dispatch paths find it there ({@link #redirectText}) and send it to the
     * client verbatim, as do callers that admit by hand (demo responders).
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
