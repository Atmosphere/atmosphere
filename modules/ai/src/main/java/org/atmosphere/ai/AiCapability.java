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
package org.atmosphere.ai;

/**
 * Capabilities that an AI adapter or model may support. Used for:
 * <ul>
 *   <li>Smart model routing — only fail over to models with matching capabilities</li>
 *   <li>Tool calling — don't offer tools to a model that can't call them</li>
 *   <li>Multi-modal — don't send images to a text-only model</li>
 *   <li>Feature negotiation — endpoints can declare required capabilities</li>
 * </ul>
 *
 * <p>Adapters declare their capabilities via
 * {@link AiStreamingAdapter#capabilities()} and {@link AiSupport#capabilities()}.
 * The framework uses these to make routing and configuration decisions.</p>
 */
public enum AiCapability {

    /** Basic text streaming (all adapters support this). */
    TEXT_STREAMING,

    /** Model can call tools/functions and process their results. */
    TOOL_CALLING,

    /**
     * Runtime participates in structured output handling (JSON schema conformance).
     * The pipeline's {@code StructuredOutputCapturingSession} does the actual
     * parsing/validation — this flag indicates the runtime cooperates with it.
     */
    STRUCTURED_OUTPUT,

    /** Model can process image inputs. */
    VISION,

    /** Model can process audio inputs. */
    AUDIO,

    /** Model can generate or process multi-modal content (images, audio, files). */
    MULTI_MODAL,

    /** Adapter manages its own conversation memory (e.g., Google ADK sessions). */
    CONVERSATION_MEMORY,

    /** Model supports system prompts. */
    SYSTEM_PROMPT,

    /** Adapter supports agent/multi-step orchestration (e.g., Embabel, ADK). */
    AGENT_ORCHESTRATION,

    /** Adapter supports human-in-the-loop tool approval (e.g., ADK ToolConfirmation). */
    TOOL_APPROVAL,

    /**
     * Runtime honours prompt-cache hints (Anthropic cache_control, OpenAI
     * prompt cache, Gemini context cache). Phase 7 of the unified
     * {@code @Agent} roadmap.
     */
    PROMPT_CACHING,

    /** Runtime participates in multi-agent handoff / delegation. */
    MULTI_AGENT_HANDOFF,

    /**
     * Runtime exposes cooperative cancellation via
     * {@link AgentRuntime#executeWithHandle(AgentExecutionContext, StreamingSession)}.
     * Phase 2 of the unified {@code @Agent} roadmap.
     */
    CANCELLATION,

    /** Runtime exposes configured / discovered model list via a model-enumeration API. */
    MODEL_ENUMERATION,

    /** Runtime emits typed token usage via {@link StreamingSession#usage}. Phase 1. */
    TOKEN_USAGE,

    /**
     * Runtime emits incremental tool-argument streaming frames via
     * {@link StreamingSession#toolCallDelta(String, String)} as the model
     * generates tool-call JSON, so browser UIs can render "typing" state on
     * tool-argument fields before the consolidated
     * {@link org.atmosphere.ai.AiEvent.ToolStart} event fires. Runtimes that
     * lack this capability still fulfill the default {@code toolCallDelta()}
     * no-op contract (the interface default is a structured metadata frame
     * keyed by tool-call id) but never invoke it from their streaming loop,
     * so no {@code ai.toolCall.delta.*} frames reach the wire.
     *
     * <p>In 4.0.36 only {@code BuiltInAgentRuntime} declares this — its
     * {@code OpenAiCompatibleClient} chat-completions and responses-API
     * streaming loops both call {@code session.toolCallDelta(id, chunk)}
     * on every {@code delta.tool_calls[].function.arguments} fragment. The
     * six framework bridges (Spring AI, LangChain4j, ADK, Embabel, Koog,
     * Semantic Kernel) cannot emit deltas without bypassing their high-level
     * streaming APIs — see the 4.0.36 CHANGELOG entry and commit
     * {@code 895a7e0a2e} for the rationale.</p>
     */
    TOOL_CALL_DELTA,

    /**
     * Runtime honours a per-request {@link RetryPolicy} supplied on
     * {@link AgentExecutionContext#retryPolicy()}. Only the Built-in runtime
     * currently threads the policy into its HTTP client's {@code sendWithRetry}
     * loop; framework runtimes (Spring AI, LangChain4j, ADK, Koog, Semantic
     * Kernel) inherit their native retry layers and cannot be overridden at
     * request time. Callers that need a guaranteed override should either use
     * the Built-in runtime or wire retry at the framework layer.
     *
     * <p>Runtimes without this capability log a WARN on the first request that
     * carries a non-default policy, per {@code AbstractAgentRuntime.execute}'s
     * Mode-Parity enforcement — so operators see the gap in startup logs
     * rather than silently getting the runtime's default retry behavior.</p>
     */
    PER_REQUEST_RETRY
}
