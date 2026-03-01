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

import org.atmosphere.ai.llm.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * Framework-agnostic AI request. Carries the user message, system prompt,
 * model name, optional hints (temperature, maxTokens, etc.), and conversation
 * history for multi-turn support.
 *
 * <p>This record is what flows through the {@link AiInterceptor} chain
 * before reaching the {@link AiSupport} implementation. Interceptors can
 * transform it (e.g., augment the message with RAG context, override the
 * model, add guardrails).</p>
 *
 * @param message      the user's message
 * @param systemPrompt the system prompt (may be empty)
 * @param model        the model name (may be null for provider default)
 * @param hints        optional hints (temperature, maxTokens, etc.)
 * @param history      conversation history (prior user/assistant turns)
 */
public record AiRequest(
        String message,
        String systemPrompt,
        String model,
        Map<String, Object> hints,
        List<ChatMessage> history
) {
    /**
     * Create a request with just a message.
     */
    public AiRequest(String message) {
        this(message, "", null, Map.of(), List.of());
    }

    /**
     * Create a request with a message and system prompt.
     */
    public AiRequest(String message, String systemPrompt) {
        this(message, systemPrompt, null, Map.of(), List.of());
    }

    /**
     * Return a copy with a different message.
     */
    public AiRequest withMessage(String newMessage) {
        return new AiRequest(newMessage, systemPrompt, model, hints, history);
    }

    /**
     * Return a copy with a different system prompt.
     */
    public AiRequest withSystemPrompt(String newSystemPrompt) {
        return new AiRequest(message, newSystemPrompt, model, hints, history);
    }

    /**
     * Return a copy with a different model.
     */
    public AiRequest withModel(String newModel) {
        return new AiRequest(message, systemPrompt, newModel, hints, history);
    }

    /**
     * Return a copy with additional hints merged in.
     */
    public AiRequest withHints(Map<String, Object> additionalHints) {
        var merged = new java.util.HashMap<>(this.hints);
        merged.putAll(additionalHints);
        return new AiRequest(message, systemPrompt, model, Map.copyOf(merged), history);
    }

    /**
     * Return a copy with conversation history.
     */
    public AiRequest withHistory(List<ChatMessage> newHistory) {
        return new AiRequest(message, systemPrompt, model, hints, newHistory);
    }
}
