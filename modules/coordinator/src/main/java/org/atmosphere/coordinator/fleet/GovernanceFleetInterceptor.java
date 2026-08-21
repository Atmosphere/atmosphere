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

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Bridge from {@link FleetInterceptor} to a {@link GovernancePolicy} chain.
 * Each outbound {@link AgentCall} is synthesized into an {@link AiRequest}
 * (skill + serialized args as message text) and evaluated against the
 * configured policies. Deny short-circuits the dispatch; admit proceeds.
 * Transform is re-applied per String arg value — the policy is evaluated
 * once more against each arg so message rewrites (PII redaction, scope
 * redirect) land on the exact payload the target agent receives.
 *
 * <p>Goal-hijacking prevention at the agent-to-agent edge.
 * A coordinator dispatching {@code call("research", "write_code", …)} is
 * the same goal-hijacking risk as a user prompting a support bot with
 * "write Python" — both must pass the same scope policy. Using this
 * interceptor turns every cross-agent dispatch into a governance decision.</p>
 *
 * <p>Example wiring in a {@code @Prompt} handler:</p>
 * <pre>{@code
 * @Autowired List<GovernancePolicy> policies;
 *
 * @Prompt
 * public void onPrompt(String msg, AgentFleet fleet, StreamingSession s) {
 *     var governed = fleet.withInterceptor(new GovernanceFleetInterceptor(policies));
 *     var research = governed.agent("research").call("web_search", ...);
 * }
 * }</pre>
 */
public final class GovernanceFleetInterceptor implements FleetInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceFleetInterceptor.class);

    private final List<GovernancePolicy> policies;

    public GovernanceFleetInterceptor(List<GovernancePolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        this.policies = List.copyOf(policies);
    }

    @Override
    public Decision before(AgentCall call) {
        if (policies.isEmpty()) {
            return Decision.proceed();
        }
        var synthetic = new AiRequest(summarize(call),
                null, null, null, null, null, null,
                java.util.Map.of("fleet.dispatch.agent", call.agentName(),
                        "fleet.dispatch.skill", call.skill()),
                null);
        var context = PolicyContext.preAdmission(synthetic);

        var currentCall = call;
        for (var policy : policies) {
            PolicyDecision decision;
            try {
                decision = policy.evaluate(context);
            } catch (RuntimeException e) {
                logger.error("GovernancePolicy {} threw during fleet dispatch — fail-closed",
                        policy.name(), e);
                return Decision.deny("policy '" + policy.name() + "' evaluation failed");
            }
            switch (decision) {
                case PolicyDecision.Admit ignored -> { /* next policy */ }
                case PolicyDecision.Prefer prefer -> {
                    // Soft governance on the dispatch edge: advisory only — the call
                    // proceeds. Logged, not enforced (a hard block would use Deny).
                    logger.debug("Fleet dispatch preferred alternative from {} ({}): {}",
                            policy.name(), prefer.reason(), prefer.preferred());
                }
                case PolicyDecision.Deny deny -> {
                    logger.info("Fleet dispatch denied by {}: {}", policy.name(), deny.reason());
                    return Decision.deny(deny.reason());
                }
                case PolicyDecision.Transform transform -> {
                    // Rewrite the call's message-proxy so the next policy sees it.
                    context = PolicyContext.preAdmission(transform.modifiedRequest());
                    // The transformed message is the summarized call — a
                    // skill + args.toString() concatenation with no inverse
                    // mapping onto the structured args. Land the rewrite on
                    // the real payload instead: re-run the policy against
                    // each String arg value and apply its per-arg decision.
                    var result = transformArgs(policy, currentCall);
                    if (result.denyReason() != null) {
                        logger.info("Fleet dispatch denied by {} during arg transform: {}",
                                policy.name(), result.denyReason());
                        return Decision.deny(result.denyReason());
                    }
                    if (result.call() == currentCall) {
                        logger.warn("Policy {} transformed the summarized dispatch of "
                                + "agent={} skill={} but no String arg matched — the "
                                + "rewrite could not be applied to the structured args "
                                + "and the call proceeds unchanged",
                                policy.name(), currentCall.agentName(), currentCall.skill());
                    }
                    currentCall = result.call();
                }
            }
        }
        return currentCall == call ? Decision.proceed() : Decision.rewrite(currentCall);
    }

    /** Outcome of the per-arg transform pass: the (possibly rewritten) call, or a deny. */
    private record ArgTransformResult(AgentCall call, String denyReason) { }

    /**
     * Apply a transforming policy to each String arg value. The policy is
     * evaluated once per String arg with that value as the request message,
     * so message-rewriting policies (PII redaction, scope redirect) land on
     * the exact payload the target agent receives. Non-String values pass
     * through untouched — a message rewrite has no defined projection onto
     * structured values. A per-arg Deny fails the dispatch closed.
     */
    private static ArgTransformResult transformArgs(GovernancePolicy policy, AgentCall call) {
        var rewritten = new java.util.LinkedHashMap<String, Object>(call.args());
        var changed = false;
        for (var entry : rewritten.entrySet()) {
            if (!(entry.getValue() instanceof String argValue)) {
                continue;
            }
            var argRequest = new AiRequest(argValue,
                    null, null, null, null, null, null,
                    java.util.Map.of("fleet.dispatch.agent", call.agentName(),
                            "fleet.dispatch.skill", call.skill(),
                            "fleet.dispatch.arg", entry.getKey()),
                    null);
            PolicyDecision decision;
            try {
                decision = policy.evaluate(PolicyContext.preAdmission(argRequest));
            } catch (RuntimeException e) {
                logger.error("GovernancePolicy {} threw during per-arg transform — fail-closed",
                        policy.name(), e);
                return new ArgTransformResult(call,
                        "policy '" + policy.name() + "' evaluation failed on arg '"
                                + entry.getKey() + "'");
            }
            switch (decision) {
                case PolicyDecision.Transform argTransform -> {
                    var modified = argTransform.modifiedRequest().message();
                    if (modified != null && !modified.equals(argValue)) {
                        entry.setValue(modified);
                        changed = true;
                    }
                }
                case PolicyDecision.Deny deny -> {
                    return new ArgTransformResult(call, deny.reason());
                }
                case PolicyDecision.Admit ignored -> { /* arg passes unchanged */ }
                case PolicyDecision.Prefer ignored -> { /* advisory only */ }
            }
        }
        if (!changed) {
            return new ArgTransformResult(call, null);
        }
        return new ArgTransformResult(new AgentCall(call.agentName(), call.skill(),
                java.util.Map.copyOf(rewritten)), null);
    }

    private static String summarize(AgentCall call) {
        return call.skill() + " " + call.args().toString();
    }
}
