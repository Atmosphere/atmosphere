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

/**
 * Declarative governance-policy SPI.
 *
 * <p>A {@code GovernancePolicy} pairs a stable identity (name, source, version) with
 * an evaluation method that maps a {@link PolicyContext} to a {@link PolicyDecision}.
 * Policies can be authored in code or produced by a {@link PolicyParser} implementation
 * (YAML, Rego, Cedar).</p>
 *
 * <p>Relationship with {@link org.atmosphere.ai.AiGuardrail}: both SPIs are designed to
 * land at the same {@code AiPipeline} admission seam. A {@code GovernancePolicy} carries
 * the artifact metadata operators want on the audit trail — name, source-of-truth URI,
 * version — that a bare {@code AiGuardrail} does not. The two SPIs are designed to
 * interop via adapters (added in a follow-up wiring commit); the declarative layer is
 * strictly additive.</p>
 *
 * <p>Implementations MUST be side-effect-free in {@link #evaluate(PolicyContext)}
 * except for metrics/logging. They MUST tolerate concurrent invocation — the pipeline
 * applies policies on the caller thread (virtual or platform) and a long-lived policy
 * instance is shared across all requests.</p>
 *
 * <p>Discovery: the same {@link org.atmosphere.ai.AiGuardrail#GUARDRAILS_PROPERTY}
 * auto-wiring contract applies — Spring / Quarkus / CDI auto-configuration will bridge
 * {@code GovernancePolicy} beans into the pipeline's policy list. A ServiceLoader
 * entry for {@code org.atmosphere.ai.governance.GovernancePolicy} is honored for
 * framework-less deployments.</p>
 */
public interface GovernancePolicy {

    /**
     * Framework-property key under which a {@code List<GovernancePolicy>} can be
     * bridged by Spring / Quarkus / CDI auto-configuration. Mirrors the
     * {@link org.atmosphere.ai.AiGuardrail#GUARDRAILS_PROPERTY} pattern so
     * framework auto-configurers wire the policy list alongside the guardrail list
     * on the same framework-scoped property bag.
     */
    String POLICIES_PROPERTY = "org.atmosphere.ai.governance.policies";

    /**
     * Stable identifier for this policy instance. MUST be unique within a single
     * pipeline — the admin console and audit trail use this to pin decisions to
     * the policy that produced them.
     */
    String name();

    /**
     * Source-of-truth URI for this policy. Opaque string, rendered on the admin
     * console as "where this came from". Conventional forms:
     * <ul>
     *   <li>{@code yaml:/etc/atmosphere/policies.yaml} — file-backed YAML</li>
     *   <li>{@code classpath:atmosphere-policies.yaml} — JAR-bundled YAML</li>
     *   <li>{@code code:fully.qualified.ClassName} — programmatic instantiation</li>
     *   <li>{@code rego:...} / {@code cedar:...} — external policy engines</li>
     * </ul>
     */
    String source();

    /**
     * Free-form version string. Conventional choices: semver ({@code "1.2.0"}),
     * ISO-8601 date ({@code "2026-04-21"}), or content hash ({@code "sha256:…"}).
     * Audit consumers read this alongside {@link #name()} to detect policy drift.
     */
    String version();

    /**
     * Evaluate this policy against the given context. Returning {@link PolicyDecision#admit()}
     * is the equivalent of "this policy does not apply here" — the pipeline moves on to the
     * next policy. Returning {@link PolicyDecision.Transform} rewrites the request for
     * subsequent policies; returning {@link PolicyDecision.Deny} terminates the turn.
     *
     * <p>Implementations MUST NOT throw. Exceptions bubble up to the pipeline as a
     * {@code fail-closed} signal and the turn is denied — match the semantics of
     * {@code AiGuardrail.inspectRequest} error handling.</p>
     */
    PolicyDecision evaluate(PolicyContext context);
}
