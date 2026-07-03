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

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Built-in LLM-callable delegation tool for {@code @Coordinator} fleets:
 * {@code delegate_task(agent, message)} dispatches a natural-language task to
 * a named fleet member and returns its reply. Registered by
 * {@code CoordinatorProcessor} only when the deep-agent preset applies to the
 * coordinator's path — without it, the model can reach sub-agents only
 * through hand-written per-crew {@code @AiTool} wrappers.
 *
 * <p>The {@link AgentFleet} parameter is framework-injected from the session's
 * injectables map ({@code DefaultToolRegistry.isFrameworkInjectableType}
 * whitelists it), so the tool always sees the same fleet the {@code @Prompt}
 * method received — including any governance / journal / activity wrappers.
 * A governance deny surfaces as the synthetic failure text of the denied
 * {@link AgentResult}, returned to the model as an ordinary tool result.</p>
 */
public final class DelegateTaskTool {

    private static final Logger logger = LoggerFactory.getLogger(DelegateTaskTool.class);

    /** The NL skill every {@code @Agent} worker registers (routes to its @Prompt pipeline). */
    static final String DEFAULT_SKILL = "default";

    /** The NL skill a nested {@code @Coordinator} registers instead of {@value #DEFAULT_SKILL}. */
    static final String COORDINATOR_SKILL = "chat";

    @AiTool(name = "delegate_task",
            description = "Delegate a task to a named sub-agent in the fleet and return its "
                    + "reply. Use when a specialised agent should handle part of the request.")
    public String delegateTask(
            AgentFleet fleet,
            @Param(value = "agent",
                    description = "Name of the fleet agent to delegate to") String agent,
            @Param(value = "message",
                    description = "The task or question to send to the agent") String message) {
        if (fleet == null) {
            return "delegate_task unavailable: no agent fleet is bound to this session.";
        }
        if (agent == null || agent.isBlank()) {
            return "delegate_task failed: 'agent' is required. Available agents: "
                    + availableNames(fleet);
        }
        AgentProxy proxy;
        try {
            proxy = fleet.agent(agent.trim());
        } catch (IllegalArgumentException e) {
            return "delegate_task failed: unknown agent '" + agent
                    + "'. Available agents: " + availableNames(fleet);
        }
        var args = Map.<String, Object>of("message", message == null ? "" : message);
        var result = proxy.call(DEFAULT_SKILL, args);
        if (!result.success() && result.text() != null
                && result.text().contains("Unknown skill")) {
            // Nested @Coordinator fleet members register "chat" instead of
            // the worker "default" skill — retry once on the coordinator NL
            // surface before reporting failure.
            result = proxy.call(COORDINATOR_SKILL, args);
        }
        if (!result.success()) {
            logger.info("delegate_task to '{}' failed: {}", agent, result.text());
            return "delegate_task to '" + agent + "' failed: "
                    + (result.text() != null && !result.text().isBlank()
                            ? result.text() : "no error detail");
        }
        return result.text();
    }

    private static String availableNames(AgentFleet fleet) {
        return fleet.available().stream().map(AgentProxy::name).toList().toString();
    }
}
