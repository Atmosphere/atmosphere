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
package org.atmosphere.mcp.runtime;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.MsAgentOsPolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link McpPolicyGateway} consults the installed
 * {@link GovernancePolicy} chain when {@code atmosphere-ai} is on the
 * classpath (it is here — test-scope dep). An MS-schema YAML rule over
 * {@code tool_name} must fire for MCP tool calls just like it does for
 * first-party {@code @AiTool} dispatches.
 */
class McpPolicyGatewayTest {

    private AtmosphereFramework framework;
    private Map<String, Object> properties;

    @BeforeEach
    void setUp() {
        framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        properties = new HashMap<>();
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(properties);
    }

    @Test
    void gatewayIsActiveWhenAiModuleOnClasspath() {
        // atmosphere-ai is a test-scope dep here, so the gateway is live.
        assertTrue(McpPolicyGateway.isActive(),
                "atmosphere-ai is on the classpath during tests");
    }

    @Test
    void admitsWhenNoPoliciesInstalled() {
        var outcome = McpPolicyGateway.admit(framework, "search", Map.of("q", "hi"));
        assertEquals(McpPolicyGateway.Outcome.ADMITTED, outcome);
    }

    @Test
    void deniesWhenMsSchemaRuleMatchesToolName() {
        // Canonical MS example: deny delete_database tool calls.
        var policy = new MsAgentOsPolicy(
                "block-delete-database", "yaml:test", "1.0",
                List.of(new MsAgentOsPolicy.Rule(
                        "deny-delete",
                        "tool_name",
                        MsAgentOsPolicy.Operator.EQ,
                        "delete_database",
                        100,
                        "Deleting databases is not allowed",
                        MsAgentOsPolicy.Action.DENY,
                        null)),
                MsAgentOsPolicy.Action.ALLOW);
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(policy));

        var outcome = McpPolicyGateway.admit(framework, "delete_database",
                Map.of("table", "users"));
        var denied = assertInstanceOf(McpPolicyGateway.Outcome.Denied.class, outcome);
        assertEquals("block-delete-database", denied.policyName());
        assertTrue(denied.reason().toLowerCase().contains("deleting databases"),
                "MS message flows through: " + denied.reason());
    }

    @Test
    void allowedToolPasses() {
        var policy = new MsAgentOsPolicy(
                "tool-gate", "yaml:test", "1.0",
                List.of(new MsAgentOsPolicy.Rule(
                        "deny-delete",
                        "tool_name",
                        MsAgentOsPolicy.Operator.EQ,
                        "delete_database",
                        100,
                        "",
                        MsAgentOsPolicy.Action.DENY,
                        null)),
                MsAgentOsPolicy.Action.ALLOW);
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(policy));

        var outcome = McpPolicyGateway.admit(framework, "search_documents",
                Map.of("query", "hello"));
        assertEquals(McpPolicyGateway.Outcome.ADMITTED, outcome);
    }

    @Test
    void nullFrameworkAdmits() {
        var outcome = McpPolicyGateway.admit(null, "delete_database", Map.of());
        assertEquals(McpPolicyGateway.Outcome.ADMITTED, outcome);
    }

    @Test
    void nullArgsPreviewAdmits() {
        var outcome = McpPolicyGateway.admit(framework, "search", null);
        assertEquals(McpPolicyGateway.Outcome.ADMITTED, outcome);
    }
}
