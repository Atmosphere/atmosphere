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

import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for an argument deny-list that was inert on the seam it was
 * written for. {@code PolicyAdmissionGate.admitToolCall} synthesises the
 * message as {@code "call_tool:<name>"} and carries the caller's payload in
 * metadata; {@link DenyListPolicy} screened only the message, so a rule like
 * {@code \bDROP\s+TABLE\b} never saw a single argument. Every documented
 * argument-injection block admitted and the {@code @McpTool} method ran.
 *
 * <p>This drives the real {@code tools/call} entry point with the deny-list
 * {@code samples/spring-boot-mcp-server} publishes, so the assertion is the
 * one its README makes: denied by the policy, method never called.</p>
 */
class McpArgumentDenyListTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class Tools {

        public volatile String broadcastBody;

        @McpTool(name = "broadcast_message", description = "broadcast to every client")
        public String broadcast(@McpParam(name = "body") String body) {
            broadcastBody = body;
            return "{\"recipients\":0,\"status\":\"sent\"}";
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
        when(resource.uuid()).thenReturn("test-arg-deny-list");
    }

    /** Byte-for-byte the deny-list published by McpGovernanceConfig in the sample. */
    private void installArgumentDenyList() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                DenyListPolicy.fromRegex("mcp-arg-deny-list",
                        "(?i)\\bDROP\\s+TABLE\\b",
                        "(?i)\\brm\\s+-rf\\s+/",
                        "\\.\\./\\.\\./")));
    }

    private String callBroadcast(Object id, String body) {
        return handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{
                    "name":"broadcast_message","arguments":{"body":%s}
                }}""".formatted(id, mapper.writeValueAsString(body)));
    }

    private void assertDeniedAndNotExecuted(String response) {
        var root = mapper.readTree(response);
        var error = root.get("error");
        assertNotNull(error, "argument matching the deny-list must return a JSON-RPC error: "
                + response);
        assertTrue(error.get("message").stringValue().toLowerCase().contains("denied by policy"),
                "error must attribute the denial to the policy: " + error);
        assertNull(root.get("result"), "denied call must not produce a tool result");
        assertNull(toolBean.broadcastBody,
                "the @McpTool method must never run when an argument is denied");
    }

    @Test
    void sqlDropInArgumentIsDeniedAndToolNeverRuns() {
        installArgumentDenyList();
        assertDeniedAndNotExecuted(callBroadcast(1, "'; DROP TABLE users;'"));
    }

    @Test
    void shellWipeInArgumentIsDeniedAndToolNeverRuns() {
        installArgumentDenyList();
        assertDeniedAndNotExecuted(callBroadcast(2, "then run rm -rf / to clean up"));
    }

    @Test
    void pathTraversalInArgumentIsDeniedAndToolNeverRuns() {
        installArgumentDenyList();
        assertDeniedAndNotExecuted(callBroadcast(3, "read ../../etc/passwd"));
    }

    @Test
    void benignArgumentStillReachesTheTool() {
        installArgumentDenyList();

        var response = callBroadcast(4, "server maintenance at 10pm");

        var root = mapper.readTree(response);
        assertNull(root.get("error"), "a benign argument must not be denied: " + response);
        assertNotNull(root.get("result"), "admitted call must produce a tool result: " + response);
        assertEquals("server maintenance at 10pm", toolBean.broadcastBody,
                "admitted call must actually execute the tool");
    }
}
