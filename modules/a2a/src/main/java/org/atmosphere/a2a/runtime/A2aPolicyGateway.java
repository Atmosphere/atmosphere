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
package org.atmosphere.a2a.runtime;

import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway that consults the governance plane's admission chain before an A2A
 * message dispatches to a raw {@code @AgentSkill} handler. Mirrors the MCP
 * adapter pattern ({@code McpPolicyGateway}): the gateway is reflective
 * because {@code atmosphere-ai} is an optional dependency of
 * {@code atmosphere-a2a}, so A2A deployments without the ai module run
 * unchanged (no governance plane exists to consult).
 *
 * <p>Pipeline-backed skills ({@link PipelineBackedSkill}) never reach this
 * gateway — their dispatch flows through {@code AiPipeline.execute()}, whose
 * pre-admission loop already evaluates the same policy chain. This gateway
 * closes the parallel gap: a user-written raw handler invoked reflectively by
 * {@link A2aProtocolHandler#executeSkill} used to bypass the policy plane
 * entirely.</p>
 *
 * <p>Admission is <b>message-level</b>: the synthetic {@code AiRequest}
 * carries the inbound message text plus {@code skill_id} / {@code action} /
 * {@code protocol} metadata, so both content policies and MS-schema metadata
 * rules fire exactly as they do on the pipeline path (Correctness Invariant
 * #7 — Mode Parity).</p>
 *
 * <p><b>Fail-closed</b>: when the governance plane is present but the
 * admission call itself fails (linkage or invocation error), the dispatch is
 * DENIED (Correctness Invariant #6 — default deny). This deliberately does
 * not replicate the MCP gateway's historical fail-open on invocation error.
 * Only the genuine absence of {@code atmosphere-ai} from the classpath — no
 * governance plane installed at all — admits, which is runtime truth, not a
 * bypass.</p>
 */
final class A2aPolicyGateway {

    private static final Logger logger = LoggerFactory.getLogger(A2aPolicyGateway.class);

    private static final Method ADMIT;
    private static final Constructor<?> AI_REQUEST_CTOR;
    private static final Class<?> DENIED_CLASS;
    private static final Method DENIED_POLICY_NAME;
    private static final Method DENIED_REASON;

    static {
        Method admit = null;
        Constructor<?> requestCtor = null;
        Class<?> denied = null;
        Method deniedPolicyName = null;
        Method deniedReason = null;
        try {
            var gateClass = Class.forName("org.atmosphere.ai.governance.PolicyAdmissionGate");
            var requestClass = Class.forName("org.atmosphere.ai.AiRequest");
            admit = gateClass.getMethod("admit", AtmosphereFramework.class, requestClass);
            requestCtor = requestClass.getConstructor(String.class, String.class,
                    String.class, String.class, String.class, String.class, String.class,
                    Map.class, List.class);
            denied = Class.forName(
                    "org.atmosphere.ai.governance.PolicyAdmissionGate$Result$Denied");
            deniedPolicyName = denied.getMethod("policyName");
            deniedReason = denied.getMethod("reason");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            logger.warn("atmosphere-ai not on classpath — A2A raw @AgentSkill governance is "
                    + "DISABLED; every raw-handler dispatch is admitted. Add the atmosphere-ai "
                    + "dependency to enforce the installed policy chain.");
        }
        ADMIT = admit;
        AI_REQUEST_CTOR = requestCtor;
        DENIED_CLASS = denied;
        DENIED_POLICY_NAME = deniedPolicyName;
        DENIED_REASON = deniedReason;
    }

    private A2aPolicyGateway() { }

    /**
     * Run the installed admission chain against an inbound A2A message bound
     * for a raw skill handler. Admits when the ai module is absent or when
     * every installed policy admits; denies with the matching policy's name +
     * reason on a policy deny, and denies with a gateway-level reason when
     * the admission machinery itself fails (fail-closed).
     *
     * @param framework   the framework whose installed policy chain governs
     *                    this deployment; {@code null} (not wired) resolves
     *                    no chain and admits
     * @param skillId     the target skill id (metadata for MS-schema rules)
     * @param messageText the inbound message text, or {@code null}
     */
    static Outcome admit(AtmosphereFramework framework, String skillId, String messageText) {
        if (ADMIT == null) {
            return Outcome.ADMITTED;
        }
        try {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("skill_id", skillId == null ? "" : skillId);
            metadata.put("action", "a2a_message");
            metadata.put("protocol", "a2a");
            var request = AI_REQUEST_CTOR.newInstance(
                    messageText == null ? "" : messageText,
                    "", null, null, null, null, null,
                    Map.copyOf(metadata), List.of());
            var result = ADMIT.invoke(null, framework, request);
            if (DENIED_CLASS.isInstance(result)) {
                var policy = (String) DENIED_POLICY_NAME.invoke(result);
                var reason = (String) DENIED_REASON.invoke(result);
                return new Outcome.Denied(policy, reason);
            }
            return Outcome.ADMITTED;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Fail CLOSED: the governance plane exists but could not be
            // consulted — admitting here would turn an infrastructure fault
            // into a policy bypass (Correctness Invariant #6). An Error
            // (e.g. LinkageError) propagates and fails the dispatch outright,
            // which is also closed, never open.
            logger.error("A2aPolicyGateway admission call failed — denying dispatch "
                    + "of skill '{}' (fail-closed)", skillId, e);
            return new Outcome.Denied("a2a-policy-gateway",
                    "policy admission could not be evaluated: " + e);
        }
    }

    /** Whether the ai module is on the classpath and the gateway is live. */
    static boolean isActive() {
        return ADMIT != null;
    }

    /** Outcome of the admission call — admit-or-deny, mirrors the ai module's Result type. */
    sealed interface Outcome {
        /** Singleton admit. */
        Outcome ADMITTED = new Admitted();

        record Admitted() implements Outcome { }

        record Denied(String policyName, String reason) implements Outcome { }
    }
}
