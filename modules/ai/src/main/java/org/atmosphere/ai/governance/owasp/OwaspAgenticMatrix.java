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
 * Top 10</a> (December 2025). Every addressed (COVERED / PARTIAL / DESIGN)
 * row points at (a) the shipped feature that defends against the threat,
 * (b) a regression test that fires on the evidence path, and (c) a
 * production-consumer grep pattern so reviewers can confirm the primitive
 * is reached on a real turn — per CLAUDE.md "SPI presence ≠ runtime presence."
 * {@link Coverage#NOT_ADDRESSED} rows carry no evidence and state the gap in
 * their notes column.
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

    /**
     * Single evidence pointer: feature class + test + consumer-grep pattern.
     *
     * <p>{@link #selfGate()} defaults to false — ordinary rows assert that
     * a non-blank {@link #consumerGrepPattern()} finds at least one
     * production caller outside the evidence class itself. Rows where the
     * evidence class <i>is</i> the CI gate (e.g. a lint test) must be
     * constructed via {@link #selfGate(String, String, String)} to
     * declare the opt-out explicitly — a blank pattern without
     * {@code selfGate=true} fails the build via
     * {@code EvidenceConsumerGrepPinTest}.</p>
     */
    public record Evidence(String evidenceClass, String testClass,
                           String consumerGrepPattern, String description,
                           boolean selfGate) {
        public Evidence {
            if (evidenceClass == null || evidenceClass.isBlank()) {
                throw new IllegalArgumentException("evidenceClass must not be blank");
            }
            testClass = testClass == null ? "" : testClass;
            consumerGrepPattern = consumerGrepPattern == null ? "" : consumerGrepPattern;
            description = description == null ? "" : description;
            if (consumerGrepPattern.isBlank() && !selfGate) {
                throw new IllegalArgumentException(
                        "Evidence row '" + evidenceClass + "' has a blank "
                                + "consumerGrepPattern but selfGate=false. Either "
                                + "supply a grep pattern for the consumer, or call "
                                + "Evidence.selfGate(...) to declare that the "
                                + "evidence class is its own CI gate.");
            }
        }

        /** Conventional 4-arg constructor for ordinary rows (selfGate = false). */
        public Evidence(String evidenceClass, String testClass,
                        String consumerGrepPattern, String description) {
            this(evidenceClass, testClass, consumerGrepPattern, description, false);
        }

        /**
         * Opt-out factory — the evidence class is itself the CI gate
         * (e.g. {@code SampleAgentScopeLintTest} walks samples/). No
         * consumer-grep pattern is applicable; the test file's
         * existence + green status IS the evidence.
         */
        public static Evidence selfGate(String evidenceClass, String testClass,
                                        String description) {
            return new Evidence(evidenceClass, testClass, "", description, true);
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
                            Evidence.selfGate("org.atmosphere.ai.governance.scope.SampleAgentScopeLintTest",
                                    "org.atmosphere.ai.governance.scope.SampleAgentScopeLintTest",
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
                                    "MsAgentOsPolicy",
                                    "Opt-in MS-schema YAML policy — the operator supplies tool-deny "
                                            + "rules (e.g. {field: tool_name, operator: eq, "
                                            + "value: delete_database, action: deny}), parsed by "
                                            + "YamlPolicyParser; no code, but not on by default")),
                    "COVERED by two default-on layers: the @RequiresApproval HITL gate parks the "
                            + "virtual thread on a privileged @AiTool until a human approves, and "
                            + "PolicyAdmissionGate.admitToolCall runs on every tool dispatch "
                            + "(ToolExecutionHelper calls it before the executor), auto-injecting "
                            + "tool_name + action into the admission context. MS-schema YAML rules "
                            + "over tool_name are an opt-in third layer — the operator supplies the "
                            + "rule (no code), but there are no default deny rules."),

            new Row("A03", "Memory Poisoning",
                    "Adversary writes malicious content into long-term memory or RAG store.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.ai.governance.memory.MemorySafetyConfig",
                                    "org.atmosphere.ai.governance.memory.MemorySafetyTest",
                                    "MemorySafetyConfig",
                                    "Default-on write-path screen: every fact extracted into a "
                                            + "LongTermMemory store is checked for indirect prompt "
                                            + "injection before it is persisted and re-injected, "
                                            + "fail-closed and rule-based; disable with "
                                            + "atmosphere.ai.memory.safety.enabled=false"),
                            new Evidence("org.atmosphere.ai.governance.memory.ScreenedLongTermMemory",
                                    "org.atmosphere.ai.governance.memory.MemorySafetyTest",
                                    "ScreenedLongTermMemory",
                                    "Decorator that enforces the memory write-path screen — drops / "
                                            + "flags / sanitizes a poisoned fact and audits the "
                                            + "enforcement to GovernanceDecisionLog"),
                            new Evidence("org.atmosphere.ai.governance.rag.SafetyContextProvider",
                                    "org.atmosphere.ai.governance.rag.SafetyContextProviderTest",
                                    "SafetyContextProvider",
                                    "Default-on read-path screen: filters injected documents from "
                                            + "RAG retrieval before they reach the prompt (A04 overlap)"),
                            new Evidence("org.atmosphere.ai.governance.rag.RagSafetyConfig",
                                    "org.atmosphere.ai.governance.rag.RagSafetyConfigTest",
                                    "RagSafetyConfig",
                                    "Default-on read-path wiring that wraps every @AiEndpoint "
                                            + "ContextProvider with SafetyContextProvider")),
                    "COVERED — on by default on both halves of A03. The write path screens every "
                            + "fact extracted into a LongTermMemory store (MemorySafetyConfig wraps "
                            + "the store with ScreenedLongTermMemory) before it is persisted and "
                            + "re-injected; the read path screens every retrieved RAG document "
                            + "(RagSafetyConfig wraps the ContextProvider with SafetyContextProvider). "
                            + "Both are fail-closed, rule-based, zero-dependency; disable with "
                            + "atmosphere.ai.memory.safety.enabled=false / "
                            + "atmosphere.ai.rag.safety.enabled=false. Opt-in tamper-evidence is "
                            + "available on the coordinator dispatch path for deployments that "
                            + "additionally require signed memory snapshots: Ed25519-signed "
                            + "CommitmentRecords (flag-off by default) and the AgentStateIntegrity "
                            + "seal utility (API + reference impl; not yet wired to a default "
                            + "consumer). Both need a durable operator key, so they stay opt-in."),

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
                            new Evidence("org.atmosphere.ai.governance.rag.RagSafetyConfig",
                                    "org.atmosphere.ai.governance.rag.RagSafetyConfigTest",
                                    "RagSafetyConfig",
                                    "Default-on wiring: AiEndpointProcessor wraps every @AiEndpoint "
                                            + "ContextProvider with SafetyContextProvider (rule-based, "
                                            + "fail-closed) unless atmosphere.ai.rag.safety.enabled=false"),
                            new Evidence("org.atmosphere.ai.guardrails.PiiRedactionGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "PiiRedactionGuardrail",
                                    "Opt-in response-side regex guardrail — extra layer catching PII "
                                            + "leaked via indirect injection when enabled"),
                            new Evidence("org.atmosphere.ai.governance.scope.ScopePolicy",
                                    "org.atmosphere.ai.AiPipelineScopeHardeningTest",
                                    "ScopePolicy",
                                    "Scope-confinement preamble blunts injected instructions")),
                    "COVERED — on by default. AiEndpointProcessor wraps every @AiEndpoint "
                            + "ContextProvider with SafetyContextProvider unless "
                            + "atmosphere.ai.rag.safety.enabled=false, so retrieved documents are "
                            + "screened with no opt-in. Default rule-based tier requires no runtime; "
                            + "embedding-similarity and LLM-classifier tiers leverage any installed "
                            + "EmbeddingRuntime / AgentRuntime and downgrade to rule-based (never "
                            + "fail open) when absent. Every flagged document is audited through "
                            + "GovernanceDecisionLog and honours drop / flag / sanitize breach policies."),

            new Row("A05", "Cascading Failures / Runaway Agent Loops",
                    "Multi-agent loop spirals out of control; one agent's failure triggers another.",
                    Coverage.COVERED,
                    List.of(
                            new Evidence("org.atmosphere.coordinator.fleet.DefaultAgentFleet",
                                    "",
                                    "DEFAULT_PARALLEL_TIMEOUT_MS",
                                    "Default-on 120s timeout on parallel agent fan-out — a stalled "
                                            + "sub-agent cannot hang the fleet"),
                            new Evidence("org.atmosphere.ai.llm.ToolLoopPolicy",
                                    "",
                                    "ToolLoopPolicy",
                                    "Default-on tool-loop bound (5 model→tool→model rounds) — caps a "
                                            + "runaway tool-calling loop at the turn level"),
                            new Evidence("org.atmosphere.ai.guardrails.CostCeilingGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "CostCeilingGuardrail",
                                    "Opt-in per-tenant dollar ceiling — blocks outbound prompts when budget exhausted"),
                            new Evidence("org.atmosphere.ai.guardrails.OutputLengthZScoreGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "OutputLengthZScoreGuardrail",
                                    "Opt-in z-score drift detector — blocks responses N sigma from rolling mean"),
                            new Evidence("org.atmosphere.coordinator.journal.CoordinationJournal",
                                    "",
                                    "CoordinationJournal",
                                    "Cross-agent dispatch log — observable + rate-inspectable")),
                    "Default-on bounds cap runaway loops: a 120s parallel-fan-out timeout "
                            + "(DefaultAgentFleet) and a 5-round tool-loop cap (ToolLoopPolicy). The "
                            + "backpressure invariant (#3 in CLAUDE.md) is enforced framework-wide. "
                            + "Opt-in cost + output-drift guardrails add a per-tenant budget and "
                            + "length-anomaly blocking on top when enabled."),

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
                    Coverage.PARTIAL,
                    List.of(
                            new Evidence("org.atmosphere.ai.filter.PiiRedactionFilter",
                                    "org.atmosphere.ai.filter.PiiRedactionFilterTest",
                                    "PiiRedactionFilter",
                                    "Opt-in stream-level PII rewrite — rewrites tokens before bytes flush to client"),
                            new Evidence("org.atmosphere.ai.guardrails.PiiRedactionGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "PiiRedactionGuardrail",
                                    "Opt-in pre-admission + response-side PII detector")),
                    "PARTIAL — PII redaction ships as a stream-level filter (PiiRedactionFilter, "
                            + "wire-level token rewrite) and a turn-level guardrail "
                            + "(PiiRedactionGuardrail, response-side block), with regex for email / "
                            + "phone / credit-card / SSN / IPv4. Both are OPT-IN, not default-on: a "
                            + "default response block would terminate any legitimate answer that "
                            + "contains a customer's own email or IP, so the operator enables them "
                            + "explicitly (the Spring starter beans, an @AiEndpoint(filters=...) "
                            + "declaration, or PolicyRegistry YAML) and extends patterns via "
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
                            new Evidence("org.atmosphere.ai.gateway.PerUserRateLimiter",
                                    "",
                                    "PerUserRateLimiter",
                                    "Default-on per-user rate limiter on the AiGateway admission path "
                                            + "that every AbstractAgentRuntime call traverses; "
                                            + "permissive 1M-calls/hour backstop, tighten via "
                                            + "AiGatewayHolder.install(...)"),
                            new Evidence("org.atmosphere.ai.guardrails.CostCeilingGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "CostCeilingGuardrail",
                                    "Opt-in per-tenant budget; blocks outbound prompts when exhausted"),
                            new Evidence("org.atmosphere.ai.guardrails.OutputLengthZScoreGuardrail",
                                    "org.atmosphere.ai.guardrails.GuardrailsTest",
                                    "OutputLengthZScoreGuardrail",
                                    "Opt-in cap on runaway generations via length-drift detection")),
                    "Default-on: a per-user rate limiter sits on the AiGateway admission path that "
                            + "every runtime call passes through (AbstractAgentRuntime → "
                            + "AiGatewayHolder), at a permissive 1M-calls/hour backstop — the "
                            + "framework warns at startup that the default is permissive and "
                            + "production should call AiGatewayHolder.install(...) to tighten it. "
                            + "Opt-in cost + length-drift guardrails add a per-tenant dollar ceiling "
                            + "and runaway-generation caps; cost + rate are observable in real time "
                            + "through Micrometer / OTel."),

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
