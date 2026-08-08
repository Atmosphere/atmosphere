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
package org.atmosphere.samples.mcp;

import org.atmosphere.ai.governance.AllowListPolicy;
import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.PolicyAdmissionGate;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.RateLimitPolicy;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Governance applied to the MCP protocol layer. McpPolicyGateway
 * evaluates {@code tools/call} invocations through the policy chain
 * published here; this test locks down the chain shape and proves the
 * policies decide correctly on representative MCP tool invocations.
 *
 * <p>Atmosphere differentiator: MCP dispatch governance streamed over
 * the same transport as the UI. MS Agent Framework's MCP gateway is
 * HTTP-only; ours governs the protocol over the streaming transport.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false" })
class McpGovernanceE2ETest {

    @Autowired AtmosphereFramework framework;
    @Autowired KillSwitchPolicy killSwitch;
    @Autowired RateLimitPolicy rateLimit;
    @Autowired AllowListPolicy toolAllowList;
    @Autowired DenyListPolicy argDenyList;

    /**
     * Admit a tool call through the same seam {@code McpPolicyGateway} uses, so
     * the request shape under test is the real one. This helper used to hand-build
     * a context with the arguments concatenated into the message; that made
     * {@link #goal2_argumentDenyListCatchesInjectionAttempts} pass against a
     * deny-list that never actually read an argument, because on the real seam the
     * message is only {@code "call_tool:<name>"} and the payload rides in metadata.
     */
    private PolicyDecision decide(GovernancePolicy policy, String toolName,
                                  Map<String, Object> args) {
        var result = PolicyAdmissionGate.admitToolCall(List.of(policy), toolName, args);
        return result instanceof PolicyAdmissionGate.Result.Denied denied
                ? PolicyDecision.deny(denied.reason())
                : PolicyDecision.admit();
    }

    @Test
    void goal2_policyChainPublishedForMcpGateway() {
        @SuppressWarnings("unchecked")
        var installed = (List<GovernancePolicy>) framework.getAtmosphereConfig()
                .properties().get(GovernancePolicy.POLICIES_PROPERTY);
        assertNotNull(installed,
                "McpGovernanceConfig must publish policies on POLICIES_PROPERTY");
        assertEquals(4, installed.size());
        var names = installed.stream().map(GovernancePolicy::name).toList();
        assertTrue(names.containsAll(Set.of("kill-switch", "mcp-tool-rate-limit",
                "mcp-tool-allowlist", "mcp-arg-deny-list")),
                "all four MCP admission policies must install, got: " + names);
    }

    @Test
    void goal2_allowListAdmitsSafeTool() {
        assertInstanceOf(PolicyDecision.Admit.class,
                decide(toolAllowList, "list_users", Map.of()));
    }

    @Test
    void goal2_allowListDeniesUnknownToolByDefault() {
        // ban_user is deliberately NOT in the allow-list — operators must
        // opt in before that sensitive tool is callable.
        var decision = decide(toolAllowList, "ban_user", Map.of("uuid", "abc"));
        assertInstanceOf(PolicyDecision.Deny.class, decision,
                "default-deny allow-list must block ban_user until explicitly allowed");
    }

    @Test
    void goal2_argumentDenyListCatchesInjectionAttempts() {
        // A tool-call whose args carry a SQL drop attempt is denied even
        // when the tool name itself is on the allow-list. The arguments go in
        // as arguments — not pre-rendered into the message — so this fails if
        // the deny-list ever stops reading them.
        assertInstanceOf(PolicyDecision.Deny.class,
                decide(argDenyList, "broadcast_message", Map.of("body", "'; DROP TABLE users;'")));
        assertInstanceOf(PolicyDecision.Deny.class,
                decide(argDenyList, "atmosphere_version", Map.of("path", "../../etc/passwd")));
        assertInstanceOf(PolicyDecision.Admit.class,
                decide(argDenyList, "broadcast_message", Map.of("body", "maintenance at 10pm")));
    }

    @Test
    void goal2_killSwitchHaltsAllMcpTraffic() {
        try {
            killSwitch.arm("incident-mcp", "e2e");
            var decision = decide(killSwitch, "list_users", Map.of());
            assertInstanceOf(PolicyDecision.Deny.class, decision,
                    "armed kill switch must deny even safe MCP tool calls");
        } finally {
            killSwitch.disarm();
        }
    }

    @Test
    void goal2_rateLimitConfiguredForMcpClients() {
        assertEquals(60, rateLimit.limit());
        assertEquals(60, rateLimit.window().toSeconds());
    }

    @Test
    void goal4_policyChainBacksOwaspA02A08Evidence() {
        // OWASP A02 (tool misuse) and A08 (supply chain / MCP plugin) both
        // point at PolicyAdmissionGate.admitToolCall as evidence. The
        // EvidenceConsumerGrepPinTest proves a production consumer exists;
        // here we prove it's THIS sample for the MCP angle.
        @SuppressWarnings("unchecked")
        var installed = (List<GovernancePolicy>) framework.getAtmosphereConfig()
                .properties().get(GovernancePolicy.POLICIES_PROPERTY);
        assertTrue(installed.stream().anyMatch(p -> p instanceof AllowListPolicy),
                "OWASP A02/A08 evidence: allow-list on MCP tool names is live here");
        assertTrue(installed.stream().anyMatch(p -> p instanceof DenyListPolicy),
                "OWASP A04-adjacent evidence: arg deny-list catches injection attempts");
    }
}
