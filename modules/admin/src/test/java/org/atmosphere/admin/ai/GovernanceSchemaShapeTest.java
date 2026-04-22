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

import org.atmosphere.ai.governance.AuditEntry;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the output shape of every
 * {@code GET /api/admin/governance/*} endpoint against a documented contract.
 * Drift here silently breaks:
 *
 * <ul>
 *   <li>the admin-console Vue views (which read these payloads verbatim);</li>
 *   <li>Microsoft Agent Governance Toolkit consumers that point at our
 *       {@code /check} endpoint as a drop-in decision service;</li>
 *   <li>{@code agt verify}-style compliance tooling that reads the OWASP
 *       matrix and audit trail for evidence.</li>
 * </ul>
 *
 * <p>When renaming or retiring a key is genuinely required, update this
 * test AND the Vue views AND the docs in the same commit so the contract
 * break is visible.</p>
 */
class GovernanceSchemaShapeTest {

    @AfterEach
    void tearDown() {
        GovernanceDecisionLog.reset();
    }

    @Test
    void listPoliciesShapeIsStable() {
        var controller = new GovernanceController(new AtmosphereFramework());
        var list = controller.listPolicies();
        // Empty list on a bare framework; shape lives in the renderer, not
        // the elements — we assert that via a direct render in the OWASP
        // matrix below (which always has rows).
        assertNotNull(list);
    }

    @Test
    void summaryShapeIsStable() {
        var controller = new GovernanceController(new AtmosphereFramework());
        var summary = controller.summary();
        assertEquals(Set.of("policyCount", "sources"), summary.keySet(),
                "summary keys must remain {policyCount, sources}");
        assertInstanceOf(Integer.class, summary.get("policyCount"));
        assertInstanceOf(java.util.List.class, summary.get("sources"));
    }

    @Test
    void checkShapeIsStableAndMatchesMsAgentOs() {
        var controller = new GovernanceController(new AtmosphereFramework());
        var response = controller.check(Map.of(
                "agent_id", "classroom",
                "action", "prompt",
                "context", Map.of("message", "hello")));

        // The MS Agent Governance Toolkit /check payload has six fields. We
        // match verbatim so external gateways (Envoy, Kong, Azure APIM)
        // that already speak to MS's ASGI provider can point at us as a
        // drop-in decision service.
        assertEquals(Set.of("allowed", "decision", "reason", "matched_policy",
                        "matched_source", "evaluation_ms"),
                response.keySet(),
                "/check payload drift from the MS Agent Governance Toolkit: "
                        + response.keySet());
        assertInstanceOf(Boolean.class, response.get("allowed"));
        assertInstanceOf(String.class, response.get("decision"));
        assertInstanceOf(String.class, response.get("reason"));
        // matched_policy and matched_source are nullable strings on "no match"
        // paths; only the null / String union needs to hold.
        var matchedPolicy = response.get("matched_policy");
        assertTrue(matchedPolicy == null || matchedPolicy instanceof String,
                "matched_policy is String or null, got: " + matchedPolicy);
        var matchedSource = response.get("matched_source");
        assertTrue(matchedSource == null || matchedSource instanceof String,
                "matched_source is String or null, got: " + matchedSource);
        assertInstanceOf(Double.class, response.get("evaluation_ms"));
    }

    @Test
    void listRecentDecisionsShapeIsStable() {
        var log = GovernanceDecisionLog.install(10);
        log.record(new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "policy-a",
                "code:test",
                "1.0",
                "deny",
                "test reason",
                Map.of("phase", "pre_admission", "message", "hi"),
                0.42));

        var controller = new GovernanceController(new AtmosphereFramework());
        var list = controller.listRecentDecisions(10);
        assertEquals(1, list.size());
        var entry = list.get(0);
        assertEquals(Set.of("timestamp", "policy_name", "policy_source",
                        "policy_version", "decision", "reason", "evaluation_ms",
                        "context_snapshot"),
                entry.keySet(),
                "audit entry shape must match the MS audit_entry payload: "
                        + entry.keySet());
        assertInstanceOf(String.class, entry.get("timestamp"));
        assertInstanceOf(String.class, entry.get("policy_name"));
        assertInstanceOf(String.class, entry.get("decision"));
        assertInstanceOf(Double.class, entry.get("evaluation_ms"));
        assertInstanceOf(Map.class, entry.get("context_snapshot"));
    }

    @Test
    void owaspMatrixShapeIsStable() {
        var controller = new GovernanceController(new AtmosphereFramework());
        var matrix = controller.owaspMatrix();
        assertEquals(Set.of("framework", "rows", "coverage_counts", "total_rows"),
                matrix.keySet(),
                "owasp matrix payload shape drift: " + matrix.keySet());
        assertInstanceOf(String.class, matrix.get("framework"));
        assertInstanceOf(java.util.List.class, matrix.get("rows"));
        assertInstanceOf(Map.class, matrix.get("coverage_counts"));
        assertInstanceOf(Integer.class, matrix.get("total_rows"));

        var rows = (java.util.List<?>) matrix.get("rows");
        assertTrue(!rows.isEmpty(), "OWASP rows must not be empty");
        var row0 = (Map<?, ?>) rows.get(0);
        assertEquals(Set.of("id", "title", "description", "coverage",
                        "notes", "evidence"),
                row0.keySet(),
                "owasp row shape drift: " + row0.keySet());
        var evidence = (java.util.List<?>) row0.get("evidence");
        if (!evidence.isEmpty()) {
            var ev0 = (Map<?, ?>) evidence.get(0);
            assertEquals(Set.of("class", "test", "consumer_grep", "description"),
                    ev0.keySet(),
                    "owasp evidence shape drift: " + ev0.keySet());
        }
    }

    @Test
    void coverageCountsIncludeAllFourBuckets() {
        var controller = new GovernanceController(new AtmosphereFramework());
        var matrix = controller.owaspMatrix();
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Integer>) matrix.get("coverage_counts");
        assertEquals(Set.of("COVERED", "PARTIAL", "DESIGN", "NOT_ADDRESSED"),
                counts.keySet(),
                "coverage_counts keys must stay the four OWASP buckets: "
                        + counts.keySet());
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(matrix.get("total_rows"), total,
                "coverage sums must equal total rows");
    }
}
