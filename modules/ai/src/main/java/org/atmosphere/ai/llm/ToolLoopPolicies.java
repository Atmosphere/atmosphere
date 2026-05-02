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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AgentExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper for attaching a {@link ToolLoopPolicy} to an
 * {@link AgentExecutionContext} and reading it back inside a runtime.
 *
 * <p>Mirrors the metadata-sidecar convention used by
 * {@link CacheHint#from(AgentExecutionContext)},
 * {@code SpringAiAdvisors}, {@code LangChain4jAiServices}, and
 * {@code AdkRootAgent}. Keeping the policy in {@code metadata()} rather than
 * adding a 20th typed field on {@link AgentExecutionContext} avoids a fresh
 * constructor-shim layer and lets the SPI grow without rippling through
 * every call site that constructs a context positionally.</p>
 *
 * <h2>What the runtimes do</h2>
 *
 * <p>The Built-in runtime ({@link BuiltInAgentRuntime}) reads this slot from
 * {@code context.metadata()} via {@link #from} inside its
 * {@code buildRequest(...)} and stamps it onto {@link ChatCompletionRequest}
 * so the {@link OpenAiCompatibleClient} tool loop honors the iteration cap
 * and overflow behavior.</p>
 *
 * <p>Framework runtimes (LangChain4j, Spring AI, ADK, Koog) read the same
 * slot and translate it to their framework's native loop limit when one
 * exists — see each module's README for the exact translation. When a
 * framework has no mappable knob, the policy is honored as a hint and the
 * runtime logs once at request time.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Strict cap for a tool-driven agent — fail-fast on overflow:
 * var ctx = ToolLoopPolicies.attach(baseContext, ToolLoopPolicy.strict(3));
 * runtime.execute(ctx, session);
 *
 * // Or via an interceptor so every prompt picks up the same policy:
 * @Component
 * class ToolLoopInterceptor implements AiInterceptor {
 *     @Override
 *     public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
 *         return request.withMetadata(Map.of(
 *                 ToolLoopPolicies.METADATA_KEY, ToolLoopPolicy.maxIterations(10)));
 *     }
 * }
 * }</pre>
 */
public final class ToolLoopPolicies {

    /**
     * Canonical metadata slot. Runtime authors read from this key only;
     * callers that bypass {@link #attach} (e.g. when mutating an existing
     * metadata map) must use the same string.
     */
    public static final String METADATA_KEY = "ai.toolLoop.policy";

    private ToolLoopPolicies() {
    }

    /**
     * Read the per-request policy out of {@code context.metadata()}.
     * Returns {@code null} when no slot is present (the runtime then
     * applies {@link ToolLoopPolicy#DEFAULT}). A type-mismatched slot
     * throws — silent drops on a misconfigured policy would mask the
     * cap never firing.
     */
    public static ToolLoopPolicy from(AgentExecutionContext context) {
        if (context == null || context.metadata() == null) {
            return null;
        }
        var slot = context.metadata().get(METADATA_KEY);
        if (slot == null) {
            return null;
        }
        if (!(slot instanceof ToolLoopPolicy policy)) {
            throw new IllegalArgumentException(
                    METADATA_KEY + " must be a " + ToolLoopPolicy.class.getName()
                            + ", got " + slot.getClass().getName());
        }
        return policy;
    }

    /**
     * Read the per-request policy with a fallback to {@link ToolLoopPolicy#DEFAULT}.
     * Convenience for runtime authors who want a non-null result without the
     * null-check at every site.
     */
    public static ToolLoopPolicy fromOrDefault(AgentExecutionContext context) {
        var p = from(context);
        return p != null ? p : ToolLoopPolicy.DEFAULT;
    }

    /**
     * Return a new context with {@code policy} attached under
     * {@link #METADATA_KEY}. Replaces any previously attached policy —
     * a request has exactly one tool-loop policy.
     */
    public static AgentExecutionContext attach(AgentExecutionContext context, ToolLoopPolicy policy) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy is required");
        }
        Map<String, Object> nextMetadata = new HashMap<>(context.metadata());
        nextMetadata.put(METADATA_KEY, policy);
        return context.withMetadata(Map.copyOf(nextMetadata));
    }
}
