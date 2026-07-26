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

    /**
     * {@code true} when {@code atmosphere-ai} IS on the classpath but the
     * governance reflection could not be initialized (version skew: the gate
     * class loaded, then a method or the {@code Denied} type did not resolve).
     * That is a broken governance plane, not an absent one, so
     * {@link #admit} fails closed rather than admitting unchecked tool calls
     * (Correctness Invariant #6).
     */
    private static final boolean INIT_BROKEN;

    static {
        Method admit = null;
        Class<?> denied = null;
        Method deniedPolicyName = null;
        Method deniedReason = null;
        boolean initBroken = false;
        Class<?> gateClass = null;
        try {
            gateClass = Class.forName("org.atmosphere.ai.governance.PolicyAdmissionGate");
        } catch (ClassNotFoundException absent) {
            // Regime 1 — governance absent BY DESIGN. GovernancePolicy itself
            // lives in atmosphere-ai, so with the module off the classpath no
            // policy can be installed; admitting matches PolicyAdmissionGate's
            // own empty-chain semantics (an empty chain is an implicit admit).
            logger.warn("atmosphere-ai not on classpath — MCP tools/call governance is DISABLED; "
                    + "every MCP tool call is admitted. Add the atmosphere-ai dependency to "
                    + "enforce PolicyAdmissionGate rules.");
        }
        if (gateClass != null) {
            try {
                admit = gateClass.getMethod("admitToolCall",
                        AtmosphereFramework.class, String.class, Map.class);
                denied = Class.forName(
                        "org.atmosphere.ai.governance.PolicyAdmissionGate$Result$Denied");
                deniedPolicyName = denied.getMethod("policyName");
                deniedReason = denied.getMethod("reason");
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError skew) {
                // Regime 2 — governance PRESENT but its reflective surface did
                // not resolve. All-or-nothing: null every handle so a partially
                // initialized gateway can never silently convert a real Denied
                // into an admit (which is what a resolved admitToolCall + an
                // unresolved Denied class used to do).
                admit = null;
                denied = null;
                deniedPolicyName = null;
                deniedReason = null;
                initBroken = true;
                logger.error("atmosphere-ai is on the classpath but PolicyAdmissionGate "
                        + "reflection failed to initialize — MCP tools/call FAILS CLOSED "
                        + "(every call denied) until the version skew is resolved: {}",
                        skew.toString());
            }
        }
        ADMIT_TOOL_CALL = admit;
        DENIED_CLASS = denied;
        DENIED_POLICY_NAME = deniedPolicyName;
        DENIED_REASON = deniedReason;
        INIT_BROKEN = initBroken;
    }

    private McpPolicyGateway() { }

    /**
     * Run the admission chain against an MCP tool call.
     *
     * <p>Fail-closed contract (Correctness Invariant #6). Three regimes:</p>
     * <ul>
     *   <li><b>Governance absent</b> ({@code atmosphere-ai} off the classpath,
     *       or a null framework) — {@link Outcome.Admitted}. No policy can
     *       exist, matching the ai module's own empty-chain semantics.</li>
     *   <li><b>Governance active</b> — the installed chain decides:
     *       {@link Outcome.Admitted} when every policy admits, otherwise
     *       {@link Outcome.Denied} with the matching policy's name + reason.</li>
     *   <li><b>Governance broken</b> (present but its reflection failed to
     *       initialize, or the admission call itself throws) —
     *       {@link Outcome.Denied}. A governance plane that errored must never
     *       let a tool call through: this is the seam an operator's
     *       {@code tool_name} deny rule relies on, so admitting on error would
     *       silently disarm it.</li>
     * </ul>
     *
     * @param framework  the Atmosphere framework the server is running under
     * @param toolName   the MCP tool name being invoked
     * @param argsPreview a shallow snapshot of the tool arguments — used
     *                    for audit-log context, not for authorization logic
     */
    public static Outcome admit(AtmosphereFramework framework,
                                 String toolName,
                                 Map<String, Object> argsPreview) {
        if (INIT_BROKEN) {
            return new Outcome.Denied("mcp-policy-gateway",
                    "atmosphere-ai is on the classpath but its governance reflection failed "
                            + "to initialize — failing closed");
        }
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
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            // The admission call itself failed (a policy threw, an argument
            // mismatch, a reflective access error). Deny — an errored
            // governance plane is not an admitting one. RuntimeException is
            // caught too so Method.invoke's unchecked failures and any fault in
            // the Denied accessors below also fail closed.
            var cause = e instanceof InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : e;
            logger.error("McpPolicyGateway admission call failed for tool '{}' — denying "
                    + "(fail closed)", toolName, cause);
            return new Outcome.Denied("mcp-policy-gateway",
                    "governance admission call failed: " + cause);
        }
    }

    /**
     * Whether the ai module is on the classpath AND its governance reflection
     * initialized — i.e. the gateway can actually run the admission chain.
     * Reports confirmed runtime state, never classpath presence alone
     * (Correctness Invariant #5): a broken-init gateway is not active even
     * though {@code atmosphere-ai} is present.
     */
    public static boolean isActive() {
        return ADMIT_TOOL_CALL != null && !INIT_BROKEN;
    }

    /** Outcome of the admission call — admit-or-deny, mirrors the ai module's Result type. */
    public sealed interface Outcome {
        /** Singleton admit. */
        Outcome ADMITTED = new Admitted();

        record Admitted() implements Outcome { }
        record Denied(String policyName, String reason) implements Outcome { }
    }
}
