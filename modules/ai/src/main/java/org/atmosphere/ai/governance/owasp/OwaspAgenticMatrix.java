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
package org.atmosphere.ai.governance.owasp;

import java.util.List;

/**
 * Atmosphere's self-assessment against the <a
 * href="https://genai.owasp.org/resource/agentic-ai-top-10/">OWASP Agentic AI
 * Top 10</a> (December 2025). Every row points at (a) the shipped feature
 * that defends against the threat, (b) a regression test that fires on the
 * evidence path, and (c) a production-consumer grep pattern so reviewers
 * can confirm the primitive is reached on a real turn — per CLAUDE.md
 * "SPI presence ≠ runtime presence."
 *
 * <h2>Coverage vocabulary</h2>
 * <ul>
 *   <li>{@link Coverage#COVERED} — shipped feature on the critical path with
 *       a consumer + regression test</li>
 *   <li>{@link Coverage#PARTIAL} — feature exists but depends on operator
 *       configuration or doesn't cover every variant of the threat</li>
 *   <li>{@link Coverage#DESIGN} — primitive exists but no shipped consumer;
 *       Atmosphere deployments must wire it themselves</li>
 *   <li>{@link Coverage#NOT_ADDRESSED} — out of current scope, flagged for
 *       transparency</li>
 * </ul>
 *
 * <h2>CI gate</h2>
 * {@code OwaspMatrixPinTest} loads this matrix, resolves every
 * {@link Evidence#evidenceClass()} / {@link Evidence#testClass()} to a real
 * {@link Class}, and fails the build on any reference that no longer
 * exists. Evidence drift surfaces as a red CI run rather than a stale marketing
 * claim.
 */
public final class OwaspAgenticMatrix {

    private OwaspAgenticMatrix() { }

    public enum Coverage { COVERED, PARTIAL, DESIGN, NOT_ADDRESSED }

    /** One entry in the self-assessment matrix. */
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

    /** Single evidence pointer: feature class + test + consumer grep pattern. */
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

    /**
     * The self-assessment. Read the notes column when reviewing — honest
     * reporting of WHAT is covered and WHAT isn't is the whole point;
     * silent rounding up defeats the self-assessment.
     */
    public static final List<Row> MATRIX = List.of(
            new Row("A01", "Goal Hijacking",
                    "LLM answers off-scope requests (McDonald's bot writing Python code).",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.annotation.AgentScope",
                                    "org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrailTest",
                                    "@AgentScope",
                                    "@AgentScope + ScopeGuardrail (3 tiers: rule / embedding / LLM classifier)"),
                            new Evidence("org.atmosphere.ai.governance.scope.ScopePolicy",
                                    "org.atmosphere.ai.AiPipelineScopeHardeningTest",
                                    "ScopePolicy",
                                    "System-prompt hardening at AiPipeline layer (unbypassable)"),
                            new Evidence("org.atmosphere.ai.governance.scope.SampleAgentScopeLintTest",
                                    "org.atmosphere.ai.governance.scope.SampleAgentScopeLintTest",
                                    "",
                                    "Sample-hygiene CI lint — build fails if an @AiEndpoint lacks @AgentScope")),
                    "Full defense-in-depth: pre-admission classification, system-prompt hardening, "
                            + "sample lint. Default tier is embedding-similarity."),

            new Row("A02", "Tool Misuse / Over-Privileged Tool Use",
                    "Agent invokes a tool it shouldn't, or with unsafe arguments.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.annotation.RequiresApproval",
                                    "",
                                    "@RequiresApproval",
                                    "HITL gate on @AiTool methods — parks the virtual thread until approved"),
                            new Evidence("org.atmosphere.ai.governance.PolicyAdmissionGate",
                                    "org.atmosphere.ai.governance.PolicyAdmissionGateToolCallTest",
                                    "admitToolCall",
                                    "Tool-call admission seam — auto-injects tool_name + action into metadata before the tool executor runs"),
                            new Evidence("org.atmosphere.ai.governance.MsAgentOsPolicy",
                                    "org.atmosphere.ai.governance.MsAgentOsYamlConformanceTest",
                                    "tool_name",
                                    "MS-schema YAML rules deny specific tool invocations by context.tool_name")),
                    "COVERED via three layers: @RequiresApproval HITL gate, "
                            + "PolicyAdmissionGate.admitToolCall auto-injecting tool_name at dispatch "
                            + "(ToolExecutionHelper calls it before executeAndFormat), and MS-schema "
                            + "YAML rules over tool_name. The canonical MS example "
                            + "{field: tool_name, operator: eq, value: delete_database, action: deny} "
                            + "fires without operator plumbing."),

            new Row("A03", "Memory Poisoning",
                    "Adversary writes malicious content into long-term memory or RAG store.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.coordinator.commitment.AgentStateIntegrity",
                                    "org.atmosphere.coordinator.commitment.AgentStateIntegrityTest",
                                    "AgentStateIntegrity",
                                    "Ed25519 seal/verify utility for AgentState memory snapshots. "
                                            + "Domain-separated payload binds content to its memory "
                                            + "slot so cross-slot replay fails."),
                            new Evidence("org.atmosphere.coordinator.commitment.CommitmentRecord",
                                    "org.atmosphere.coordinator.commitment.CommitmentRecordTest",
                                    "CommitmentRecord",
                                    "Ed25519-signed dispatch records — verifiable audit trail for "
                                            + "any memory mutation that rides through the coordinator "
                                            + "(@Experimental, flag-off default)"),
                            new Evidence("org.atmosphere.coordinator.commitment.Ed25519CommitmentSigner",
                                    "org.atmosphere.coordinator.commitment.CommitmentRecordTest",
                                    "Ed25519CommitmentSigner",
                                    "JDK 21 built-in EdDSA signer — no external crypto dep; "
                                            + "shared with the AgentStateIntegrity seal format"),
                            new Evidence("org.atmosphere.ai.governance.rag.SafetyContextProvider",
                                    "org.atmosphere.ai.governance.rag.SafetyContextProviderTest",
                                    "SafetyContextProvider",
                                    "Filters injected documents from RAG retrieval — prevents "
                                            + "poisoned RAG corpora from reaching the prompt (A04 overlap)")),
                    "COVERED via two layers: AgentStateIntegrity seals long-term memory snapshots "
                            + "with domain-separated Ed25519 signatures, and SafetyContextProvider "
                            + "filters injected content out of the RAG retrieval path. The "
                            + "coordinator's CommitmentRecord provides the audit trail for any "
                            + "memory mutation that rides through dispatch. All three are flag-off "
                            + "by default; operators opt in for deployments that require "
                            + "tamper-evident memory."),

            new Row("A04", "Indirect Prompt Injection",
                    "Attacker plants instructions in RAG docs / tool outputs / web content the agent ingests.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.rag.InjectionClassifier",
                                    "org.atmosphere.ai.governance.rag.RuleBasedInjectionClassifierTest",
                                    "InjectionClassifier",
                                    "Three-tier classifier SPI (rule-based / embedding-similarity / "
                                            + "LLM-classifier) cross-provider via EmbeddingRuntime + AgentRuntime"),
                            new Evidence("org.atmosphere.ai.governance.rag.SafetyContextProvider",
                                    "org.atmosphere.ai.governance.rag.SafetyContextProviderTest",
                                    "SafetyContextProvider",
                                    "Decorator wraps any ContextProvider, drops / flags / sanitizes "
                                            + "flagged docs, records to GovernanceDecisionLog"),
                            new Evidence("org.atmosphere.ai.guardrails.PiiRedactionGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "PiiRedactionGuardrail",
                                    "Response-side regex guardrail catches PII leaked via indirect injection"),
                            new Evidence("org.atmosphere.ai.governance.scope.ScopePolicy",
                                    "org.atmosphere.ai.AiPipelineScopeHardeningTest",
                                    "ScopePolicy",
                                    "Scope-confinement preamble blunts injected instructions")),
                    "COVERED via three-tier InjectionClassifier SPI + SafetyContextProvider decorator. "
                            + "Default rule-based tier requires no runtime; embedding-similarity tier "
                            + "leverages any installed EmbeddingRuntime (5 adapters); LLM-classifier "
                            + "tier uses any AgentRuntime (7 adapters). Every flagged document is "
                            + "audited through GovernanceDecisionLog and honours drop / flag / "
                            + "sanitize breach policies."),

            new Row("A05", "Cascading Failures / Runaway Agent Loops",
                    "Multi-agent loop spirals out of control; one agent's failure triggers another.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.guardrails.CostCeilingGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "CostCeilingGuardrail",
                                    "Per-tenant dollar ceiling — blocks outbound prompts when budget exhausted"),
                            new Evidence("org.atmosphere.ai.guardrails.OutputLengthZScoreGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "OutputLengthZScoreGuardrail",
                                    "Z-score drift detector — blocks responses N sigma from rolling mean"),
                            new Evidence("org.atmosphere.coordinator.journal.CoordinationJournal",
                                    "",
                                    "CoordinationJournal",
                                    "Cross-agent dispatch log — observable + rate-inspectable")),
                    "Backpressure invariant (#3 in CLAUDE.md) is enforced framework-wide; cost + "
                            + "drift guardrails catch runaway generations at the turn level."),

            new Row("A06", "Unauthorized Action / Privilege Escalation",
                    "Agent performs an action beyond its principal's authorization.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.admin.ControlAuthorizer",
                                    "",
                                    "ControlAuthorizer",
                                    "Triple-gate admin authorization (feature flag + Principal + ControlAuthorizer)"),
                            new Evidence("org.atmosphere.ai.identity.AgentIdentity",
                                    "",
                                    "AgentIdentity",
                                    "Per-user identity, permissions, credentials"),
                            new Evidence("org.atmosphere.ai.annotation.RequiresApproval",
                                    "org.atmosphere.ai.tool.ToolExecutionHelperTest",
                                    "@RequiresApproval",
                                    "HITL gate for privileged actions")),
                    "Correctness Invariant #6 ('every mutating surface requires explicit authorization, "
                            + "default deny') is framework-wide."),

            new Row("A07", "Output Leakage / Sensitive Information Disclosure",
                    "Agent reveals secrets, PII, or internal data through generated text.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.filter.PiiRedactionFilter",
                                    "org.atmosphere.ai.filter.PiiRedactionFilterTest",
                                    "PiiRedactionFilter",
                                    "Stream-level PII rewrite — rewrites tokens before bytes flush to client"),
                            new Evidence("org.atmosphere.ai.guardrails.PiiRedactionGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "PiiRedactionGuardrail",
                                    "Pre-admission + response-side PII detector")),
                    "Both wire-level (filter) and turn-level (guardrail) coverage. Regex patterns "
                            + "ship for email, phone, credit card, SSN, IPv4; operators extend via "
                            + "withPatterns()."),

            new Row("A08", "Supply Chain Compromise (Plugin / MCP)",
                    "Malicious or tampered plugin / MCP server injected into the agent runtime.",
                    Coverage.NOT_ADDRESSED,
                    List.of(),
                    "Out of current scope. Ed25519 plugin signing + IATP trust scoring (MS Agent Mesh "
                            + "parity) live in Phase C — parked per v4 §4 until a named enterprise ask "
                            + "or concrete partner-integration trigger fires."),

            new Row("A09", "Denial of Service (Cost / Resource Exhaustion)",
                    "Attacker pumps expensive prompts, exhausts API budget, or forces large token generations.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.guardrails.CostCeilingGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "CostCeilingGuardrail",
                                    "Per-tenant budget; blocks outbound prompts when exhausted"),
                            new Evidence("org.atmosphere.ai.gateway.PerUserRateLimiter",
                                    "",
                                    "PerUserRateLimiter",
                                    "Per-user call rate limit on AiGateway admission path"),
                            new Evidence("org.atmosphere.ai.guardrails.OutputLengthZScoreGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "OutputLengthZScoreGuardrail",
                                    "Caps runaway generations via length-drift detection")),
                    "Three defensive layers: per-tenant cost, per-user rate, per-response length drift. "
                            + "Cost + rate are observable in real time through Micrometer / OTel."),

            new Row("A10", "Lack of Accountability / No Audit Trail",
                    "Agent decisions cannot be traced back to policies, principals, or turn context.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.GovernanceDecisionLog",
                                    "org.atmosphere.ai.governance.GovernanceDecisionLogTest",
                                    "GovernanceDecisionLog",
                                    "Ring-buffered AuditEntry per policy.evaluate — identity, decision, context snapshot, timing"),
                            new Evidence("org.atmosphere.ai.governance.GovernanceTracer",
                                    "",
                                    "GovernanceTracer",
                                    "OpenTelemetry span per evaluation — name, decision, reason as span attributes"),
                            new Evidence("org.atmosphere.admin.ai.GovernanceController",
                                    "org.atmosphere.admin.ai.GovernanceControllerTest",
                                    "GovernanceController",
                                    "/api/admin/governance/decisions surfaces the ring buffer over HTTP")),
                    "Every GovernancePolicy.evaluate decision generates an AuditEntry (context_snapshot "
                            + "redaction-safe) and an OTel span. Ring-buffered for post-incident triage; "
                            + "operators ship to Kafka/Postgres via a custom sink for long-term retention.")
    );

    /** Shorthand — lookup by ID (A01 through A10). */
    public static Row row(String id) {
        for (var row : MATRIX) {
            if (row.id().equalsIgnoreCase(id)) return row;
        }
        throw new IllegalArgumentException("unknown OWASP Agentic row: " + id);
    }
}
