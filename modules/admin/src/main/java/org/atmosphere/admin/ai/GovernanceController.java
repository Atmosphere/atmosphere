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
package org.atmosphere.admin.ai;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.AuditEntry;
import org.atmosphere.ai.governance.DryRunPolicy;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernanceHealthReport;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.SwappablePolicy;
import org.atmosphere.ai.governance.TimedPolicy;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.atmosphere.ai.governance.compliance.ComplianceMatrix;
import org.atmosphere.ai.governance.owasp.OwaspAgenticMatrix;
import org.atmosphere.cpr.AtmosphereFramework;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only admin introspection for the Atmosphere governance policy plane.
 * Enumerates policies currently installed on the framework via
 * {@link GovernancePolicy#POLICIES_PROPERTY} and reports their identity so
 * operators can answer "which policies are active on this deployment?" from
 * the admin console without reaching into framework internals.
 *
 * <p>Reports runtime-confirmed state only (Correctness Invariant #5, Runtime
 * Truth): the list reflects what {@code AiEndpointProcessor} will actually
 * apply on a turn, not what the YAML file or Spring beans might intend.</p>
 */
public final class GovernanceController {

    private final AtmosphereFramework framework;

    public GovernanceController(AtmosphereFramework framework) {
        this.framework = framework;
    }

    /**
     * Enumerate active governance policies — one entry per policy, carrying
     * the stable identity ({@code name} / {@code source} / {@code version})
     * and a {@code className} hint so operators can tell e.g. a custom-built
     * policy from an adapter-wrapped {@code AiGuardrail}.
     */
    public List<Map<String, Object>> listPolicies() {
        var policies = readPolicies();
        var result = new ArrayList<Map<String, Object>>(policies.size());
        for (var policy : policies) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", policy.name());
            entry.put("source", policy.source());
            entry.put("version", policy.version());
            entry.put("className", policy.getClass().getName());
            result.add(entry);
        }
        return result;
    }

    /**
     * Evaluate a policy-decision request shaped like Microsoft Agent Governance
     * Toolkit's {@code POST /check} endpoint. Accepts {@code {agent_id, action,
     * context}} and returns {@code {allowed, decision, reason, matched_policy,
     * evaluation_ms}}. Wire-level compatible so external gateways (Envoy,
     * Kong, Azure APIM) that already speak to MS's ASGI policy provider can
     * use Atmosphere as a drop-in decision service.
     *
     * <p>Mapping to the {@link GovernancePolicy} chain:</p>
     * <ul>
     *   <li>{@code agent_id} → {@link AiRequest#agentId()}</li>
     *   <li>{@code action} → {@code context["action"]}</li>
     *   <li>Each {@code context} key is flattened onto request metadata so
     *       MS rules that reference {@code tool_name}, {@code token_count},
     *       etc. see the same values they would inside MS's evaluator.</li>
     * </ul>
     */
    public Map<String, Object> check(Map<String, Object> payload) {
        var start = System.nanoTime();
        var agentId = asString(payload == null ? null : payload.get("agent_id"));
        var action = asString(payload == null ? null : payload.get("action"));
        Map<String, Object> rawContext = Map.of();
        if (payload != null && payload.get("context") instanceof Map<?, ?> ctxMap) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : ctxMap.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(entry.getKey().toString(), entry.getValue());
                }
            }
            rawContext = copy;
        }
        var metadata = new LinkedHashMap<String, Object>(rawContext);
        if (action != null && !action.isBlank()) {
            metadata.putIfAbsent("action", action);
        }
        var message = asString(metadata.get("message"));
        var request = new AiRequest(message == null ? "" : message,
                "", null, null, null, agentId, null, metadata, List.of());
        var policies = readPolicies();

        String matchedName = null;
        String matchedSource = null;
        String action_outcome = "allow";
        boolean allowed = true;
        String reason;
        if (policies.isEmpty()) {
            reason = "no policies installed";
        } else {
            reason = "no rule matched";
            for (var policy : policies) {
                try {
                    var decision = policy.evaluate(PolicyContext.preAdmission(request));
                    switch (decision) {
                        case PolicyDecision.Deny deny -> {
                            allowed = false;
                            action_outcome = "deny";
                            reason = deny.reason();
                            matchedName = policy.name();
                            matchedSource = policy.source();
                        }
                        case PolicyDecision.Transform transform -> {
                            allowed = true;
                            action_outcome = "transform";
                            reason = "request rewritten by policy";
                            matchedName = policy.name();
                            matchedSource = policy.source();
                        }
                        case PolicyDecision.Admit ignored -> { }
                    }
                } catch (Exception e) {
                    allowed = false;
                    action_outcome = "deny";
                    reason = "policy evaluation failed: " + e.getMessage();
                    matchedName = policy.name();
                    matchedSource = policy.source();
                }
                if (!allowed) {
                    break;
                }
            }
        }
        var durationMs = (System.nanoTime() - start) / 1_000_000.0;
        var response = new LinkedHashMap<String, Object>();
        response.put("allowed", allowed);
        response.put("decision", action_outcome);
        response.put("reason", allowed ? "" : reason);
        response.put("matched_policy", matchedName);
        response.put("matched_source", matchedSource);
        response.put("evaluation_ms", Math.round(durationMs * 100.0) / 100.0);
        return response;
    }

    /**
     * Return the most-recent {@link AuditEntry} records from the installed
     * {@link GovernanceDecisionLog}, newest first, up to {@code limit}.
     * Empty when no log is installed (NOOP default).
     */
    public List<Map<String, Object>> listRecentDecisions(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        var entries = GovernanceDecisionLog.installed().recent(limit);
        var result = new ArrayList<Map<String, Object>>(entries.size());
        for (var entry : entries) {
            result.add(renderEntry(entry));
        }
        return result;
    }

    private static Map<String, Object> renderEntry(AuditEntry entry) {
        var map = new LinkedHashMap<String, Object>();
        map.put("timestamp", entry.timestamp().toString());
        map.put("policy_name", entry.policyName());
        map.put("policy_source", entry.policySource());
        map.put("policy_version", entry.policyVersion());
        map.put("decision", entry.decision());
        map.put("reason", entry.reason());
        map.put("evaluation_ms", entry.evaluationMs());
        map.put("context_snapshot", entry.contextSnapshot());
        return map;
    }

    /**
     * Render the {@link OwaspAgenticMatrix} self-assessment as a JSON-friendly
     * map. One entry per OWASP Agentic Top-10 row with coverage, evidence
     * pointers, and notes. Summary counts appear alongside so operators can
     * read the one-line coverage story without walking the full matrix.
     */
    public Map<String, Object> owaspMatrix() {
        var rows = new ArrayList<Map<String, Object>>(OwaspAgenticMatrix.MATRIX.size());
        var coverageCounts = new LinkedHashMap<String, Integer>();
        coverageCounts.put("COVERED", 0);
        coverageCounts.put("PARTIAL", 0);
        coverageCounts.put("DESIGN", 0);
        coverageCounts.put("NOT_ADDRESSED", 0);
        for (var row : OwaspAgenticMatrix.MATRIX) {
            var renderedRow = new LinkedHashMap<String, Object>();
            renderedRow.put("id", row.id());
            renderedRow.put("title", row.title());
            renderedRow.put("description", row.description());
            renderedRow.put("coverage", row.coverage().name());
            renderedRow.put("notes", row.notes());
            var evidenceList = new ArrayList<Map<String, Object>>(row.evidence().size());
            for (var evidence : row.evidence()) {
                var renderedEvidence = new LinkedHashMap<String, Object>();
                renderedEvidence.put("class", evidence.evidenceClass());
                renderedEvidence.put("test", evidence.testClass());
                renderedEvidence.put("consumer_grep", evidence.consumerGrepPattern());
                renderedEvidence.put("description", evidence.description());
                evidenceList.add(renderedEvidence);
            }
            renderedRow.put("evidence", evidenceList);
            rows.add(renderedRow);
            coverageCounts.merge(row.coverage().name(), 1, Integer::sum);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("framework", "OWASP Agentic AI Top 10 (December 2025)");
        result.put("rows", rows);
        result.put("coverage_counts", coverageCounts);
        result.put("total_rows", OwaspAgenticMatrix.MATRIX.size());
        return result;
    }

    /**
     * Render Atmosphere's regulatory compliance self-assessment —
     * {@code EU_AI_ACT}, {@code HIPAA}, {@code SOC2}. Each matrix follows
     * the same shape as the OWASP matrix so admin consumers can reuse
     * the same rendering code.
     */
    public Map<String, Object> complianceMatrices() {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : ComplianceMatrix.MATRICES.entrySet()) {
            result.put(entry.getKey().name(), renderMatrix(
                    entry.getKey().displayName(), entry.getValue()));
        }
        return result;
    }

    private static Map<String, Object> renderMatrix(String displayName,
                                                     List<ComplianceMatrix.Row> rows) {
        var rendered = new ArrayList<Map<String, Object>>(rows.size());
        var coverageCounts = new LinkedHashMap<String, Integer>();
        coverageCounts.put("COVERED", 0);
        coverageCounts.put("PARTIAL", 0);
        coverageCounts.put("DESIGN", 0);
        coverageCounts.put("NOT_ADDRESSED", 0);
        for (var row : rows) {
            var renderedRow = new LinkedHashMap<String, Object>();
            renderedRow.put("id", row.id());
            renderedRow.put("title", row.title());
            renderedRow.put("description", row.description());
            renderedRow.put("coverage", row.coverage().name());
            renderedRow.put("notes", row.notes());
            var evidenceList = new ArrayList<Map<String, Object>>(row.evidence().size());
            for (var evidence : row.evidence()) {
                var renderedEvidence = new LinkedHashMap<String, Object>();
                renderedEvidence.put("class", evidence.evidenceClass());
                renderedEvidence.put("test", evidence.testClass());
                renderedEvidence.put("consumer_grep", evidence.consumerGrepPattern());
                renderedEvidence.put("description", evidence.description());
                evidenceList.add(renderedEvidence);
            }
            renderedRow.put("evidence", evidenceList);
            rendered.add(renderedRow);
            coverageCounts.merge(row.coverage().name(), 1, Integer::sum);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("framework", displayName);
        out.put("rows", rendered);
        out.put("coverage_counts", coverageCounts);
        out.put("total_rows", rows.size());
        return out;
    }

    /**
     * Build a {@link GovernanceHealthReport} snapshot by introspecting the
     * runtime policy list. Finds the first {@link KillSwitchPolicy} (wrapped
     * or direct), every {@link DryRunPolicy}, and fingerprints every policy
     * with its identity digest. Callers that track SLOs separately can
     * extend the response — this surface reports on what the framework
     * already has wired.
     */
    public GovernanceHealthReport health() {
        var policies = readPolicies();
        KillSwitchPolicy killSwitch = null;
        var dryRuns = new ArrayList<DryRunPolicy>();
        var fingerprintable = new java.util.LinkedHashMap<GovernancePolicy, byte[]>();

        for (var policy : policies) {
            var unwrapped = unwrap(policy);
            if (killSwitch == null && unwrapped instanceof KillSwitchPolicy ks) {
                killSwitch = ks;
            }
            if (unwrapped instanceof DryRunPolicy dr) {
                dryRuns.add(dr);
            }
            // Key the fingerprint on the unwrapped policy — the digest should
            // describe the enforceable content, not the observability wrapper.
            fingerprintable.put(unwrapped, null);
        }

        return GovernanceHealthReport.build(killSwitch, fingerprintable, dryRuns, List.of());
    }

    private static GovernancePolicy unwrap(GovernancePolicy policy) {
        if (policy instanceof TimedPolicy timed) {
            return unwrap(timed.delegate());
        }
        return policy;
    }

    /** Convenience — {@link #health()} serialized as a plain map for JSON admin surfaces. */
    public Map<String, Object> healthMap() {
        var report = health();
        var out = new LinkedHashMap<String, Object>();
        out.put("generatedAt", report.generatedAt().toString());

        var ks = new LinkedHashMap<String, Object>();
        ks.put("armed", report.killSwitch().armed());
        if (report.killSwitch().reason() != null) {
            ks.put("reason", report.killSwitch().reason());
            ks.put("operator", report.killSwitch().operator());
            ks.put("armedAt", report.killSwitch().armedAt().toString());
        }
        out.put("killSwitch", ks);

        var fingerprints = new ArrayList<Map<String, Object>>();
        for (var fp : report.policies()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", fp.name());
            entry.put("source", fp.source());
            entry.put("version", fp.version());
            entry.put("digest", fp.digest());
            fingerprints.add(entry);
        }
        out.put("policies", fingerprints);

        var dryRunList = new ArrayList<Map<String, Object>>();
        for (var dr : report.dryRuns()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("wrappedPolicyName", dr.wrappedPolicyName());
            entry.put("admits", dr.admits());
            entry.put("denies", dr.denies());
            entry.put("transforms", dr.transforms());
            entry.put("errors", dr.errors());
            dryRunList.add(entry);
        }
        out.put("dryRuns", dryRunList);

        // Keep SLO slot open even when empty so client code doesn't branch.
        out.put("slos", List.of());
        return out;
    }

    /**
     * Hot-reload a policy wrapped in {@link SwappablePolicy}. Finds the
     * swap slot whose {@link SwappablePolicy#name()} matches
     * {@code swapName}, parses {@code yaml} into the first policy it
     * yields, and atomically replaces the delegate. The wrapper's own
     * identity (audit label) stays pinned; the underlying enforceable
     * content changes.
     *
     * <p>Mutating surface — callers are expected to be authenticated and
     * authorized by the HTTP layer (Correctness Invariant #6). Returns
     * a summary of the swap: outgoing + incoming delegate identity.</p>
     *
     * @throws IllegalArgumentException when no SwappablePolicy with that
     *         name is installed, or the YAML yields zero policies
     */
    public Map<String, Object> reloadSwappable(String swapName, String yaml) {
        if (swapName == null || swapName.isBlank()) {
            throw new IllegalArgumentException("swapName must not be blank");
        }
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("yaml must not be blank");
        }
        var target = findSwappable(swapName);
        if (target == null) {
            throw new IllegalArgumentException("no SwappablePolicy named '" + swapName + "' is installed");
        }

        List<GovernancePolicy> parsed;
        try (var in = new java.io.ByteArrayInputStream(
                yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            parsed = new YamlPolicyParser().parse("admin-reload:" + swapName, in);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("YAML parse failed: " + e.getMessage(), e);
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("YAML yielded zero policies — reload aborted");
        }

        var outgoing = target.replace(parsed.get(0));
        var result = new LinkedHashMap<String, Object>();
        result.put("swapName", swapName);
        result.put("outgoing", Map.of(
                "name", outgoing.name(),
                "version", outgoing.version(),
                "source", outgoing.source()));
        result.put("incoming", Map.of(
                "name", parsed.get(0).name(),
                "version", parsed.get(0).version(),
                "source", parsed.get(0).source()));
        return result;
    }

    /**
     * Arm the first installed {@link KillSwitchPolicy} with the operator-
     * supplied reason + operator id. Mutating surface — the HTTP layer
     * is expected to authenticate and authorize the caller.
     *
     * @return summary of the resulting armed state
     * @throws IllegalStateException when no kill switch is installed
     */
    public Map<String, Object> armKillSwitch(String reason, String operator) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        var killSwitch = findKillSwitch();
        if (killSwitch == null) {
            throw new IllegalStateException("no KillSwitchPolicy installed on this framework");
        }
        killSwitch.arm(reason, operator == null ? "admin" : operator);
        var state = killSwitch.armedState();
        var result = new LinkedHashMap<String, Object>();
        result.put("armed", true);
        result.put("reason", state.reason());
        result.put("operator", state.operator());
        result.put("armedAt", state.armedAt().toString());
        return result;
    }

    /** Disarm the installed kill switch, restoring normal traffic. */
    public Map<String, Object> disarmKillSwitch() {
        var killSwitch = findKillSwitch();
        if (killSwitch == null) {
            throw new IllegalStateException("no KillSwitchPolicy installed on this framework");
        }
        killSwitch.disarm();
        return Map.of("armed", false);
    }

    private KillSwitchPolicy findKillSwitch() {
        for (var policy : readPolicies()) {
            var unwrapped = policy;
            if (unwrapped instanceof TimedPolicy timed) {
                unwrapped = timed.delegate();
            }
            if (unwrapped instanceof KillSwitchPolicy ks) {
                return ks;
            }
        }
        return null;
    }

    private SwappablePolicy findSwappable(String name) {
        for (var policy : readPolicies()) {
            var unwrapped = policy;
            if (unwrapped instanceof TimedPolicy timed) {
                unwrapped = timed.delegate();
            }
            if (unwrapped instanceof SwappablePolicy swap && swap.name().equals(name)) {
                return swap;
            }
        }
        return null;
    }

    /** Summary: policy count and distinct source URIs. */
    public Map<String, Object> summary() {
        var policies = readPolicies();
        var sources = new java.util.LinkedHashSet<String>();
        for (var policy : policies) {
            sources.add(policy.source());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("policyCount", policies.size());
        result.put("sources", List.copyOf(sources));
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private List<GovernancePolicy> readPolicies() {
        if (framework == null) {
            return List.of();
        }
        var config = framework.getAtmosphereConfig();
        if (config == null) {
            return List.of();
        }
        var raw = config.properties().get(GovernancePolicy.POLICIES_PROPERTY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        var result = new ArrayList<GovernancePolicy>(list.size());
        for (var entry : list) {
            if (entry instanceof GovernancePolicy policy) {
                result.add(policy);
            }
        }
        return List.copyOf(result);
    }
}
