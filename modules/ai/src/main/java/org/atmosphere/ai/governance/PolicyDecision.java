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

/**
 * Decision returned from {@link GovernancePolicy#evaluate(PolicyContext)}.
 *
 * <p>The three admission cases — {@link Admit}, {@link Transform}, {@link Deny} — mirror
 * {@code AiGuardrail.GuardrailResult} intentionally, so the two SPIs share the same
 * admission-path semantics and an adapter between them is straightforward (see
 * {@link GuardrailAsPolicy} / {@link PolicyAsGuardrail} in the same package).</p>
 *
 * <p>{@link Prefer} is a fourth, <em>advisory</em> case with no {@code GuardrailResult}
 * counterpart: it admits the turn unchanged (like {@link Admit}) but records that a
 * different, preferred path exists — the "soft governance" tier that lets a policy
 * express "scoped access is preferred over standing access" without a hard {@link Deny}.
 * The advisory is recorded on the observability path and can be fed back to the agent on
 * a later turn (see {@code GovernanceFeedbackInterceptor}); it never blocks or rewrites,
 * so admission-flow call sites treat {@code Prefer} exactly like {@code Admit}.</p>
 *
 * <p>Vocabulary choice: {@code admit}/{@code deny} matches OPA/Rego and the MS Agent
 * Governance Toolkit at the evaluate-decision level. Interop is at the vocabulary
 * and SPI-shape level, not the YAML-artifact level — see
 * {@link GovernancePolicy} for a breakdown of what does and doesn't line up with
 * Microsoft's toolkit. {@code Prefer} is an Atmosphere-native extension with no MS
 * counterpart, so it is never produced by the MS-rules bridge. The imperative
 * {@code pass}/{@code block} vocabulary is preserved on {@code AiGuardrail} because
 * existing application code and tests reference it.</p>
 */
public sealed interface PolicyDecision {

    /** The policy admits the current context with no modification. */
    record Admit() implements PolicyDecision { }

    /**
     * The policy admits the current context but rewrites the request (e.g. PII
     * redaction, scope-confinement system-prompt injection). Only meaningful in
     * {@link PolicyContext.Phase#PRE_ADMISSION}; the pipeline ignores {@code Transform}
     * decisions returned from the post-response path and logs a warning.
     */
    record Transform(AiRequest modifiedRequest) implements PolicyDecision {
        public Transform {
            if (modifiedRequest == null) {
                throw new IllegalArgumentException("modifiedRequest must not be null");
            }
        }
    }

    /**
     * The policy denies the current context. The pipeline terminates the turn with
     * a {@link SecurityException} carrying {@link #reason()} and records the denial
     * on the observability path.
     */
    record Deny(String reason) implements PolicyDecision {
        public Deny {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be null or blank");
            }
        }
    }

    /**
     * The policy admits the current context unchanged but records that a different,
     * <em>preferred</em> path exists — the "soft governance" tier between {@link Admit}
     * (no opinion) and {@link Deny} (hard block). Neither terminal nor mutating: the turn
     * proceeds exactly as with {@link Admit}, and admission-flow call sites treat the two
     * identically. The distinction is observability-only — the decision is recorded with
     * {@code decision="prefer"}, its {@link #reason()}, and the {@link #preferred()}
     * alternative, so it can be surfaced in the admin decision log and fed back to the
     * agent on a later turn (see {@code GovernanceFeedbackInterceptor}).
     *
     * <p>Example: a request proposing a standing-admin grant is admitted, but the policy
     * records {@code preferred="request a scoped, time-boxed credential for the single
     * function"}, {@code reason="standing admin grants violate least-privilege for this
     * ticket type"}.</p>
     */
    record Prefer(String preferred, String reason) implements PolicyDecision {
        public Prefer {
            if (preferred == null || preferred.isBlank()) {
                throw new IllegalArgumentException("preferred must not be null or blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be null or blank");
            }
        }
    }

    /** Shorthand for {@code new Admit()}. */
    static PolicyDecision admit() {
        return new Admit();
    }

    /** Shorthand for {@code new Transform(modifiedRequest)}. */
    static PolicyDecision transform(AiRequest modifiedRequest) {
        return new Transform(modifiedRequest);
    }

    /** Shorthand for {@code new Deny(reason)}. */
    static PolicyDecision deny(String reason) {
        return new Deny(reason);
    }

    /** Shorthand for {@code new Prefer(preferred, reason)}. */
    static PolicyDecision prefer(String preferred, String reason) {
        return new Prefer(preferred, reason);
    }
}
