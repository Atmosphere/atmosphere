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

import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Gateway that consults the governance plane's admission chain before an
 * MCP {@code tools/call} dispatches to the registered tool handler. The
 * gateway is reflective: {@code atmosphere-ai} is an optional dependency
 * of {@code atmosphere-mcp}, so the class-path check lets MCP deployments
 * that don't install the ai module run unchanged.
 *
 * <p>When the ai module IS present, every MCP tool call flows through
 * {@code PolicyAdmissionGate.admitToolCall(framework, toolName, args)} —
 * the same seam {@code @AiTool} dispatch uses. An MS-schema YAML rule
 * over {@code tool_name} like
 * {@code {field: tool_name, operator: eq, value: delete_database, action: deny}}
 * fires for MCP invocations identically to first-party tools. Partial
 * coverage of OWASP Agentic Top-10 A08 (Supply Chain — plugin abuse via
 * rogue MCP server).</p>
 */
public final class McpPolicyGateway {

    private static final Logger logger = LoggerFactory.getLogger(McpPolicyGateway.class);

    private static final Method ADMIT_TOOL_CALL;
    private static final Class<?> DENIED_CLASS;
    private static final Method DENIED_POLICY_NAME;
    private static final Method DENIED_REASON;

    static {
        Method admit = null;
        Class<?> denied = null;
        Method deniedPolicyName = null;
        Method deniedReason = null;
        try {
            var gateClass = Class.forName("org.atmosphere.ai.governance.PolicyAdmissionGate");
            admit = gateClass.getMethod("admitToolCall",
                    AtmosphereFramework.class, String.class, Map.class);
            denied = Class.forName(
                    "org.atmosphere.ai.governance.PolicyAdmissionGate$Result$Denied");
            deniedPolicyName = denied.getMethod("policyName");
            deniedReason = denied.getMethod("reason");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            logger.debug("atmosphere-ai not on classpath — McpPolicyGateway will admit all calls");
        }
        ADMIT_TOOL_CALL = admit;
        DENIED_CLASS = denied;
        DENIED_POLICY_NAME = deniedPolicyName;
        DENIED_REASON = deniedReason;
    }

    private McpPolicyGateway() { }

    /**
     * Run the admission chain against an MCP tool call. Returns an
     * {@link Outcome.Admitted} when the ai module is absent or when
     * every installed policy admits; returns {@link Outcome.Denied} with
     * the matching policy's name + reason otherwise.
     *
     * @param framework  the Atmosphere framework the server is running under
     * @param toolName   the MCP tool name being invoked
     * @param argsPreview a shallow snapshot of the tool arguments — used
     *                    for audit-log context, not for authorization logic
     */
    public static Outcome admit(AtmosphereFramework framework,
                                 String toolName,
                                 Map<String, Object> argsPreview) {
        if (ADMIT_TOOL_CALL == null || framework == null) {
            return Outcome.ADMITTED;
        }
        try {
            var result = ADMIT_TOOL_CALL.invoke(null, framework, toolName,
                    argsPreview == null ? Map.of() : argsPreview);
            if (DENIED_CLASS != null && DENIED_CLASS.isInstance(result)) {
                var policy = (String) DENIED_POLICY_NAME.invoke(result);
                var reason = (String) DENIED_REASON.invoke(result);
                return new Outcome.Denied(policy, reason);
            }
            return Outcome.ADMITTED;
        } catch (IllegalAccessException | InvocationTargetException e) {
            logger.warn("McpPolicyGateway admission call threw — admitting by default: {}",
                    e.toString());
            return Outcome.ADMITTED;
        }
    }

    /** Whether the ai module is on the classpath and the gateway is live. */
    public static boolean isActive() {
        return ADMIT_TOOL_CALL != null;
    }

    /** Outcome of the admission call — admit-or-deny, mirrors the ai module's Result type. */
    public sealed interface Outcome {
        /** Singleton admit. */
        Outcome ADMITTED = new Admitted();

        record Admitted() implements Outcome { }
        record Denied(String policyName, String reason) implements Outcome { }
    }
}
