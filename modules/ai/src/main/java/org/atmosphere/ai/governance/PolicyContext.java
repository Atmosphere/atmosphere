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

import org.atmosphere.ai.AiRequest;

import java.util.Optional;

/**
 * Snapshot of the turn state handed to {@link GovernancePolicy#evaluate(PolicyContext)}.
 *
 * <p>Two shapes, one record:</p>
 * <ul>
 *   <li>{@link Phase#PRE_ADMISSION} — {@link #request()} is present, {@link #accumulatedResponse()}
 *       is empty. Policies may return {@link PolicyDecision.Transform} to rewrite the request
 *       (PII redaction, scope-confinement prompt hardening) or {@link PolicyDecision.Deny} to
 *       refuse the turn before any tokens are spent.</li>
 *   <li>{@link Phase#POST_RESPONSE} — {@link #accumulatedResponse()} is present with the
 *       text accumulated so far; {@link #request()} is the (possibly transformed) request
 *       that produced it. Only {@link PolicyDecision.Admit} and {@link PolicyDecision.Deny}
 *       are meaningful on this path — once bytes are on the wire, transformation is not
 *       retroactive.</li>
 * </ul>
 *
 * <p>The context is intentionally immutable — policies do not mutate it. A
 * {@link PolicyDecision.Transform} decision carries the replacement {@link AiRequest}
 * explicitly so the pipeline rebuilds a fresh context for the next policy in the chain.</p>
 */
public record PolicyContext(Phase phase, AiRequest request, String accumulatedResponse) {

    public PolicyContext {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        accumulatedResponse = accumulatedResponse == null ? "" : accumulatedResponse;
    }

    /** Build a pre-admission context (no response text yet). */
    public static PolicyContext preAdmission(AiRequest request) {
        return new PolicyContext(Phase.PRE_ADMISSION, request, "");
    }

    /** Build a post-response context with accumulated response text. */
    public static PolicyContext postResponse(AiRequest request, String accumulatedResponse) {
        return new PolicyContext(Phase.POST_RESPONSE, request, accumulatedResponse);
    }

    /** Convenience — {@link Optional} view of the response text for post-response policies. */
    public Optional<String> responseText() {
        return accumulatedResponse.isEmpty()
                ? Optional.empty()
                : Optional.of(accumulatedResponse);
    }

    /**
     * Pipeline stage at which the policy is being evaluated.
     *
     * <p>Policies that only care about one side can short-circuit the other in
     * {@link GovernancePolicy#evaluate(PolicyContext)} with a {@link PolicyDecision#admit()}.</p>
     */
    public enum Phase {
        /** Before the LLM call. {@link PolicyContext#request()} is the outgoing request. */
        PRE_ADMISSION,

        /** During / after streaming. {@link PolicyContext#accumulatedResponse()} carries text so far. */
        POST_RESPONSE
    }
}
