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
import org.atmosphere.ai.governance.RateLimitPolicy;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * v4 Goals 2 + 4 applied at the MCP protocol layer. Policies published
 * here are consumed by {@code McpPolicyGateway} on every
 * {@code tools/call} — {@code PolicyAdmissionGate.admitToolCall} evaluates
 * the tool invocation against this chain before the
 * {@code @McpTool}-annotated method runs.
 *
 * <p>Atmosphere-unique differentiator: governance on MCP protocol
 * dispatch, streamed over the same transport that serves WebSocket chat
 * to the UI. MS Agent Governance Toolkit has an MCP security gateway
 * but it's HTTP-only; we govern the same dispatch over our streaming
 * transport with no extra wiring.</p>
 *
 * <h2>Policies applied</h2>
 * <ul>
 *   <li>{@link KillSwitchPolicy} — ops can halt every MCP invocation
 *       from the admin endpoint</li>
 *   <li>{@link RateLimitPolicy} — per-client tool-call rate cap</li>
 *   <li>{@link AllowListPolicy} — only known-safe tools admitted by
 *       default; the sensitive {@code ban_user} tool is deliberately
 *       absent from this list so that its invocation is denied unless
 *       the operator explicitly includes it</li>
 *   <li>{@link DenyListPolicy} — catch-all for forbidden argument
 *       content (attempts to execute shell, SQL drops, path traversal)</li>
 * </ul>
 */
@Configuration
public class McpGovernanceConfig {

    private static final Logger logger = LoggerFactory.getLogger(McpGovernanceConfig.class);

    @Bean
    public KillSwitchPolicy mcpKillSwitch() {
        return new KillSwitchPolicy();
    }

    @Bean
    public RateLimitPolicy mcpRateLimit() {
        return new RateLimitPolicy("mcp-tool-rate-limit", 60, Duration.ofSeconds(60));
    }

    /**
     * Safe-by-default tool allow-list. The MCP tool name lands on the
     * policy-evaluation message via {@code admitToolCall} — only messages
     * matching one of these tool names admit. {@code ban_user} is
     * deliberately excluded so its invocation fails closed; operators
     * who actually want it accessible add it to the YAML-backed version
     * of this list.
     */
    @Bean
    public AllowListPolicy mcpToolAllowList() {
        return new AllowListPolicy("mcp-tool-allowlist",
                "list_users", "broadcast_message", "get_chat_stats");
    }

    /** Deny-list on argument content — catches obvious abuse patterns. */
    @Bean
    public DenyListPolicy mcpArgumentDenyList() {
        return DenyListPolicy.fromRegex("mcp-arg-deny-list",
                "(?i)\\bDROP\\s+TABLE\\b",
                "(?i)\\brm\\s+-rf\\s+/",
                "\\.\\./\\.\\./");
    }

    @Bean
    public PolicyPlane policyPlane(AtmosphereFramework framework,
                                    KillSwitchPolicy killSwitch,
                                    RateLimitPolicy rateLimit,
                                    AllowListPolicy allowList,
                                    DenyListPolicy argDenyList) {
        List<GovernancePolicy> policies = List.of(
                killSwitch, rateLimit, allowList, argDenyList);
        framework.getAtmosphereConfig().properties()
                .put(GovernancePolicy.POLICIES_PROPERTY, policies);
        logger.info("Published {} MCP governance policies: {}",
                policies.size(),
                policies.stream().map(GovernancePolicy::name).toList());
        return new PolicyPlane(policies);
    }

    public record PolicyPlane(List<GovernancePolicy> policies) { }
}
