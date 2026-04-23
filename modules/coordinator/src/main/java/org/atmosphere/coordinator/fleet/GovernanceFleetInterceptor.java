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
 * configured policies. Deny short-circuits the dispatch; transform
 * rewrites the message field back into the call's args; admit proceeds.
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
                case PolicyDecision.Deny deny -> {
                    logger.info("Fleet dispatch denied by {}: {}", policy.name(), deny.reason());
                    return Decision.deny(deny.reason());
                }
                case PolicyDecision.Transform transform -> {
                    // Rewrite the call's message-proxy so the next policy sees it.
                    context = PolicyContext.preAdmission(transform.modifiedRequest());
                    currentCall = new AgentCall(currentCall.agentName(),
                            currentCall.skill(),
                            java.util.Map.copyOf(currentCall.args()));
                }
            }
        }
        return currentCall == call ? Decision.proceed() : Decision.rewrite(currentCall);
    }

    private static String summarize(AgentCall call) {
        return call.skill() + " " + call.args().toString();
    }
}
