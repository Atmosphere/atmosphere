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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Applies the framework's installed {@link GovernancePolicy} chain to an
 * {@link AiRequest} without invoking an {@link org.atmosphere.ai.AgentRuntime}.
 * Same pre-admission semantics as {@link org.atmosphere.ai.AiPipeline} — exists
 * for code paths that produce responses locally (demo responders, canned
 * replies, in-process simulators) and therefore never reach the pipeline.
 *
 * <p>Without this gate, any {@code @Prompt} handler that writes directly to
 * {@link org.atmosphere.ai.StreamingSession#send(String)} bypasses the policy
 * plane — the sample's demo fallback was the first victim. The gate makes
 * "reach for governance" a one-liner: read policies from the framework's
 * {@link GovernancePolicy#POLICIES_PROPERTY} bag, evaluate the chain, return
 * the {@link Result} so the caller decides how to render denial / redaction.</p>
 *
 * <p>Fail-closed: a policy that throws during evaluation denies the turn.
 * Same contract as {@link org.atmosphere.ai.AiPipeline#execute} (Correctness
 * Invariant #2).</p>
 */
public final class PolicyAdmissionGate {

    private static final Logger logger = LoggerFactory.getLogger(PolicyAdmissionGate.class);

    private PolicyAdmissionGate() { }

    /** Outcome of the admission pass: possibly-rewritten request or a denial. */
    public sealed interface Result {
        /** Admitted unchanged or with a non-material transform — forward {@link #request()} to the responder. */
        record Admitted(AiRequest request) implements Result { }

        /** Denied by {@link #policyName()} for {@link #reason()}. Caller emits an error on the session. */
        record Denied(String policyName, String reason) implements Result { }
    }

    /**
     * Run the admission chain against the message and return the outcome.
     * Policies are loaded from the framework's
     * {@link GovernancePolicy#POLICIES_PROPERTY} (populated by
     * {@code AiEndpointProcessor}, Spring / Quarkus auto-config, or direct
     * programmatic publish). An empty list is treated as implicit admit.
     */
    public static Result admit(AtmosphereFramework framework, AiRequest request) {
        if (framework == null || request == null) {
            return new Result.Admitted(request);
        }
        var config = framework.getAtmosphereConfig();
        if (config == null) {
            return new Result.Admitted(request);
        }
        var raw = config.properties().get(GovernancePolicy.POLICIES_PROPERTY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return new Result.Admitted(request);
        }
        var current = request;
        for (var entry : list) {
            if (!(entry instanceof GovernancePolicy policy)) {
                continue;
            }
            try {
                var decision = policy.evaluate(PolicyContext.preAdmission(current));
                switch (decision) {
                    case PolicyDecision.Deny deny -> {
                        logger.warn("Request denied by policy {} (source={}, version={}): {}",
                                policy.name(), policy.source(), policy.version(), deny.reason());
                        return new Result.Denied(policy.name(), deny.reason());
                    }
                    case PolicyDecision.Transform transform -> current = transform.modifiedRequest();
                    case PolicyDecision.Admit ignored -> { }
                }
            } catch (Exception e) {
                logger.error("GovernancePolicy.evaluate failed (policy={}): fail-closed",
                        policy.name(), e);
                return new Result.Denied(policy.name(),
                        "policy evaluation failed: " + e.getMessage());
            }
        }
        return new Result.Admitted(current);
    }

    /** Convenience overload that pulls the framework off an {@link AtmosphereResource}. */
    public static Result admit(AtmosphereResource resource, AiRequest request) {
        if (resource == null) {
            return new Result.Admitted(request);
        }
        var config = resource.getAtmosphereConfig();
        return admit(config == null ? null : config.framework(), request);
    }
}
