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
package org.atmosphere.coordinator.fleet;

/**
 * Per-dispatch decision seam for {@link AgentFleet}. Invoked before every
 * {@link AgentCall} leaves the coordinator — rewrite the call, deny it, or
 * let it proceed. Governance primitives (scope, rate limit, kill switch,
 * PII redaction) hook in through this SPI when applied at the
 * coordinator-to-specialist boundary rather than at the user-facing
 * {@code @Prompt} entry.
 *
 * <p>The v4 gist's Goal 2 (goal-hijacking prevention) is solved at the
 * {@code @Prompt} entry by {@code PolicyAdmissionGate}; this SPI is the
 * <b>agent-to-agent</b> variant — a coordinator dispatching to Research
 * with "write Python code" is the same risk as a user asking for it
 * directly. Scope check at dispatch catches that.</p>
 *
 * <p>Chain-of-responsibility: multiple interceptors compose through
 * {@link AgentFleet#withInterceptor(FleetInterceptor)} — they run in
 * registration order, and the first non-{@link Decision.Proceed}
 * short-circuits the chain.</p>
 */
@FunctionalInterface
public interface FleetInterceptor {

    /**
     * Evaluate a dispatch before it leaves the coordinator.
     *
     * @param call the proposed dispatch
     * @return a {@link Decision} — {@link Decision.Proceed} admits the call
     *         unchanged, {@link Decision.Rewrite} forwards a rewritten call,
     *         {@link Decision.Deny} replaces the dispatch with a synthetic
     *         failed {@link AgentResult}
     */
    Decision before(AgentCall call);

    /** Decision returned from {@link #before(AgentCall)}. */
    sealed interface Decision {

        /** Admit the call as-is. */
        record Proceed() implements Decision { }

        /**
         * Admit but rewrite (e.g. strip PII from args, clamp a numeric field,
         * redirect to a redacted skill).
         */
        record Rewrite(AgentCall modifiedCall) implements Decision {
            public Rewrite {
                if (modifiedCall == null) {
                    throw new IllegalArgumentException("modifiedCall must not be null");
                }
            }
        }

        /**
         * Deny the dispatch. The fleet skips the call and returns a synthetic
         * {@link AgentResult} with {@code success=false} and the configured
         * reason as the text. The coordinator's journaled event stream still
         * records the denied dispatch for audit.
         */
        record Deny(String reason) implements Decision {
            public Deny {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("reason must not be null or blank");
                }
            }
        }

        static Decision proceed() { return new Proceed(); }
        static Decision rewrite(AgentCall modifiedCall) { return new Rewrite(modifiedCall); }
        static Decision deny(String reason) { return new Deny(reason); }
    }
}
