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
package org.atmosphere.ai.governance.compliance;

import java.util.List;
import java.util.Map;

/**
 * Atmosphere's self-assessment against regulatory / audit frameworks that
 * enterprise buyers routinely demand evidence for. Parallels the OWASP
 * Agentic self-assessment in {@code OwaspAgenticMatrix} — same
 * {@code Row}/{@code Evidence} shape, same "consumer grep + regression
 * test" discipline, different rows.
 *
 * <h2>Frameworks</h2>
 * <ul>
 *   <li><b>EU AI Act</b> — high-risk AI system requirements (human
 *       oversight, transparency, logging, robustness). Articles 9, 12,
 *       13, 14, 15, 17.</li>
 *   <li><b>HIPAA</b> — access control, audit logs, integrity,
 *       authentication, transmission security. §164.308, §164.312.</li>
 *   <li><b>SOC2</b> — Trust Services Criteria: logical/physical access,
 *       system operations, change management, risk mitigation. CC6.1,
 *       CC6.6, CC7.2, CC7.3.</li>
 * </ul>
 *
 * <p>This matrix <b>does not</b> claim Atmosphere is certified under any
 * of these frameworks — that is the operator's obligation at their
 * deployment. What the matrix documents is: for each relevant control,
 * WHICH Atmosphere primitive provides the technical evidence the
 * operator can point to in their audit.</p>
 *
 * <h2>CI gate</h2>
 * {@code ComplianceMatrixPinTest} resolves every {@link Evidence}
 * reference to a real {@link Class}; evidence drift fails the build
 * rather than rotting silently.
 */
public final class ComplianceMatrix {

    private ComplianceMatrix() { }

    /** Which framework a row belongs to. */
    public enum Framework {
        /** EU AI Act (Regulation (EU) 2024/1689). */
        EU_AI_ACT("EU AI Act (Regulation (EU) 2024/1689)"),
        /** US HIPAA Security Rule (45 CFR Part 164 Subpart C). */
        HIPAA("HIPAA Security Rule (45 CFR Part 164 Subpart C)"),
        /** SOC 2 Trust Services Criteria (AICPA). */
        SOC2("SOC 2 Trust Services Criteria (AICPA)");

        private final String displayName;
        Framework(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    /** Coverage bucket — same vocabulary as the OWASP matrix. */
    public enum Coverage { COVERED, PARTIAL, DESIGN, NOT_ADDRESSED }

    /** One entry in the framework matrix. */
    public record Row(String id, String title, String description,
                      Coverage coverage, List<Evidence> evidence, String notes) {
        public Row {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (coverage == null) {
                throw new IllegalArgumentException("coverage must not be null");
            }
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            notes = notes == null ? "" : notes;
        }
    }

    /** Evidence pointer (same shape as {@code OwaspAgenticMatrix.Evidence}). */
    public record Evidence(String evidenceClass, String testClass,
                           String consumerGrepPattern, String description) {
        public Evidence {
            if (evidenceClass == null || evidenceClass.isBlank()) {
                throw new IllegalArgumentException("evidenceClass must not be blank");
            }
            testClass = testClass == null ? "" : testClass;
            consumerGrepPattern = consumerGrepPattern == null ? "" : consumerGrepPattern;
            description = description == null ? "" : description;
        }
    }

    /** EU AI Act — 5 high-risk-system controls mapped to Atmosphere primitives. */
    public static final List<Row> EU_AI_ACT = List.of(
            new Row("EU-AIA-9", "Risk management system",
                    "Article 9 — identification, estimation, and mitigation of risks "
                            + "throughout the AI system lifecycle.",
                    Coverage.PARTIAL,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.owasp.OwaspAgenticMatrix",
                                    "org.atmosphere.ai.governance.owasp.OwaspMatrixPinTest",
                                    "OwaspAgenticMatrix",
                                    "OWASP Agentic Top-10 mapping supplies the risk inventory "
                                            + "that feeds the operator's Article 9 risk register")),
                    "Primitives exist to implement risk controls (scope, tool admission, drift, "
                            + "cost ceiling); the Article 9 process obligations (periodic review, "
                            + "documentation) are the operator's."),

            new Row("EU-AIA-12", "Automatic logging of events",
                    "Article 12 — logging of events throughout the AI system's lifecycle to "
                            + "facilitate post-market monitoring and audit.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.AuditEntry",
                                    "org.atmosphere.ai.governance.AuditSinkTest",
                                    "AuditEntry",
                                    "Every policy evaluation emits an AuditEntry (redaction-safe "
                                            + "context snapshot) to GovernanceDecisionLog"),
                            new Evidence("org.atmosphere.ai.governance.AuditSink",
                                    "org.atmosphere.ai.governance.AuditSinkTest",
                                    "AuditSink",
                                    "Persistent sink SPI — Kafka + JDBC reference modules ship "
                                            + "for long-term retention"),
                            new Evidence("org.atmosphere.coordinator.commitment.CommitmentRecord",
                                    "org.atmosphere.coordinator.commitment.CommitmentRecordTest",
                                    "CommitmentRecord",
                                    "Ed25519-signed dispatch records close the tamper-evidence "
                                            + "gap Article 12 specifically asks for")),
                    "Full coverage: every decision is logged, signing provides tamper-evidence, "
                            + "and downstream Kafka/Postgres sinks handle retention."),

            new Row("EU-AIA-13", "Transparency + information to deployers",
                    "Article 13 — information that enables deployers to interpret and use "
                            + "the AI system output correctly.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.annotation.AgentScope",
                                    "org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrailTest",
                                    "@AgentScope",
                                    "Declared purpose + forbidden topics + breach policy are "
                                            + "machine-readable from the annotation"),
                            new Evidence("org.atmosphere.ai.governance.owasp.OwaspAgenticMatrix",
                                    "org.atmosphere.ai.governance.owasp.OwaspMatrixPinTest",
                                    "OwaspAgenticMatrix",
                                    "Admin /api/admin/governance/owasp surface documents the "
                                            + "deployed controls to auditors")),
                    "Deployers read the admin console's Policies + OWASP tabs + the @AgentScope "
                            + "declarations to produce their Article 13 documentation."),

            new Row("EU-AIA-14", "Human oversight",
                    "Article 14 — AI systems must be designed for effective oversight by "
                            + "natural persons during use.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.annotation.RequiresApproval",
                                    "",
                                    "@RequiresApproval",
                                    "HITL approval gate on @AiTool methods — parks execution "
                                            + "until a human approves"),
                            new Evidence("org.atmosphere.ai.governance.PolicyAdmissionGate",
                                    "org.atmosphere.ai.governance.PolicyAdmissionGateToolCallTest",
                                    "PolicyAdmissionGate",
                                    "Admission seam where a human reviewer can intervene on any "
                                            + "tool call prior to execution")),
                    "Two integration points for human oversight: the @RequiresApproval HITL "
                            + "gate on tools, and the admission gate on the pipeline."),

            new Row("EU-AIA-15", "Accuracy, robustness, cybersecurity",
                    "Article 15 — appropriate level of accuracy, robustness, and cybersecurity "
                            + "throughout the lifecycle.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.rag.InjectionClassifier",
                                    "org.atmosphere.ai.governance.rag.RuleBasedInjectionClassifierTest",
                                    "InjectionClassifier",
                                    "RAG content-injection classifier (rule / embedding / LLM "
                                            + "tiers) — defense against adversarial inputs"),
                            new Evidence("org.atmosphere.ai.governance.scope.ScopePolicy",
                                    "org.atmosphere.ai.AiPipelineScopeHardeningTest",
                                    "ScopePolicy",
                                    "Scope enforcement + system-prompt hardening — primary "
                                            + "robustness surface against goal hijacking"),
                            new Evidence("org.atmosphere.coordinator.commitment.AgentStateIntegrity",
                                    "org.atmosphere.coordinator.commitment.AgentStateIntegrityTest",
                                    "AgentStateIntegrity",
                                    "Ed25519 seals on AgentState memory snapshots — cybersecurity "
                                            + "layer for long-term memory")),
                    "Defense in depth: RAG injection filtering, scope confinement, memory "
                            + "integrity seals, HITL approvals on privileged actions."));

    /** HIPAA Security Rule — 5 safeguards most directly mapped to Atmosphere primitives. */
    public static final List<Row> HIPAA = List.of(
            new Row("HIPAA-164.308-a-1",
                    "Security management process — risk analysis + management",
                    "§164.308(a)(1) — conduct an accurate and thorough assessment of "
                            + "potential risks and vulnerabilities.",
                    Coverage.PARTIAL,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.owasp.OwaspAgenticMatrix",
                                    "org.atmosphere.ai.governance.owasp.OwaspMatrixPinTest",
                                    "OwaspAgenticMatrix",
                                    "OWASP risk catalog feeds the operator's HIPAA risk analysis "
                                            + "for AI-specific threats")),
                    "Atmosphere ships the controls; the risk analysis process is the "
                            + "covered entity's obligation."),

            new Row("HIPAA-164.312-a-1", "Access control — unique user identification",
                    "§164.312(a)(1) — assign a unique name / number for identifying and "
                            + "tracking user identity.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.AiRequest",
                                    "",
                                    "AiRequest",
                                    "userId / sessionId / agentId first-class on every request"),
                            new Evidence("org.atmosphere.ai.annotation.AgentScope",
                                    "org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrailTest",
                                    "@AgentScope",
                                    "Scope decisions are authenticated against the resolved "
                                            + "userId / agentId context")),
                    "Identity fields flow through every turn and surface on every AuditEntry."),

            new Row("HIPAA-164.312-b", "Audit controls",
                    "§164.312(b) — hardware / software / procedural mechanisms that record "
                            + "and examine activity in systems with ePHI.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.AuditEntry",
                                    "org.atmosphere.ai.governance.AuditSinkTest",
                                    "AuditEntry",
                                    "Every admission decision generates an AuditEntry with "
                                            + "identity + context + decision"),
                            new Evidence("org.atmosphere.ai.governance.AuditSink",
                                    "org.atmosphere.ai.governance.AuditSinkTest",
                                    "AuditSink",
                                    "Persistent sinks (Kafka, JDBC) retain audit records beyond "
                                            + "the in-memory ring buffer")),
                    "Full audit-trail surface with retention options."),

            new Row("HIPAA-164.312-c-1", "Integrity — protect ePHI from alteration",
                    "§164.312(c)(1) — electronic protected health information is not "
                            + "improperly altered or destroyed.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.coordinator.commitment.AgentStateIntegrity",
                                    "org.atmosphere.coordinator.commitment.AgentStateIntegrityTest",
                                    "AgentStateIntegrity",
                                    "Ed25519 seals on AgentState snapshots — tamper-evident "
                                            + "memory"),
                            new Evidence("org.atmosphere.coordinator.commitment.CommitmentRecord",
                                    "org.atmosphere.coordinator.commitment.CommitmentRecordTest",
                                    "CommitmentRecord",
                                    "Signed dispatch records prevent replay / forgery of "
                                            + "coordinator decisions")),
                    "Memory and dispatch both carry cryptographic integrity evidence."),

            new Row("HIPAA-164.312-e-1", "Transmission security",
                    "§164.312(e)(1) — technical security measures to guard against "
                            + "unauthorized access to ePHI transmitted over a network.",
                    Coverage.DESIGN,
                    List.of(
                            new Evidence("org.atmosphere.cpr.AtmosphereFramework",
                                    "",
                                    "AtmosphereFramework",
                                    "TLS termination is the deployment's concern; Atmosphere "
                                            + "runs on servlet / WebTransport / WebSocket stacks "
                                            + "that inherit the container's TLS configuration")),
                    "TLS is delegated to the deployment stack (Tomcat / Netty / Jetty). "
                            + "Atmosphere does not mandate a specific cipher suite."));

    /** SOC 2 Trust Services Criteria — 5 Common Criteria rows most relevant to AI. */
    public static final List<Row> SOC2 = List.of(
            new Row("SOC2-CC6.1", "Logical access security",
                    "CC6.1 — implement logical access controls over protected information "
                            + "assets.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.PolicyAdmissionGate",
                                    "org.atmosphere.ai.governance.PolicyAdmissionGateToolCallTest",
                                    "PolicyAdmissionGate",
                                    "Central admission point with default-deny on mutating "
                                            + "operations")),
                    "Every tool call / admission decision flows through the same policy chain."),

            new Row("SOC2-CC6.6", "Access control — credentials revocation",
                    "CC6.6 — implement logical access security measures to protect against "
                            + "threats from sources outside system boundaries.",
                    Coverage.PARTIAL,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.scope.ScopePolicy",
                                    "org.atmosphere.ai.AiPipelineScopeHardeningTest",
                                    "ScopePolicy",
                                    "Scope-based restrictions on agent responsibilities"),
                            new Evidence("org.atmosphere.ai.governance.rag.SafetyContextProvider",
                                    "org.atmosphere.ai.governance.rag.SafetyContextProviderTest",
                                    "SafetyContextProvider",
                                    "Filters external RAG content that could otherwise exfiltrate "
                                            + "or redirect agent behavior")),
                    "Credential-based access (rotation, revocation) is the operator's auth "
                            + "layer; Atmosphere provides the scope + content controls."),

            new Row("SOC2-CC7.2", "System operations — monitoring",
                    "CC7.2 — monitor system components to detect anomalies indicative of "
                            + "malicious acts, natural disasters, or errors.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.GovernanceMetrics",
                                    "org.atmosphere.ai.governance.GovernanceMetricsTest",
                                    "GovernanceMetrics",
                                    "Similarity + latency histograms per policy; operators "
                                            + "alert on admission-path anomalies"),
                            new Evidence("org.atmosphere.ai.governance.GovernanceDecisionLog",
                                    "org.atmosphere.ai.governance.AuditSinkTest",
                                    "GovernanceDecisionLog",
                                    "Ring-buffered decision log + persistent sinks for SIEM "
                                            + "streaming")),
                    "Metrics + audit stream together cover detection of anomalous admission "
                            + "patterns."),

            new Row("SOC2-CC7.3", "System operations — incident response",
                    "CC7.3 — evaluate security events to determine whether they could or "
                            + "have resulted in a failure and initiate response.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.AuditEntry",
                                    "org.atmosphere.ai.governance.AuditSinkTest",
                                    "AuditEntry",
                                    "Time-series decision log drives incident triage"),
                            new Evidence("org.atmosphere.coordinator.commitment.CommitmentRecord",
                                    "org.atmosphere.coordinator.commitment.CommitmentRecordTest",
                                    "CommitmentRecord",
                                    "Signed dispatch records survive post-incident verification")),
                    "Admin console decisions + signed commitment records give responders "
                            + "verifiable evidence of what happened."),

            new Row("SOC2-CC8.1", "Change management",
                    "CC8.1 — authorize, design, develop, configure, document, test, approve, "
                            + "and implement changes to infrastructure, data, software, procedures.",
                    Coverage.PARTIAL,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.GovernancePolicy",
                                    "",
                                    "GovernancePolicy",
                                    "Identity fields (name / source / version) on every policy "
                                            + "feed the operator's change-management audit")),
                    "Policy-side change management is supplied by the identity fields. "
                            + "Source-control + release-process obligations are the operator's."));

    /** Framework → rows lookup; used by admin controller. */
    public static final Map<Framework, List<Row>> MATRICES = Map.of(
            Framework.EU_AI_ACT, EU_AI_ACT,
            Framework.HIPAA, HIPAA,
            Framework.SOC2, SOC2);
}
