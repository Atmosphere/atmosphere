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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import org.atmosphere.ai.AgentExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-request bridge for swapping the AgentScope {@link ReActAgent} that
 * {@link AgentScopeAgentRuntime} dispatches the prompt against. By default
 * the runtime uses the agent installed via
 * {@link AgentScopeAgentRuntime#setNativeClient(ReActAgent)} (or the runtime's
 * auto-configured default). When a caller attaches a different
 * {@code ReActAgent} instance via this bridge, that agent handles the
 * request — useful when different prompts need different agent topologies
 * (e.g. a planning prompt routes through a {@code ReActAgent} pre-loaded
 * with planning tools, while a quick lookup goes through a vanilla agent).
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * <p>The agent rides on {@link AgentExecutionContext#metadata()} under
 * {@link #METADATA_KEY}, mirroring the {@link org.atmosphere.ai.llm.CacheHint}
 * convention and the four other sidecar bridges
 * ({@code SpringAiAdvisors}, {@code LangChain4jAiServices},
 * {@code KoogStrategy}, {@code AdkRootAgent}). Keeps {@code modules/ai} free
 * of any AgentScope dependency — the {@link ReActAgent} type is only
 * resolved inside {@code modules/agentscope} where it's already provided.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * var planner = ReActAgent.builder()
 *         .name("planner")
 *         .tools(planningToolkit)
 *         .build();
 * var ctx = AgentScopeAgent.attach(baseContext, planner);
 * runtime.execute(ctx, session);
 * }</pre>
 */
public final class AgentScopeAgent {

    /**
     * Canonical metadata slot. The runtime reads from this key only.
     */
    public static final String METADATA_KEY = "agentscope.agent";

    private AgentScopeAgent() {
    }

    /**
     * Read the per-request {@link ReActAgent} out of {@code context.metadata()}.
     * Returns {@code null} when no slot is present (the runtime then uses
     * the agent from {@link AgentScopeAgentRuntime#getNativeClient()}). Type
     * errors throw — silent drops would mask the override never firing.
     */
    public static ReActAgent from(AgentExecutionContext context) {
        if (context == null || context.metadata() == null) {
            return null;
        }
        var slot = context.metadata().get(METADATA_KEY);
        if (slot == null) {
            return null;
        }
        if (!(slot instanceof ReActAgent agent)) {
            throw new IllegalArgumentException(
                    METADATA_KEY + " must be a " + ReActAgent.class.getName()
                            + ", got " + slot.getClass().getName());
        }
        return agent;
    }

    /**
     * Return a new context with {@code agent} attached under
     * {@link #METADATA_KEY}. Replaces any previously attached agent — the
     * bridge is exclusive (a request dispatches against exactly one
     * {@link ReActAgent}).
     */
    public static AgentExecutionContext attach(
            AgentExecutionContext context, ReActAgent agent) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (agent == null) {
            throw new IllegalArgumentException("agent is required");
        }
        Map<String, Object> nextMetadata = new HashMap<>(context.metadata());
        nextMetadata.put(METADATA_KEY, agent);
        return context.withMetadata(Map.copyOf(nextMetadata));
    }
}
