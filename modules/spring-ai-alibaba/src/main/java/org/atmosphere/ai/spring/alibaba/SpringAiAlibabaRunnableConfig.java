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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.atmosphere.ai.AgentExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-request bridge for overriding the Spring AI Alibaba
 * {@link RunnableConfig} that {@link SpringAiAlibabaAgentRuntime} passes to
 * {@code ReactAgent.call(messages, runnableConfig)}.
 *
 * <p>Alibaba's {@link RunnableConfig} is the natural per-invocation handle for
 * the {@code ReactAgent} graph: it carries the {@code threadId} (for memory
 * thread continuation across calls against a checkpointed graph), the
 * {@code checkPointId} (for resuming from a specific checkpoint), the
 * {@code streamMode} ({@code VALUES} / {@code UPDATES} / {@code MESSAGES}),
 * arbitrary {@code metadata} + {@code context} maps, and a {@code Store} for
 * cross-thread state. Without this bridge an Atmosphere caller using Alibaba
 * was locked into the no-arg {@code agent.call(messages)} overload, which
 * skips all of the above.</p>
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * <p>The config rides on {@link AgentExecutionContext#metadata()} under
 * {@link #METADATA_KEY}, mirroring the {@link org.atmosphere.ai.llm.CacheHint}
 * convention and the five other sidecar bridges
 * ({@code SpringAiAdvisors}, {@code LangChain4jAiServices},
 * {@code KoogStrategy}, {@code AdkRootAgent}, {@code SemanticKernelInvocation},
 * {@code EmbabelPromptRunner}, {@code AgentScopeAgent}). Keeps {@code modules/ai}
 * free of any Spring AI Alibaba dependency — the {@link RunnableConfig} type is
 * only resolved inside {@code modules/spring-ai-alibaba} where it's already
 * provided.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * var ctx = SpringAiAlibabaRunnableConfig.attach(baseContext,
 *         RunnableConfig.builder()
 *                 .threadId("user-42-session-7")
 *                 .streamMode(CompiledGraph.StreamMode.VALUES)
 *                 .build());
 * runtime.execute(ctx, session);
 * }</pre>
 */
public final class SpringAiAlibabaRunnableConfig {

    /**
     * Canonical metadata slot. The runtime reads from this key only.
     */
    public static final String METADATA_KEY = "spring-ai-alibaba.runnableConfig";

    private SpringAiAlibabaRunnableConfig() {
    }

    /**
     * Read the per-request {@link RunnableConfig} out of
     * {@code context.metadata()}. Returns {@code null} when no slot is
     * present (the runtime then falls back to the no-arg
     * {@code agent.call(messages)} overload). Type errors throw — silent
     * drops would mask the override never firing.
     */
    public static RunnableConfig from(AgentExecutionContext context) {
        if (context == null || context.metadata() == null) {
            return null;
        }
        var slot = context.metadata().get(METADATA_KEY);
        if (slot == null) {
            return null;
        }
        if (!(slot instanceof RunnableConfig config)) {
            throw new IllegalArgumentException(
                    METADATA_KEY + " must be a " + RunnableConfig.class.getName()
                            + ", got " + slot.getClass().getName());
        }
        return config;
    }

    /**
     * Return a new context with {@code config} attached under
     * {@link #METADATA_KEY}. Replaces any previously attached config — the
     * bridge is exclusive (a request dispatches against exactly one
     * {@link RunnableConfig}).
     */
    public static AgentExecutionContext attach(
            AgentExecutionContext context, RunnableConfig config) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        Map<String, Object> nextMetadata = new HashMap<>(context.metadata());
        nextMetadata.put(METADATA_KEY, config);
        return context.withMetadata(Map.copyOf(nextMetadata));
    }
}
