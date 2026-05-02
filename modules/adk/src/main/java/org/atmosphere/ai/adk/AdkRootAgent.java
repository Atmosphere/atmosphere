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
package org.atmosphere.ai.adk;

import com.google.adk.agents.BaseAgent;
import org.atmosphere.ai.AgentExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper for swapping ADK's default {@code LlmAgent} root with a caller-provided
 * {@link BaseAgent} sub-tree on a per-request basis. Enables the orchestration
 * agent types ({@link com.google.adk.agents.SequentialAgent},
 * {@link com.google.adk.agents.ParallelAgent},
 * {@link com.google.adk.agents.LoopAgent}) and any custom {@link BaseAgent}
 * subclass to be wired as the request's root, instead of the single
 * {@code LlmAgent} {@link AdkAgentRuntime#buildRequestRunner} builds by default.
 *
 * <h2>Why a per-request override</h2>
 *
 * <p>Two scopes already let you set an ADK root agent before this helper:</p>
 * <ul>
 *   <li><b>Process-wide</b> via
 *       {@link AdkAgentRuntime#setRunner(com.google.adk.runner.Runner)} — you
 *       construct an {@link com.google.adk.runner.Runner} with whatever root
 *       you like and hand the whole runner to the runtime. Every subsequent
 *       request that does <em>not</em> trigger the per-request runner path
 *       (no tools, no cache hint) shares that root.</li>
 *   <li><b>Per-request</b> via this helper — only this request gets the
 *       custom root; the next call falls back to the default {@code LlmAgent}
 *       (or whatever the static runner holds). Useful when different prompts
 *       need different agent topologies (e.g. a coding request goes through a
 *       {@code SequentialAgent[planner, coder, reviewer]} while a quick
 *       lookup goes through a single {@code LlmAgent}).</li>
 * </ul>
 *
 * <h2>What the runtime does when a root is attached</h2>
 *
 * <p>{@link AdkAgentRuntime#buildRequestRunner} reads the slot via {@link #from}
 * and, when non-null, uses the supplied agent verbatim as the
 * {@link com.google.adk.apps.App.Builder#rootAgent} value. That means the
 * runtime will <em>not</em>:</p>
 * <ul>
 *   <li>construct its own {@code LlmAgent} (model + instruction + tools),</li>
 *   <li>attach {@link AdkToolBridge}-translated tools to the supplied root —
 *       tool wiring belongs on the user's leaf {@code LlmAgent}s, since
 *       {@link com.google.adk.agents.SequentialAgent} /
 *       {@link com.google.adk.agents.ParallelAgent} /
 *       {@link com.google.adk.agents.LoopAgent} are orchestration shells that
 *       do not themselves call models. The user is responsible for calling
 *       {@code .tools(...)} on the leaf agents at construction time.</li>
 * </ul>
 *
 * <p>Cache configuration ({@code CacheHint}), gateway admission, lifecycle
 * listeners, history seeding, and the {@code ExecutionHandle} cancel path all
 * still wrap the run — the bridge replaces only the root agent slot, not the
 * surrounding Atmosphere safety rail.</p>
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * <p>The agent rides on {@link AgentExecutionContext#metadata()} under
 * {@link #METADATA_KEY} rather than a typed context field, mirroring
 * {@link org.atmosphere.ai.llm.CacheHint},
 * {@code org.atmosphere.ai.spring.SpringAiAdvisors}, and
 * {@code org.atmosphere.ai.langchain4j.LangChain4jAiServices}. Keeps
 * {@code modules/ai} free of any {@code google-adk} dependency — the
 * {@link BaseAgent} type is only resolved inside this module where ADK is
 * already provided.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Build an ADK orchestration tree (planner -> coder -> reviewer):
 * var planner = LlmAgent.builder().name("planner").model("gemini-2.5-flash")
 *         .instruction("Plan the steps.").build();
 * var coder = LlmAgent.builder().name("coder").model("gemini-2.5-flash")
 *         .instruction("Write the code.").build();
 * var reviewer = LlmAgent.builder().name("reviewer").model("gemini-2.5-flash")
 *         .instruction("Review the code.").build();
 * var pipeline = SequentialAgent.builder()
 *         .name("code-pipeline")
 *         .subAgents(planner, coder, reviewer)
 *         .build();
 *
 * // Attach via an interceptor so every prompt routes through it:
 * @Component
 * class PipelineInterceptor implements AiInterceptor {
 *     @Override
 *     public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
 *         return request.withMetadata(Map.of(
 *                 AdkRootAgent.METADATA_KEY, pipeline));
 *     }
 * }
 * }</pre>
 */
public final class AdkRootAgent {

    /**
     * Canonical metadata slot. The runtime reads from this key only; callers
     * who bypass {@link #attach} (e.g. when mutating an existing metadata map)
     * must use the same string.
     */
    public static final String METADATA_KEY = "adk.rootAgent";

    private AdkRootAgent() {
    }

    /**
     * Read the per-request root agent out of {@code context.metadata()}.
     * Returns {@code null} when no slot is present (the runtime then builds
     * its default {@code LlmAgent} root). A type-mismatched slot throws —
     * silent drops would mask the bridge never firing.
     */
    public static BaseAgent from(AgentExecutionContext context) {
        if (context == null || context.metadata() == null) {
            return null;
        }
        var slot = context.metadata().get(METADATA_KEY);
        if (slot == null) {
            return null;
        }
        if (!(slot instanceof BaseAgent rootAgent)) {
            throw new IllegalArgumentException(
                    METADATA_KEY + " must be a " + BaseAgent.class.getName()
                            + ", got " + slot.getClass().getName());
        }
        return rootAgent;
    }

    /**
     * Return a new context with {@code rootAgent} attached under
     * {@link #METADATA_KEY}. Replaces any previously attached root — unlike
     * {@code SpringAiAdvisors} which appends, an ADK root agent is exclusive:
     * a request has exactly one {@link com.google.adk.apps.App} which has
     * exactly one root agent.
     */
    public static AgentExecutionContext attach(AgentExecutionContext context, BaseAgent rootAgent) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (rootAgent == null) {
            throw new IllegalArgumentException("rootAgent is required");
        }
        Map<String, Object> nextMetadata = new HashMap<>(context.metadata());
        nextMetadata.put(METADATA_KEY, rootAgent);
        return context.withMetadata(Map.copyOf(nextMetadata));
    }
}
