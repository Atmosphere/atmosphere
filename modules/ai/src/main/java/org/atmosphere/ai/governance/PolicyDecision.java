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
 * <p>The three cases mirror {@code AiGuardrail.GuardrailResult} intentionally — the
 * two SPIs share the same admission-path semantics so an adapter between them is
 * straightforward (see {@code GuardrailAdapter} / {@code PolicyAdapter} in the same
 * package, added in the wiring commit).</p>
 *
 * <p>Vocabulary choice: {@code admit}/{@code deny} matches OPA/Rego and MS Agent OS
 * governance surfaces, which is the interoperability target of Phase A. The imperative
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
}
