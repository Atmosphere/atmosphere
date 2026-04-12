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

import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Immutable context carrying everything an {@link AgentRuntime} needs to execute
 * an agent loop. Unlike {@link AiRequest}, this record carries tools, memory,
 * and context providers as first-class fields — enabling framework-specific
 * runtimes to use them natively.
 *
 * <p>The runtime decides how to use each field:</p>
 * <ul>
 *   <li>{@code tools} — bridge to framework's tool system (LC4j ToolSpecification, ADK BaseTool, etc.)</li>
 *   <li>{@code memory} — bridge to framework's chat memory or use Atmosphere's directly</li>
 *   <li>{@code contextProviders} — bridge to framework's RAG or apply as prompt augmentation</li>
 *   <li>{@code history} — pre-loaded conversation history (runtime may also load from memory)</li>
 *   <li>{@code metadata} — extensible key-value sidecar; carries {@link org.atmosphere.ai.llm.CacheHint}
 *       under {@code ai.cache.hint} when prompt caching is opted in</li>
 *   <li>{@code approvalStrategy} — HITL gate every runtime bridge must consult when a tool
 *       has {@link ToolDefinition#requiresApproval()}. May be {@code null} when no session-scoped
 *       strategy is available (e.g. ad-hoc tests, coordinator evaluation); runtimes fall back to
 *       direct execution without gating in that case.</li>
 *   <li>{@code listeners} — per-request {@link AgentLifecycleListener} list; bridges fire
 *       {@code onToolCall}/{@code onToolResult} events on every tool round</li>
 *   <li>{@code parts} — multi-modal {@link Content} input (image, audio, file); runtimes
 *       translate to framework-native types (Spring AI {@code Media}, LC4j {@code ImageContent},
 *       ADK {@code Part.fromBytes}, OpenAI multi-content array)</li>
 *   <li>{@code approvalPolicy} — declarative {@link ToolApprovalPolicy} that controls which
 *       tools require HITL approval; defaults to annotation-driven</li>
 *   <li>{@code retryPolicy} — per-request {@link RetryPolicy} override; the Built-in runtime
 *       threads it into {@code OpenAiCompatibleClient.sendWithRetry}; framework runtimes inherit
 *       their native retry layers</li>
 * </ul>
 */
public record AgentExecutionContext(
        String message,
        String systemPrompt,
        String model,
        String agentId,
        String sessionId,
        String userId,
        String conversationId,
        List<ToolDefinition> tools,
        Object toolTarget,
        AiConversationMemory memory,
        List<ContextProvider> contextProviders,
        Map<String, Object> metadata,
        List<ChatMessage> history,
        Class<?> responseType,
        ApprovalStrategy approvalStrategy,
        List<AgentLifecycleListener> listeners,
        List<Content> parts,
        ToolApprovalPolicy approvalPolicy,
        RetryPolicy retryPolicy
) {

    public AgentExecutionContext {
        tools = tools != null ? List.copyOf(tools) : List.of();
        contextProviders = contextProviders != null ? List.copyOf(contextProviders) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        history = history != null ? List.copyOf(history) : List.of();
        listeners = listeners != null ? List.copyOf(listeners) : List.of();
        parts = parts != null ? List.copyOf(parts) : List.of();
        approvalPolicy = approvalPolicy != null ? approvalPolicy : ToolApprovalPolicy.annotated();
        retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.DEFAULT;
    }

    /**
     * 18-arg constructor preserved for callers that supply tool approval
     * policy but not yet a per-request {@link RetryPolicy}. Defaults to
     * {@link RetryPolicy#DEFAULT}.
     */
    public AgentExecutionContext(String message, String systemPrompt, String model,
                                 String agentId, String sessionId, String userId,
                                 String conversationId, List<ToolDefinition> tools,
                                 Object toolTarget, AiConversationMemory memory,
                                 List<ContextProvider> contextProviders,
                                 Map<String, Object> metadata, List<ChatMessage> history,
                                 Class<?> responseType, ApprovalStrategy approvalStrategy,
                                 List<AgentLifecycleListener> listeners,
                                 List<Content> parts, ToolApprovalPolicy approvalPolicy) {
        this(message, systemPrompt, model, agentId, sessionId, userId, conversationId,
                tools, toolTarget, memory, contextProviders, metadata, history, responseType,
                approvalStrategy, listeners, parts, approvalPolicy, RetryPolicy.DEFAULT);
    }

    /**
     * Phase 3 16-arg shim preserved for call sites that carry a listener
     * list but not yet multi-modal input parts, a custom
     * {@link ToolApprovalPolicy}, or a per-request {@link RetryPolicy}.
     * Delegates to the 18-arg shim with an empty parts list and the
     * annotation-driven default policy.
     */
    public AgentExecutionContext(String message, String systemPrompt, String model,
                                 String agentId, String sessionId, String userId,
                                 String conversationId, List<ToolDefinition> tools,
                                 Object toolTarget, AiConversationMemory memory,
                                 List<ContextProvider> contextProviders,
                                 Map<String, Object> metadata, List<ChatMessage> history,
                                 Class<?> responseType, ApprovalStrategy approvalStrategy,
                                 List<AgentLifecycleListener> listeners) {
        this(message, systemPrompt, model, agentId, sessionId, userId, conversationId,
                tools, toolTarget, memory, contextProviders, metadata, history, responseType,
                approvalStrategy, listeners, List.of(), ToolApprovalPolicy.annotated());
    }

    /**
     * Phase 2 15-arg shim preserved for call sites that carry an
     * {@link ApprovalStrategy} but not yet a listener list, multi-modal parts,
     * a custom policy, or a per-request {@link RetryPolicy}. Delegates to the
     * 18-arg shim.
     */
    public AgentExecutionContext(String message, String systemPrompt, String model,
                                 String agentId, String sessionId, String userId,
                                 String conversationId, List<ToolDefinition> tools,
                                 Object toolTarget, AiConversationMemory memory,
                                 List<ContextProvider> contextProviders,
                                 Map<String, Object> metadata, List<ChatMessage> history,
                                 Class<?> responseType, ApprovalStrategy approvalStrategy) {
        this(message, systemPrompt, model, agentId, sessionId, userId, conversationId,
                tools, toolTarget, memory, contextProviders, metadata, history, responseType,
                approvalStrategy, List.of(), List.of(), ToolApprovalPolicy.annotated());
    }

    /** Create a context with a different message. */
    public AgentExecutionContext withMessage(String message) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /** Create a context with a different system prompt. */
    public AgentExecutionContext withSystemPrompt(String systemPrompt) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /** Create a context with additional metadata. */
    public AgentExecutionContext withMetadata(Map<String, Object> metadata) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /** Create a context with conversation history. */
    public AgentExecutionContext withHistory(List<ChatMessage> history) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /** Create a context with a target response type for structured output. */
    public AgentExecutionContext withResponseType(Class<?> responseType) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /** Create a context bound to a session-scoped HITL strategy. */
    public AgentExecutionContext withApprovalStrategy(ApprovalStrategy approvalStrategy) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /** Create a context with an additional lifecycle listener list (Phase 3). */
    public AgentExecutionContext withListeners(List<AgentLifecycleListener> listeners) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /** Create a context with multi-modal input parts (Phase 4). */
    public AgentExecutionContext withParts(List<Content> parts) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /**
     * Create a context with a custom {@link ToolApprovalPolicy} (Phase 6).
     * Use {@link ToolApprovalPolicy#allowAll()} for trusted test harnesses,
     * {@link ToolApprovalPolicy#denyAll()} for shadow/preview mode, or
     * {@link ToolApprovalPolicy#custom(java.util.function.Predicate)} for
     * caller-driven decisions. The default is
     * {@link ToolApprovalPolicy#annotated()} which honors
     * {@code @RequiresApproval}.
     */
    public AgentExecutionContext withApprovalPolicy(ToolApprovalPolicy approvalPolicy) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }

    /**
     * Create a context with a custom {@link RetryPolicy}. The Built-in
     * runtime threads this through {@code OpenAiCompatibleClient.sendWithRetry}
     * as a per-request override; framework adapter runtimes (Spring AI,
     * LC4j, ADK, Koog, Embabel) honor their own retry layers and ignore
     * the per-request override unless explicitly wired upstream.
     */
    public AgentExecutionContext withRetryPolicy(RetryPolicy retryPolicy) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history, responseType, approvalStrategy, listeners, parts, approvalPolicy, retryPolicy);
    }
}
