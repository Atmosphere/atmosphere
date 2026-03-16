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
import org.atmosphere.ai.tool.ToolDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Framework-agnostic AI request. Carries the user message, identity fields,
 * system prompt, model name, optional metadata, and conversation history
 * for multi-turn support.
 *
 * <p>This record is what flows through the {@link AiInterceptor} chain
 * before reaching the {@link AiSupport} implementation. Interceptors can
 * transform it (e.g., augment the message with RAG context, override the
 * model, add guardrails).</p>
 *
 * <p>Identity fields ({@code userId}, {@code sessionId}, {@code agentId},
 * {@code conversationId}) are first-class so that adapters like Google ADK
 * (which needs {@code userId}/{@code sessionId}) and Embabel (which needs
 * {@code agentId}) can access them directly instead of digging through an
 * untyped map.</p>
 *
 * @param message        the user's message
 * @param systemPrompt   the system prompt (may be empty)
 * @param model          the model name (may be null for provider default)
 * @param userId         the end-user identifier (may be null)
 * @param sessionId      the session identifier for stateful backends (may be null)
 * @param agentId        the target agent identifier (may be null)
 * @param conversationId the conversation thread identifier (may be null)
 * @param metadata       optional provider-specific metadata (temperature, maxTokens, etc.)
 * @param history        conversation history (prior user/assistant turns)
 */
public record AiRequest(
        String message,
        String systemPrompt,
        String model,
        String userId,
        String sessionId,
        String agentId,
        String conversationId,
        Map<String, Object> metadata,
        List<ChatMessage> history
) {
    /**
     * Create a request with just a message.
     */
    public AiRequest(String message) {
        this(message, "", null, null, null, null, null, Map.of(), List.of());
    }

    /**
     * Create a request with a message and system prompt.
     */
    public AiRequest(String message, String systemPrompt) {
        this(message, systemPrompt, null, null, null, null, null, Map.of(), List.of());
    }

    /**
     * Return a copy with a different message.
     */
    public AiRequest withMessage(String newMessage) {
        return new AiRequest(newMessage, systemPrompt, model, userId, sessionId,
                agentId, conversationId, metadata, history);
    }

    /**
     * Return a copy with a different system prompt.
     */
    public AiRequest withSystemPrompt(String newSystemPrompt) {
        return new AiRequest(message, newSystemPrompt, model, userId, sessionId,
                agentId, conversationId, metadata, history);
    }

    /**
     * Return a copy with a different model.
     */
    public AiRequest withModel(String newModel) {
        return new AiRequest(message, systemPrompt, newModel, userId, sessionId,
                agentId, conversationId, metadata, history);
    }

    /**
     * Return a copy with a different user ID.
     */
    public AiRequest withUserId(String newUserId) {
        return new AiRequest(message, systemPrompt, model, newUserId, sessionId,
                agentId, conversationId, metadata, history);
    }

    /**
     * Return a copy with a different session ID.
     */
    public AiRequest withSessionId(String newSessionId) {
        return new AiRequest(message, systemPrompt, model, userId, newSessionId,
                agentId, conversationId, metadata, history);
    }

    /**
     * Return a copy with a different agent ID.
     */
    public AiRequest withAgentId(String newAgentId) {
        return new AiRequest(message, systemPrompt, model, userId, sessionId,
                newAgentId, conversationId, metadata, history);
    }

    /**
     * Return a copy with a different conversation ID.
     */
    public AiRequest withConversationId(String newConversationId) {
        return new AiRequest(message, systemPrompt, model, userId, sessionId,
                agentId, newConversationId, metadata, history);
    }

    /**
     * Return a copy with additional metadata merged in.
     */
    public AiRequest withMetadata(Map<String, Object> additionalMetadata) {
        var merged = new java.util.HashMap<>(this.metadata);
        merged.putAll(additionalMetadata);
        return new AiRequest(message, systemPrompt, model, userId, sessionId,
                agentId, conversationId, Map.copyOf(merged), history);
    }

    /**
     * Return a copy with conversation history.
     */
    public AiRequest withHistory(List<ChatMessage> newHistory) {
        return new AiRequest(message, systemPrompt, model, userId, sessionId,
                agentId, conversationId, metadata, newHistory);
    }

    /**
     * Return a copy with available tool definitions stored in metadata.
     * Adapters can read these via {@link #tools()} to register tools
     * with their native framework.
     */
    public AiRequest withTools(Collection<ToolDefinition> tools) {
        var merged = new java.util.HashMap<>(this.metadata);
        merged.put("ai.tools", List.copyOf(tools));
        return new AiRequest(message, systemPrompt, model, userId, sessionId,
                agentId, conversationId, Map.copyOf(merged), history);
    }

    /**
     * Get the tool definitions available for this request, if any.
     */
    @SuppressWarnings("unchecked")
    public List<ToolDefinition> tools() {
        var tools = metadata.get("ai.tools");
        if (tools instanceof List<?> list) {
            return (List<ToolDefinition>) list;
        }
        return List.of();
    }
}
