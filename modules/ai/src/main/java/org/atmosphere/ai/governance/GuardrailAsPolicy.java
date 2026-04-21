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

/**
 * Adapter that exposes any {@link AiGuardrail} as a {@link GovernancePolicy} so
 * existing guardrail implementations flow through the declarative policy plane
 * without changing their source.
 *
 * <p>Default identity is derived from the guardrail's class:</p>
 * <ul>
 *   <li>{@link #name()} — {@link Class#getSimpleName()}</li>
 *   <li>{@link #source()} — {@code "code:<fully-qualified-class-name>"}</li>
 *   <li>{@link #version()} — {@code "embedded"}</li>
 * </ul>
 *
 * <p>Callers that need explicit identity (for audit-trail pinning across
 * deployments) pass their own values via
 * {@link #GuardrailAsPolicy(AiGuardrail, String, String, String)}.</p>
 */
public final class GuardrailAsPolicy implements GovernancePolicy {

    private final AiGuardrail guardrail;
    private final String name;
    private final String source;
    private final String version;

    /**
     * Wrap a guardrail with identity derived from its class.
     */
    public GuardrailAsPolicy(AiGuardrail guardrail) {
        this(requireNonNull(guardrail),
                guardrail.getClass().getSimpleName(),
                "code:" + guardrail.getClass().getName(),
                "embedded");
    }

    private static AiGuardrail requireNonNull(AiGuardrail guardrail) {
        if (guardrail == null) {
            throw new IllegalArgumentException("guardrail must not be null");
        }
        return guardrail;
    }

    /**
     * Wrap a guardrail with explicit identity — useful when the same guardrail
     * class is instantiated multiple times with different configuration and
     * the audit trail needs to tell them apart.
     */
    public GuardrailAsPolicy(AiGuardrail guardrail, String name, String source, String version) {
        if (guardrail == null) {
            throw new IllegalArgumentException("guardrail must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be null or blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be null or blank");
        }
        this.guardrail = guardrail;
        this.name = name;
        this.source = source;
        this.version = version;
    }

    /** Expose the wrapped guardrail (inspection / testing / unwrap in pipeline). */
    public AiGuardrail guardrail() {
        return guardrail;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var result = switch (context.phase()) {
            case PRE_ADMISSION -> guardrail.inspectRequest(context.request());
            case POST_RESPONSE -> guardrail.inspectResponse(context.accumulatedResponse());
        };
        return switch (result) {
            case AiGuardrail.GuardrailResult.Pass ignored -> PolicyDecision.admit();
            case AiGuardrail.GuardrailResult.Modify modify -> PolicyDecision.transform(modify.modifiedRequest());
            case AiGuardrail.GuardrailResult.Block block -> PolicyDecision.deny(block.reason());
        };
    }
}
