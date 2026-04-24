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

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceControllerTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private Map<String, Object> properties;

    @BeforeEach
    void setUp() {
        framework = mock(AtmosphereFramework.class);
        config = mock(AtmosphereConfig.class);
        properties = new HashMap<>();
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(properties);
    }

    @Test
    void returnsEmptyWhenNoPoliciesInstalled() {
        var controller = new GovernanceController(framework);
        assertTrue(controller.listPolicies().isEmpty());
        assertEquals(0, controller.summary().get("policyCount"));
    }

    @Test
    void returnsEmptyForNullFramework() {
        var controller = new GovernanceController(null);
        assertTrue(controller.listPolicies().isEmpty());
    }

    @Test
    void reportsIdentityForEachInstalledPolicy() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                new FakePolicy("pii-guard", "yaml:/etc/policies.yaml", "1.2.0"),
                new FakePolicy("drift-watcher", "classpath:atmosphere-policies.yaml", "1.0")));
        var controller = new GovernanceController(framework);

        var list = controller.listPolicies();
        assertEquals(2, list.size());
        assertEquals("pii-guard", list.get(0).get("name"));
        assertEquals("yaml:/etc/policies.yaml", list.get(0).get("source"));
        assertEquals("1.2.0", list.get(0).get("version"));
        assertEquals(FakePolicy.class.getName(), list.get(0).get("className"));
    }

    @Test
    void summaryAggregatesCountAndUniqueSources() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                new FakePolicy("a", "yaml:main.yaml", "1.0"),
                new FakePolicy("b", "yaml:main.yaml", "1.0"),
                new FakePolicy("c", "code:org.example.Custom", "1.0")));
        var controller = new GovernanceController(framework);

        var summary = controller.summary();
        assertEquals(3, summary.get("policyCount"));
        var sources = (List<?>) summary.get("sources");
        assertEquals(2, sources.size(), "duplicate yaml:main.yaml must be collapsed");
        assertTrue(sources.contains("yaml:main.yaml"));
        assertTrue(sources.contains("code:org.example.Custom"));
    }

    @Test
    void skipsNonPolicyEntriesInPropertyValue() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                new FakePolicy("valid", "yaml:a.yaml", "1.0"),
                "not a policy",
                42));
        var controller = new GovernanceController(framework);

        var list = controller.listPolicies();
        assertEquals(1, list.size());
        assertEquals("valid", list.get(0).get("name"));
    }

    @Test
    void checkAllowsWhenNoPoliciesInstalled() {
        var controller = new GovernanceController(framework);
        var decision = controller.check(java.util.Map.of(
                "agent_id", "agent-a",
                "action", "read_documents",
                "context", java.util.Map.of("tool_name", "search")));
        assertTrue((boolean) decision.get("allowed"));
        assertEquals("allow", decision.get("decision"));
        assertEquals("", decision.get("reason"));
    }

    @Test
    void checkRunsMsStyleDenyRuleFromMetadata() {
        // In-memory MS-style rule: deny when tool_name == delete_database.
        // Avoids a test-scope SnakeYAML dep while still proving the /check
        // endpoint flows MS context fields through metadata correctly.
        var rule = new org.atmosphere.ai.governance.MsAgentOsPolicy.Rule(
                "deny-delete", "tool_name",
                org.atmosphere.ai.governance.MsAgentOsPolicy.Operator.EQ,
                "delete_database", 100, "Destructive tool call",
                org.atmosphere.ai.governance.MsAgentOsPolicy.Action.DENY, null);
        var policy = new org.atmosphere.ai.governance.MsAgentOsPolicy(
                "block-delete-database", "yaml:test", "1.0",
                List.of(rule),
                org.atmosphere.ai.governance.MsAgentOsPolicy.Action.ALLOW);
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(policy));

        var controller = new GovernanceController(framework);
        var decision = controller.check(java.util.Map.of(
                "agent_id", "agent-a",
                "action", "call_tool",
                "context", java.util.Map.of("tool_name", "delete_database")));

        assertEquals(false, decision.get("allowed"));
        assertEquals("deny", decision.get("decision"));
        assertEquals("Destructive tool call", decision.get("reason"));
        assertEquals("block-delete-database", decision.get("matched_policy"));
        assertEquals("yaml:test", decision.get("matched_source"));
    }

    @Test
    void checkAllowsWhenNoRuleMatches() {
        var rule = new org.atmosphere.ai.governance.MsAgentOsPolicy.Rule(
                "deny-exact-tool", "tool_name",
                org.atmosphere.ai.governance.MsAgentOsPolicy.Operator.EQ,
                "delete_database", 100, "",
                org.atmosphere.ai.governance.MsAgentOsPolicy.Action.DENY, null);
        var policy = new org.atmosphere.ai.governance.MsAgentOsPolicy(
                "specific-denies", "yaml:test", "1.0",
                List.of(rule),
                org.atmosphere.ai.governance.MsAgentOsPolicy.Action.ALLOW);
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(policy));

        var controller = new GovernanceController(framework);
        var decision = controller.check(java.util.Map.of(
                "agent_id", "agent-a",
                "action", "call_tool",
                "context", java.util.Map.of("tool_name", "search_documents")));

        assertTrue((boolean) decision.get("allowed"));
        assertEquals("allow", decision.get("decision"));
    }

    @Test
    void checkReportsEvaluationMs() throws Exception {
        var controller = new GovernanceController(framework);
        var decision = controller.check(java.util.Map.of("context", java.util.Map.of()));
        var evaluationMs = decision.get("evaluation_ms");
        assertTrue(evaluationMs instanceof Number,
                "evaluation_ms must be numeric (got " + evaluationMs + ")");
        assertTrue(((Number) evaluationMs).doubleValue() >= 0.0);
    }

    @Test
    void wrongTypeInPropertyYieldsEmptyList() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, "not a list");
        var controller = new GovernanceController(framework);
        assertTrue(controller.listPolicies().isEmpty(),
                "non-list property must not crash — reads as empty");
    }

    private record FakePolicy(String name, String source, String version) implements GovernancePolicy {
        @Override
        public PolicyDecision evaluate(PolicyContext context) {
            return PolicyDecision.admit();
        }
    }
}
