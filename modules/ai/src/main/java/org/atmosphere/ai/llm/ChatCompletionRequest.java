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

import org.atmosphere.ai.approval.ApprovalStrategy;
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
        List<org.atmosphere.ai.Content> parts
) {
    /**
     * Canonical constructor.
     */
    public ChatCompletionRequest {
        tools = tools != null ? List.copyOf(tools) : List.of();
        parts = parts != null ? List.copyOf(parts) : List.of();
    }

    /**
     * Shim constructor accepting the 8-arg form without multi-modal parts.
     * Defaults {@code parts} to an empty list so existing callers (routing,
     * fanout, tests) keep compiling unchanged. Callers that need multi-modal
     * input use the canonical 9-arg constructor directly.
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools,
                                 String conversationId, ApprovalStrategy approvalStrategy) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools,
                conversationId, approvalStrategy, List.of());
    }

    /**
     * Backwards-compatible constructor without approvalStrategy.
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools,
                                 String conversationId) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools, conversationId, null, List.of());
    }

    /**
     * Backwards-compatible constructor without conversationId or approvalStrategy.
     */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 double temperature, int maxStreamingTexts,
                                 boolean jsonMode, List<ToolDefinition> tools) {
        this(model, messages, temperature, maxStreamingTexts, jsonMode, tools, null, null, List.of());
    }

    /**
     * Create a simple single-prompt request.
     */
    public static ChatCompletionRequest of(String model, String userPrompt) {
        return new ChatCompletionRequest(model, List.of(ChatMessage.user(userPrompt)),
                0.7, 2048, false, List.of(), null, null, List.of());
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

        public ChatCompletionRequest build() {
            return new ChatCompletionRequest(model, List.copyOf(messages),
                    temperature, maxStreamingTexts, jsonMode, tools, conversationId, approvalStrategy, parts);
        }
    }
}
