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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.orchestration.InvocationContext;
import org.atmosphere.ai.AgentExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-request bridge for overriding the Semantic Kernel
 * {@link InvocationContext} that {@link SemanticKernelAgentRuntime} passes to
 * {@code ChatCompletionService.getStreamingChatMessageContentsAsync(...)}.
 *
 * <p>SK's {@code InvocationContext} carries the per-invocation execution
 * settings: {@code ToolCallBehavior} (tool dispatch policy + max auto-invoke
 * attempts), {@code PromptExecutionSettings} (temperature, max tokens, etc.),
 * {@code KernelHooks} (function-invoking filters), and the return-mode flag.
 * Without this bridge an Atmosphere caller using SK was locked into the
 * runtime's default {@code allowAllKernelFunctions(hasTools)} +
 * default-everything-else context. This bridge lets advanced callers swap in
 * any {@code InvocationContext} the SK builder produces — for example one
 * with {@code withMaxAutoInvokeAttempts(int)} or a custom
 * {@code KernelHookContext} for function-invoking interception.</p>
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * <p>The context rides on {@link AgentExecutionContext#metadata()} under
 * {@link #METADATA_KEY}, mirroring the {@link org.atmosphere.ai.llm.CacheHint}
 * convention and the four existing sidecar bridges
 * ({@code SpringAiAdvisors}, {@code LangChain4jAiServices},
 * {@code KoogStrategy}, {@code AdkRootAgent}). Keeps {@code modules/ai} free
 * of any Semantic Kernel dependency — the {@link InvocationContext} type is
 * only resolved inside {@code modules/semantic-kernel} where it's already
 * provided.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * var ctx = SemanticKernelInvocation.attach(baseContext,
 *         InvocationContext.builder()
 *                 .withToolCallBehavior(
 *                         ToolCallBehavior.allowAllKernelFunctions(true))
 *                 .build());
 * runtime.execute(ctx, session);
 * }</pre>
 */
public final class SemanticKernelInvocation {

    /**
     * Canonical metadata slot. The runtime reads from this key only.
     */
    public static final String METADATA_KEY = "semantic-kernel.invocationContext";

    private SemanticKernelInvocation() {
    }

    /**
     * Read the per-request {@link InvocationContext} out of
     * {@code context.metadata()}. Returns {@code null} when no slot is
     * present (the runtime then builds its default context via
     * {@link SemanticKernelAgentRuntime#buildInvocationContext}). Type errors
     * throw — silent drops would mask the override never firing.
     */
    public static InvocationContext from(AgentExecutionContext context) {
        if (context == null || context.metadata() == null) {
            return null;
        }
        var slot = context.metadata().get(METADATA_KEY);
        if (slot == null) {
            return null;
        }
        if (!(slot instanceof InvocationContext invocation)) {
            throw new IllegalArgumentException(
                    METADATA_KEY + " must be a " + InvocationContext.class.getName()
                            + ", got " + slot.getClass().getName());
        }
        return invocation;
    }

    /**
     * Return a new context with {@code invocation} attached under
     * {@link #METADATA_KEY}. Replaces any previously attached invocation
     * context — the bridge is exclusive (a request has exactly one
     * {@link InvocationContext}).
     */
    public static AgentExecutionContext attach(
            AgentExecutionContext context, InvocationContext invocation) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (invocation == null) {
            throw new IllegalArgumentException("invocation is required");
        }
        Map<String, Object> nextMetadata = new HashMap<>(context.metadata());
        nextMetadata.put(METADATA_KEY, invocation);
        return context.withMetadata(Map.copyOf(nextMetadata));
    }
}
