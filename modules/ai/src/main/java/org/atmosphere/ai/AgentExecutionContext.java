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
        List<ChatMessage> history
) {

    public AgentExecutionContext {
        tools = tools != null ? List.copyOf(tools) : List.of();
        contextProviders = contextProviders != null ? List.copyOf(contextProviders) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        history = history != null ? List.copyOf(history) : List.of();
    }

    /** Create a context with a different message. */
    public AgentExecutionContext withMessage(String message) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history);
    }

    /** Create a context with a different system prompt. */
    public AgentExecutionContext withSystemPrompt(String systemPrompt) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history);
    }

    /** Create a context with additional metadata. */
    public AgentExecutionContext withMetadata(Map<String, Object> metadata) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history);
    }

    /** Create a context with conversation history. */
    public AgentExecutionContext withHistory(List<ChatMessage> history) {
        return new AgentExecutionContext(message, systemPrompt, model, agentId,
                sessionId, userId, conversationId, tools, toolTarget, memory,
                contextProviders, metadata, history);
    }
}
