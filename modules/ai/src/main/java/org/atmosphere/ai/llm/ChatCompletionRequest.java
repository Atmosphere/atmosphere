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

import org.atmosphere.ai.RetryPolicy;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * A chat completion request following the OpenAI-compatible format.
 * Works with OpenAI, Gemini, Ollama, Azure OpenAI, and any compatible endpoint.
 *
 * @param conversationId   optional conversation identifier for stateful continuation
 *                         (e.g. OpenAI Responses API {@code previous_response_id}). May be null.
 * @param approvalStrategy optional session-scoped HITL gate used by the built-in tool loop
 *                         when a tool has {@code requiresApproval()}. May be null.
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        int maxStreamingTexts,
        boolean jsonMode,
        List<ToolDefinition> tools,
        String conversationId,
        ApprovalStrategy approvalStrategy,
        List<org.atmosphere.ai.Content> parts,
        List<org.atmosphere.ai.AgentLifecycleListener> listeners,
        CacheHint cacheHint,
        RetryPolicy retryPolicy,
        ToolApprovalPolicy approvalPolicy
) {
    /**
     * Canonical constructor. {@code retryPolicy} is a per-request override
     * for {@link OpenAiCompatibleClient}'s instance-level retry default —
     * when null, the client falls back to its constructor-time policy.
     */
    public ChatCompletionRequest {
        tools = tools != null ? List.copyOf(tools) : List.of();
        parts = parts != null ? List.copyOf(parts) : List.of();
        listeners = listeners != null ? List.copyOf(listeners) : List.of();
        cacheHint = cacheHint != null ? cacheHint : CacheHint.none();
        // retryPolicy stays nullable: null means "inherit the client's
        // configured default", non-null is an explicit per-request override.
    }

    /**
     * Shim constructor accepting the 12-arg form (without approval policy).
     * Defaults {@code approvalPolicy} to {@code null} so the helper falls
     * back to annotation-driven approval.
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools,
                                 String conversationId, ApprovalStrategy approvalStrategy,
                                 List<org.atmosphere.ai.Content> parts,
                                 List<org.atmosphere.ai.AgentLifecycleListener> listeners,
                                 CacheHint cacheHint, RetryPolicy retryPolicy) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools,
                conversationId, approvalStrategy, parts, listeners, cacheHint, retryPolicy, null);
    }

    /**
     * Shim constructor accepting the 11-arg form (without retry policy or
     * approval policy).
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools,
                                 String conversationId, ApprovalStrategy approvalStrategy,
                                 List<org.atmosphere.ai.Content> parts,
                                 List<org.atmosphere.ai.AgentLifecycleListener> listeners,
                                 CacheHint cacheHint) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools,
                conversationId, approvalStrategy, parts, listeners, cacheHint, null, null);
    }

    /**
     * Shim constructor accepting the 10-arg form (without cache hint).
     * Defaults {@code cacheHint} to {@link CacheHint#none()} so existing
     * callers (routing, fanout, Wave-3 built-in runtime) keep compiling
     * unchanged.
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools,
                                 String conversationId, ApprovalStrategy approvalStrategy,
                                 List<org.atmosphere.ai.Content> parts,
                                 List<org.atmosphere.ai.AgentLifecycleListener> listeners) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools,
                conversationId, approvalStrategy, parts, listeners, CacheHint.none(), null, null);
    }

    /**
     * Shim constructor accepting the 9-arg form (without listeners or cache hint).
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools,
                                 String conversationId, ApprovalStrategy approvalStrategy,
                                 List<org.atmosphere.ai.Content> parts) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools,
                conversationId, approvalStrategy, parts, List.of(), CacheHint.none());
    }

    /**
     * Shim constructor accepting the 8-arg form without multi-modal parts,
     * listeners, or cache hint.
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools,
                                 String conversationId, ApprovalStrategy approvalStrategy) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools,
                conversationId, approvalStrategy, List.of(), List.of(), CacheHint.none());
    }

    /**
     * Backwards-compatible constructor without approvalStrategy.
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools,
                                 String conversationId) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools, conversationId, null, List.of(), List.of(), CacheHint.none());
    }

    /**
     * Backwards-compatible constructor without conversationId or approvalStrategy.
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools, null, null, List.of(), List.of(), CacheHint.none());
    }

    /**
     * Create a simple single-prompt request.
     */
    public static ChatCompletionRequest of(String model, String userPrompt) {
        return new ChatCompletionRequest(model, List.of(ChatMessage.user(userPrompt)),
                0.7, 2048, false, List.of(), null, null, List.of(), List.of(), CacheHint.none());
    }

    /**
     * Builder for constructing complex requests.
     */
    public static Builder builder(String model) {
        return new Builder(model);
    }

    public static final class Builder {
        private final String model;
        private final List<ChatMessage> messages = new ArrayList<>();
        private List<ToolDefinition> tools = List.of();
        private double temperature = 0.7;
        private int maxStreamingTexts = 2048;
        private boolean jsonMode = false;
        private String conversationId;
        private ApprovalStrategy approvalStrategy;
        private List<org.atmosphere.ai.Content> parts = List.of();
        private List<org.atmosphere.ai.AgentLifecycleListener> listeners = List.of();
        private CacheHint cacheHint = CacheHint.none();
        private RetryPolicy retryPolicy;
        private ToolApprovalPolicy approvalPolicy;

        private Builder(String model) {
            this.model = model;
        }

        public Builder system(String content) {
            messages.add(ChatMessage.system(content));
            return this;
        }

        public Builder user(String content) {
            messages.add(ChatMessage.user(content));
            return this;
        }

        public Builder assistant(String content) {
            messages.add(ChatMessage.assistant(content));
            return this;
        }

        public Builder message(ChatMessage message) {
            messages.add(message);
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxStreamingTexts(int maxStreamingTexts) {
            this.maxStreamingTexts = maxStreamingTexts;
            return this;
        }

        public Builder jsonMode(boolean jsonMode) {
            this.jsonMode = jsonMode;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder approvalStrategy(ApprovalStrategy approvalStrategy) {
            this.approvalStrategy = approvalStrategy;
            return this;
        }

        /**
         * Attach multi-modal {@link org.atmosphere.ai.Content} parts
         * (image, audio, file) to the request. The
         * {@link org.atmosphere.ai.llm.OpenAiCompatibleClient} translates
         * them into the OpenAI multi-content {@code content} array format
         * on the last user message when the list is non-empty.
         */
        public Builder parts(List<org.atmosphere.ai.Content> parts) {
            this.parts = parts != null ? parts : List.of();
            return this;
        }

        /**
         * Attach {@link org.atmosphere.ai.AgentLifecycleListener} instances
         * so {@link OpenAiCompatibleClient}'s tool-call loop fires per-tool
         * {@code onToolCall} / {@code onToolResult} events on every round.
         * Defaults to empty, which is equivalent to the pre-Phase-3 behavior.
         */
        public Builder listeners(List<org.atmosphere.ai.AgentLifecycleListener> listeners) {
            this.listeners = listeners != null ? listeners : List.of();
            return this;
        }

        /**
         * Attach a {@link CacheHint} so {@link OpenAiCompatibleClient} emits
         * {@code prompt_cache_key} on the OpenAI chat-completions wire when
         * the hint is opted in. Travels intact across tool-loop rounds.
         */
        public Builder cacheHint(CacheHint cacheHint) {
            this.cacheHint = cacheHint != null ? cacheHint : CacheHint.none();
            return this;
        }

        /**
         * Attach a per-request {@link RetryPolicy} override. When set,
         * {@link OpenAiCompatibleClient} uses this policy for the
         * upstream HTTP retries instead of its instance-level default.
         * Pass {@code null} to inherit the client's configured default.
         */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder approvalPolicy(ToolApprovalPolicy approvalPolicy) {
            this.approvalPolicy = approvalPolicy;
            return this;
        }

        public ChatCompletionRequest build() {
            return new ChatCompletionRequest(model, List.copyOf(messages),
                    temperature, maxStreamingTexts, jsonMode, tools, conversationId, approvalStrategy, parts, listeners, cacheHint, retryPolicy, approvalPolicy);
        }
    }
}
