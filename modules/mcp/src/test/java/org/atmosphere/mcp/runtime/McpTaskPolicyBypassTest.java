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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for the MCP session-dialect task-path policy bypass. A
 * {@code tools/call} carrying a {@code "task"} object returned early into
 * {@code acceptToolCallAsTask} <em>before</em> {@code checkToolPolicy} ran, so a
 * client could invoke an otherwise-denied tool simply by adding {@code "task":{}}
 * — even though the synchronous path and the stateless dialect both gate first.
 * Policy admission now runs before the task branch so all four tool-call entry
 * points gate identically (Correctness Invariant #6 Security, #7 Mode Parity).
 */
class McpTaskPolicyBypassTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class Tools {

        public volatile boolean deleteInvoked = false;

        @McpTool(name = "delete_database", description = "drop a database")
        public String deleteDatabase(@McpParam(name = "table") String table) {
            deleteInvoked = true;
            return "deleted:" + table;
        }

        @McpTool(name = "search_documents", description = "search")
        public String search(@McpParam(name = "q") String q) {
            return "results:" + q;
        }
    }

    private Tools toolBean;
    private McpProtocolHandler handler;
    private AtmosphereResource resource;
    private Map<String, Object> properties;

    @BeforeEach
    void setUp() {
        toolBean = new Tools();
        var registry = new McpRegistry();
        registry.scan(toolBean);

        var config = mock(AtmosphereConfig.class);
        var framework = mock(AtmosphereFramework.class);
        properties = new HashMap<>();
        when(config.framework()).thenReturn(framework);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(properties);

        handler = new McpProtocolHandler("test", "1.0", registry, config);
        resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("test-policy-task");
    }

    private void installDeleteDenyPolicy() {
        var policy = new MsAgentOsPolicy(
                "block-delete-database", "yaml:test", "1.0",
                List.of(new MsAgentOsPolicy.Rule(
                        "deny-delete", "tool_name", MsAgentOsPolicy.Operator.EQ,
                        "delete_database", 100, "Deleting databases is not allowed",
                        MsAgentOsPolicy.Action.DENY, null)),
                MsAgentOsPolicy.Action.ALLOW);
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(policy));
    }

    @Test
    void taskAugmentedCallIsDeniedByPolicyAndToolNeverRuns() throws Exception {
        installDeleteDenyPolicy();

        var response = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"delete_database","arguments":{"table":"users"},
                    "task":{}
                }}""");

        var root = mapper.readTree(response);
        var error = root.get("error");
        assertNotNull(error, "task-augmented call to a denied tool must return a JSON-RPC error, "
                + "not a CreateTaskResult: " + response);
        assertTrue(error.get("message").stringValue().toLowerCase().contains("denied by policy"),
                "error must attribute the denial to the policy: " + error);
        assertNull(root.get("result"), "denied call must not produce a task result");
        // The task branch must not have dispatched the tool at all.
        Thread.sleep(100);
        assertFalse(toolBean.deleteInvoked,
                "denied tool must never execute, even off-thread as a task");
    }

    @Test
    void syncCallIsAlsoDeniedByPolicy() throws Exception {
        installDeleteDenyPolicy();

        var response = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                    "name":"delete_database","arguments":{"table":"users"}
                }}""");

        var error = mapper.readTree(response).get("error");
        assertNotNull(error, "synchronous denied call must return an error (control): " + response);
        assertFalse(toolBean.deleteInvoked);
    }

    @Test
    void allowedTaskAugmentedCallStillSucceeds() throws Exception {
        installDeleteDenyPolicy(); // only delete_database is denied

        var response = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                    "name":"search_documents","arguments":{"q":"hi"},
                    "task":{}
                }}""");

        JsonNode result = mapper.readTree(response).get("result");
        assertNotNull(result, "a tool the policy permits must still create a task: " + response);
        assertNotNull(result.get("task"), "result envelope must contain 'task'");
    }
}
