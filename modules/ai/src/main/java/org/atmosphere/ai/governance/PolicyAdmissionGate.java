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
import org.atmosphere.ai.governance.scope.ScopePolicyInstaller;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // Consume any per-request ScopePolicy install before iterating the
        // framework-scoped policy chain. Mirrors AiPipeline / AiStreamingSession
        // so direct admission-gate callers (demo responders, in-process
        // simulators, channel bridges that bypass AiPipeline) honour the same
        // per-request scope contract the streaming path does.
        var current = request;
        var mutableMeta = new java.util.HashMap<String, Object>(
                current.metadata() != null ? current.metadata() : Map.of());
        var requestScope = ScopePolicyInstaller.extract(mutableMeta);
        if (requestScope != null) {
            current = new AiRequest(current.message(), current.systemPrompt(),
                    current.model(), current.userId(), current.sessionId(),
                    current.agentId(), current.conversationId(),
                    Map.copyOf(mutableMeta), current.history());
        }
        var raw = config.properties().get(GovernancePolicy.POLICIES_PROPERTY);
        var installedPolicies = raw instanceof List<?> list ? list : List.of();
        if (requestScope == null && installedPolicies.isEmpty()) {
            return new Result.Admitted(current);
        }
        var effectiveChain = new ArrayList<GovernancePolicy>(installedPolicies.size() + 1);
        if (requestScope != null) {
            effectiveChain.add(requestScope);
        }
        for (var entry : installedPolicies) {
            if (entry instanceof GovernancePolicy gp) {
                effectiveChain.add(gp);
            }
        }
        for (var policy : effectiveChain) {
            var ctx = PolicyContext.preAdmission(current);
            var tracer = GovernanceTracer.start(policy, ctx);
            var startNs = System.nanoTime();
            try {
                var decision = policy.evaluate(ctx);
                var evalMs = (System.nanoTime() - startNs) / 1_000_000.0;
                switch (decision) {
                    case PolicyDecision.Deny deny -> {
                        logger.warn("Request denied by policy {} (source={}, version={}): {}",
                                policy.name(), policy.source(), policy.version(), deny.reason());
                        GovernanceDecisionLog.installed().record(
                                GovernanceDecisionLog.entry(policy, ctx, "deny", deny.reason(), evalMs));
                        tracer.end("deny", deny.reason());
                        return new Result.Denied(policy.name(), deny.reason());
                    }
                    case PolicyDecision.Transform transform -> {
                        GovernanceDecisionLog.installed().record(
                                GovernanceDecisionLog.entry(policy, ctx, "transform",
                                        "request rewritten", evalMs));
                        tracer.end("transform", "request rewritten");
                        current = transform.modifiedRequest();
                    }
                    case PolicyDecision.Admit ignored -> {
                        GovernanceDecisionLog.installed().record(
                                GovernanceDecisionLog.entry(policy, ctx, "admit", "", evalMs));
                        tracer.end("admit", "");
                    }
                }
            } catch (Exception e) {
                var evalMs = (System.nanoTime() - startNs) / 1_000_000.0;
                logger.error("GovernancePolicy.evaluate failed (policy={}): fail-closed",
                        policy.name(), e);
                GovernanceDecisionLog.installed().record(
                        GovernanceDecisionLog.entry(policy, ctx, "error",
                                "evaluate threw: " + e.getMessage(), evalMs));
                tracer.end("error", e.getMessage());
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

    /**
     * Run the admission chain against a tool-call intent. Builds a synthetic
     * {@link AiRequest} whose metadata carries {@code tool_name}, {@code action},
     * and (when present) a preview of the tool arguments so MS-schema rules like
     * {@code {field: tool_name, operator: eq, value: delete_database, action: deny}}
     * fire before the tool executes.
     *
     * <p>This is the admission seam for <b>OWASP Agentic Top-10 #A02 Tool Misuse</b>.
     * A {@link org.atmosphere.ai.tool.ToolExecutionHelper} call site consults this
     * gate before invoking the tool's executor when governance is in play.</p>
     */
    public static Result admitToolCall(AtmosphereFramework framework, String toolName,
                                        Map<String, Object> args) {
        if (toolName == null || toolName.isBlank()) {
            return new Result.Admitted(new AiRequest(""));
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("tool_name", toolName);
        metadata.put("action", "call_tool");
        if (args != null && !args.isEmpty()) {
            metadata.put("tool_args_preview", previewArgs(args));
        }
        var request = new AiRequest(
                "call_tool:" + toolName,
                "", null, null, null, null, null,
                Map.copyOf(metadata), List.of());
        return admit(framework, request);
    }

    /** Resource-aware overload — reads the framework off the resource. */
    public static Result admitToolCall(AtmosphereResource resource, String toolName,
                                        Map<String, Object> args) {
        if (resource == null) {
            return new Result.Admitted(new AiRequest(""));
        }
        var config = resource.getAtmosphereConfig();
        return admitToolCall(config == null ? null : config.framework(), toolName, args);
    }

    private static String previewArgs(Map<String, Object> args) {
        var rendered = args.toString();
        return rendered.length() > 200 ? rendered.substring(0, 200) + "…" : rendered;
    }
}
