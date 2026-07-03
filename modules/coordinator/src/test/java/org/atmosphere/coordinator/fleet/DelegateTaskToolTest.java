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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.coordinator.test.StubAgentFleet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DelegateTaskToolTest {

    private static final DelegateTaskTool TOOL = new DelegateTaskTool();

    private static AgentFleet fleetWith(String agentName, String cannedResponse) {
        return StubAgentFleet.builder().agent(agentName, cannedResponse).build();
    }

    @Test
    public void delegatesToNamedAgentAndReturnsReply() {
        var fleet = fleetWith("research-agent", "Research brief.");

        var reply = TOOL.delegateTask(fleet, "research-agent", "look this up");

        assertEquals("Research brief.", reply);
    }

    @Test
    public void unknownAgentReturnsHelpfulErrorListingAvailable() {
        var fleet = fleetWith("research-agent", "unused");

        var reply = TOOL.delegateTask(fleet, "no-such-agent", "hello");

        assertTrue(reply.contains("unknown agent 'no-such-agent'"), reply);
        assertTrue(reply.contains("research-agent"),
                "the error must list the available fleet agents: " + reply);
    }

    @Test
    public void blankAgentNameReturnsHelpfulError() {
        var fleet = fleetWith("research-agent", "unused");

        var reply = TOOL.delegateTask(fleet, "  ", "hello");

        assertTrue(reply.contains("'agent' is required"), reply);
        assertTrue(reply.contains("research-agent"), reply);
    }

    @Test
    public void missingFleetReturnsToolError() {
        var reply = TOOL.delegateTask(null, "research-agent", "hello");

        assertTrue(reply.contains("no agent fleet"), reply);
    }

    @Test
    public void governanceDenySurfacesAsToolResultError() {
        // The same composition CoordinatorProcessor.applyPresetGovernance
        // builds: the fleet wrapped with GovernanceFleetInterceptor. A Deny
        // must short-circuit the transport hop and surface in the tool result.
        var governed = fleetWith("research-agent", "should never be reached")
                .withInterceptor(new GovernanceFleetInterceptor(List.of(denyAll())));

        var reply = TOOL.delegateTask(governed, "research-agent", "do something off-policy");

        assertTrue(reply.contains("failed"), reply);
        assertTrue(reply.contains("denied"), reply);
        assertTrue(reply.contains("off-policy dispatch"),
                "the policy's deny reason must reach the model: " + reply);
    }

    @Test
    public void registryExcludesFleetParamFromSchemaAndInjectsIt() throws Exception {
        // Registration mechanics: the AgentFleet parameter must be absent from
        // the LLM-facing schema and resolved from the injectables map at
        // dispatch — exactly how AiStreamingSession invokes tools.
        var registry = new DefaultToolRegistry();
        registry.register(new DelegateTaskTool());

        var tool = registry.getTool("delegate_task").orElseThrow(() ->
                new AssertionError("delegate_task must be registered"));
        var paramNames = tool.parameters().stream().map(p -> p.name()).toList();
        assertEquals(List.of("agent", "message"), paramNames,
                "the injected AgentFleet parameter must not leak into the tool schema");

        var fleet = fleetWith("helper", "Helper reply.");
        var result = tool.executor().execute(
                Map.of("agent", "helper", "message", "hi"),
                Map.of(AgentFleet.class, fleet));
        assertEquals("Helper reply.", result);
    }

    private static GovernancePolicy denyAll() {
        return new GovernancePolicy() {
            @Override
            public String name() {
                return "deny-all-test";
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public String source() {
                return "code:" + DelegateTaskToolTest.class.getName();
            }

            @Override
            public PolicyDecision evaluate(PolicyContext context) {
                return new PolicyDecision.Deny("off-policy dispatch");
            }
        };
    }
}
