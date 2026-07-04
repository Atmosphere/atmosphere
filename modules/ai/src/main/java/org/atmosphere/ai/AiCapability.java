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

    /**
     * Runtime enforces a declared response schema at the <em>provider</em> level —
     * it threads the JSON Schema generated for the {@code responseType} into the
     * provider's native structured-output API ({@code response_format:json_schema}
     * for OpenAI / Cohere, {@code output_config} for Anthropic, LangChain4j
     * {@code ResponseFormat.jsonSchema}, Gemini {@code responseSchema}, Semantic
     * Kernel {@code JsonSchemaResponseFormat}, Koog {@code StructuredRequest.Native},
     * AgentScope {@code ResponseFormat.jsonSchema}) so non-conforming output cannot
     * be emitted by the model in the first place — rather than relying solely on the
     * prompt-injection + parse path that {@link #STRUCTURED_OUTPUT} covers.
     *
     * <p>Distinct from {@link #STRUCTURED_OUTPUT}: every runtime that cooperates
     * with the pipeline parser declares {@code STRUCTURED_OUTPUT}; only runtimes
     * whose underlying SDK/wire actually carries a schema field declare this. The
     * split is a Runtime-Truth boundary (Correctness Invariant #5) — declaring it
     * asserts the schema reaches the provider, not just the prompt. Activation is
     * governed by {@link NativeStructuredOutputMode} (AUTO default, with graceful
     * fall-back to prompt-injection when a provider rejects the schema).</p>
     */
    NATIVE_STRUCTURED_OUTPUT,

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
    PER_REQUEST_RETRY,

    /**
     * Pipeline enforces a per-call {@link AiBudget} (max input/output/total
     * tokens, max steps, max wall clock). When the budget is exceeded at any
     * point during the stream, the {@code BudgetCapturingSession} decorator
     * routes an {@link AiBudgetExceededException} through
     * {@link StreamingSession#error(Throwable)} and short-circuits the
     * remaining stream.
     *
     * <p><b>Wall-clock limits</b> trip universally — the decorator samples
     * elapsed time at every session boundary regardless of which runtime
     * is dispatching. <b>Token and step limits</b> depend on the runtime
     * emitting {@link TokenUsage} via {@link StreamingSession#usage}; runtimes
     * that declare {@link #TOKEN_USAGE} also enforce token / step budgets,
     * runtimes that do not declare it can only enforce wall-clock budgets.
     * The pairing is documented in the capability matrix in
     * {@code modules/ai/README.md} so callers know which dimensions of an
     * {@link AiBudget} are effective against a given runtime.</p>
     *
     * <p>Distinct from {@link org.atmosphere.ai.budget.StreamingTextBudgetManager},
     * which tracks long-running per-tenant budgets (cumulative streaming-text
     * counts across many calls) and recommends model fallback. This capability
     * is the per-call death-spiral guard.</p>
     */
    BUDGET_ENFORCEMENT,

    /**
     * Runtime emits per-response confidence — either native logprobs from the
     * provider, a model-reported confidence field elicited by prompt, or a
     * heuristic. Surfaced via {@link StreamingSession#confidence(AiConfidence)}
     * and the {@code ai.confidence.aggregate} / {@code ai.confidence.source}
     * metadata keys. {@link AiConfidence#source()} indicates the quality of the
     * signal so consumers (routers, guardrails) can weight it accordingly.
     */
    CONFIDENCE_SCORES,

    /**
     * Runtime threads {@code context.history()} through its dispatch path so an
     * in-flight conversation can be snapshotted to a {@code CheckpointStore} and
     * resumed on an external signal hours or days later. The snapshot/resume
     * helpers are {@code AgentPassivation#passivate} and
     * {@code AgentPassivation#resume} in {@code atmosphere-checkpoint}; this flag
     * advertises only the history-threading cooperation contract they rely on,
     * not a user-facing pause/resume endpoint (that is application policy).
     * Closes the long-pause human-in-the-loop gap — agents waiting on human
     * approval can drop out of RAM, then rehydrate with full context.
     */
    PASSIVATION,

    /**
     * Runtime exposes a <em>native, model-maintained plan surface</em> the
     * adapter genuinely wires end-to-end: plan mutations made through the
     * framework's own machinery (e.g. AgentScope {@code PlanNotebook},
     * Spring AI Alibaba {@code TodoListInterceptor}, Embabel GOAP plan
     * events) are mirrored into Atmosphere's
     * {@link org.atmosphere.ai.plan.AgentPlan} model and emitted as
     * {@link AiEvent.PlanUpdate} frames.
     *
     * <p>Runtime-Truth boundary (Correctness Invariant #5): declare this
     * ONLY when the adapter's dispatch path actually attaches the native
     * plan machinery and bridges its updates — not because the wrapped
     * framework ships a planner class somewhere on the classpath. Runtimes
     * without it still get the portable plan surface: the harness
     * {@code PLANNING} feature registers the built-in {@code write_todos}
     * tool floor instead. Activation is governed by
     * {@link org.atmosphere.ai.plan.PlanningMode} (AUTO default — native
     * wins only when this capability is declared).</p>
     */
    PLANNING,

    /**
     * Runtime exposes a <em>native file-tool surface</em> the adapter
     * genuinely bridges to Atmosphere's
     * {@link org.atmosphere.ai.fs.AgentFileSystem} store (e.g. Spring AI
     * Alibaba {@code FilesystemBackend}, ADK {@code BaseArtifactService},
     * Anthropic {@code memory_20250818} commands) — the model reads and
     * writes Atmosphere's bounded, conversation-scoped store through tools
     * the framework itself defines.
     *
     * <p>Runtime-Truth boundary (Correctness Invariant #5): declare this
     * ONLY when the adapter installs the bridge on its dispatch path — the
     * mere presence of file-tool classes in the wrapped SDK does not
     * qualify. Runtimes without it still get the portable file surface: the
     * harness {@code FILESYSTEM} feature registers the built-in
     * {@code ls}/{@code read_file}/{@code write_file}/{@code edit_file}/
     * {@code glob}/{@code grep} tool floor instead. When a native bridge is
     * active the built-in floor is NOT registered (no duplicate tools);
     * activation is governed by
     * {@link org.atmosphere.ai.fs.FilesystemMode} (AUTO default).</p>
     */
    VIRTUAL_FILESYSTEM
}
