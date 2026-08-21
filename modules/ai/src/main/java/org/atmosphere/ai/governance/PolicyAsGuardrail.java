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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;

/**
 * Adapter that exposes a {@link GovernancePolicy} as an {@link AiGuardrail} so
 * declarative policies land on the existing {@code AiPipeline} admission seam.
 * Native pipeline wiring ships as {@code PolicyAdmissionGate}; this adapter
 * remains the bridge for applications wiring {@code AiGuardrail} beans.
 *
 * <p>On {@link AiGuardrail#inspectRequest(AiRequest)} the adapter builds a
 * {@link PolicyContext.Phase#PRE_ADMISSION} context and maps the returned
 * {@link PolicyDecision} to a {@link AiGuardrail.GuardrailResult}. On
 * {@link AiGuardrail#inspectResponse(AiRequest, String)} the adapter builds a
 * {@link PolicyContext.Phase#POST_RESPONSE} context around the originating
 * request, so identity-scoped output authorization keeps its subject
 * (userId / sessionId / agentId / conversationId). Only the legacy
 * {@link AiGuardrail#inspectResponse(String)} entry point — used by callers
 * with no request context — falls back to an empty placeholder request.</p>
 *
 * <p>{@link PolicyDecision.Transform} on the post-response path is mapped to
 * {@link AiGuardrail.GuardrailResult#pass()} and a warning is logged — the
 * guardrail response API cannot rewrite already-streamed text, so transform
 * is non-operational. Policies that need post-response transformation must
 * wait for the native {@code AiPipeline} wiring commit.</p>
 */
public final class PolicyAsGuardrail implements AiGuardrail {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(PolicyAsGuardrail.class);

    private final GovernancePolicy policy;

    public PolicyAsGuardrail(GovernancePolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.policy = policy;
    }

    /** Expose the wrapped policy for inspection / testing. */
    public GovernancePolicy policy() {
        return policy;
    }

    @Override
    public GuardrailResult inspectRequest(AiRequest request) {
        var ctx = PolicyContext.preAdmission(request);
        var startNs = System.nanoTime();
        try {
            var decision = policy.evaluate(ctx);
            var evalMs = (System.nanoTime() - startNs) / 1_000_000.0;
            return switch (decision) {
                case PolicyDecision.Admit ignored -> {
                    record(ctx, "admit", "", evalMs);
                    yield GuardrailResult.pass();
                }
                case PolicyDecision.Transform transform -> {
                    record(ctx, "transform", "request rewritten", evalMs);
                    yield GuardrailResult.modify(transform.modifiedRequest());
                }
                case PolicyDecision.Prefer prefer -> {
                    // Soft governance: admit unchanged, record the preferred path.
                    recordPrefer(ctx, prefer.preferred(), prefer.reason(), evalMs);
                    yield GuardrailResult.pass();
                }
                case PolicyDecision.Deny deny -> {
                    record(ctx, "deny", deny.reason(), evalMs);
                    yield GuardrailResult.block(deny.reason());
                }
            };
        } catch (RuntimeException e) {
            // Mirror the PolicyAdmissionGate / AiPipeline contract: a policy that
            // throws is recorded to the decision log before the exception
            // propagates (the caller fails closed). Without this, the Kafka /
            // Postgres audit sinks miss policy decisions on the @AiEndpoint path,
            // where installed policies arrive wrapped as PolicyAsGuardrail.
            record(ctx, "error", "evaluate threw: " + e.getMessage(),
                    (System.nanoTime() - startNs) / 1_000_000.0);
            throw e;
        }
    }

    @Override
    public GuardrailResult inspectResponse(String accumulatedResponse) {
        // Legacy text-only entry point: no request context is available, so
        // the policy observes an empty placeholder. Producers that hold the
        // originating request call the identity-aware overload below.
        return inspectResponse(new AiRequest(""), accumulatedResponse);
    }

    @Override
    public GuardrailResult inspectResponse(AiRequest originatingRequest,
                                           String accumulatedResponse) {
        // POST_RESPONSE evaluates against the originating request so
        // identity-scoped output authorization (e.g. AcsManifestPolicy's
        // userId/sessionId/agentId/conversationId snapshot) keeps its
        // subject at the output intervention point.
        var ctx = PolicyContext.postResponse(originatingRequest, accumulatedResponse);
        var startNs = System.nanoTime();
        try {
            var decision = policy.evaluate(ctx);
            var evalMs = (System.nanoTime() - startNs) / 1_000_000.0;
            return switch (decision) {
                case PolicyDecision.Admit ignored -> {
                    record(ctx, "admit", "", evalMs);
                    yield GuardrailResult.pass();
                }
                case PolicyDecision.Transform ignored -> {
                    record(ctx, "transform",
                            "ignored — guardrail response SPI cannot rewrite streamed text", evalMs);
                    logger.warn("Policy {} returned Transform on POST_RESPONSE; "
                            + "ignored — guardrail response SPI cannot rewrite streamed text",
                            policy.name());
                    yield GuardrailResult.pass();
                }
                case PolicyDecision.Prefer prefer -> {
                    // Advisory on the response path: recorded for the audit trail, but the
                    // response has already streamed so there is no next action to steer here.
                    recordPrefer(ctx, prefer.preferred(), prefer.reason(), evalMs);
                    yield GuardrailResult.pass();
                }
                case PolicyDecision.Deny deny -> {
                    record(ctx, "deny", deny.reason(), evalMs);
                    yield GuardrailResult.block(deny.reason());
                }
            };
        } catch (RuntimeException e) {
            record(ctx, "error", "evaluate threw: " + e.getMessage(),
                    (System.nanoTime() - startNs) / 1_000_000.0);
            throw e;
        }
    }

    /**
     * Record the policy decision to the installed {@link GovernanceDecisionLog}
     * so it reaches the admin decisions view and any registered persistent audit
     * sinks (Kafka / Postgres) — parity with {@link PolicyAdmissionGate} and
     * {@code AiPipeline}, which record every decision they make.
     */
    private void record(PolicyContext ctx, String decision, String reason, double evalMs) {
        GovernanceDecisionLog.installed().record(
                GovernanceDecisionLog.entry(policy, ctx, decision, reason, evalMs));
    }

    /**
     * Record a {@link PolicyDecision.Prefer} advisory, stamping the preferred
     * alternative into the audit snapshot so it can be fed back to the agent.
     */
    private void recordPrefer(PolicyContext ctx, String preferred, String reason, double evalMs) {
        GovernanceDecisionLog.installed().record(
                GovernanceDecisionLog.preferEntry(policy, ctx, preferred, reason, evalMs));
    }
}
