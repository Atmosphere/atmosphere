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
 * declarative policies land on the existing {@code AiPipeline} admission seam
 * before native pipeline wiring arrives.
 *
 * <p>On {@link AiGuardrail#inspectRequest(AiRequest)} the adapter builds a
 * {@link PolicyContext.Phase#PRE_ADMISSION} context and maps the returned
 * {@link PolicyDecision} to a {@link AiGuardrail.GuardrailResult}. On
 * {@link AiGuardrail#inspectResponse(String)} the adapter builds a
 * {@link PolicyContext.Phase#POST_RESPONSE} context around a placeholder
 * request — the guardrail response path does not carry the request object, so
 * the adapter reconstructs an empty {@link AiRequest} that policies reading
 * only {@code accumulatedResponse} handle correctly. A policy that inspects
 * request fields from the post-response context will see an empty message —
 * intended, because the existing {@code AiGuardrail} response SPI does not
 * thread the request through.</p>
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
        var decision = policy.evaluate(PolicyContext.preAdmission(request));
        return switch (decision) {
            case PolicyDecision.Admit ignored -> GuardrailResult.pass();
            case PolicyDecision.Transform transform -> GuardrailResult.modify(transform.modifiedRequest());
            case PolicyDecision.Deny deny -> GuardrailResult.block(deny.reason());
        };
    }

    @Override
    public GuardrailResult inspectResponse(String accumulatedResponse) {
        // The existing guardrail response SPI does not pass the request through;
        // build a placeholder so policies that read only accumulated text keep
        // working. Policies that require the original request on the response
        // path will observe an empty placeholder — intentional, documented on
        // the class.
        var decision = policy.evaluate(
                PolicyContext.postResponse(new AiRequest(""), accumulatedResponse));
        return switch (decision) {
            case PolicyDecision.Admit ignored -> GuardrailResult.pass();
            case PolicyDecision.Transform ignored -> {
                logger.warn("Policy {} returned Transform on POST_RESPONSE; "
                        + "ignored — guardrail response SPI cannot rewrite streamed text",
                        policy.name());
                yield GuardrailResult.pass();
            }
            case PolicyDecision.Deny deny -> GuardrailResult.block(deny.reason());
        };
    }
}
