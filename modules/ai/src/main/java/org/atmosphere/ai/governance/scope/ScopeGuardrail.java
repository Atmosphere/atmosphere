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

/**
 * Strategy for deciding whether an {@link AiRequest} falls within an
 * endpoint's declared scope. One implementation per {@link AgentScope.Tier}:
 * {@link RuleBasedScopeGuardrail} (rule tier), embedding-similarity and
 * LLM-classifier tiers land in follow-up commits and plug in via
 * {@link java.util.ServiceLoader}.
 *
 * <p>Implementations MUST be thread-safe and MUST NOT throw — exceptions
 * are caught and treated as {@link Decision#ERROR} at the caller; the
 * policy-layer wrapper then surfaces them as {@code Deny} (fail-closed,
 * Correctness Invariant #2).</p>
 */
public interface ScopeGuardrail {

    /** Which tier this implementation handles; returned by {@link #tier()}. */
    AgentScope.Tier tier();

    /**
     * Classify the request against the configured scope.
     *
     * @param request incoming request (never {@code null})
     * @param config  resolved scope configuration (never {@code null})
     * @return {@link Decision} recording the verdict plus telemetry fields
     *         used by the policy-layer wrapper to build the breach message
     */
    Decision evaluate(AiRequest request, ScopeConfig config);

    /** Result of a scope check — classification plus audit fields. */
    record Decision(Outcome outcome, String reason, double similarity) {

        public Decision {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
            reason = reason == null ? "" : reason;
        }

        public static Decision inScope(double similarity) {
            return new Decision(Outcome.IN_SCOPE, "", similarity);
        }

        public static Decision outOfScope(String reason, double similarity) {
            return new Decision(Outcome.OUT_OF_SCOPE, reason, similarity);
        }

        public static Decision error(String reason) {
            return new Decision(Outcome.ERROR, reason, Double.NaN);
        }
    }

    enum Outcome {
        IN_SCOPE,
        OUT_OF_SCOPE,
        ERROR
    }
}
