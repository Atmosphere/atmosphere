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
package org.atmosphere.interactions;

import org.atmosphere.ai.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * The inbound description of an interaction turn handed to
 * {@link InteractionService#create} / {@link InteractionService#createBackground}.
 *
 * @param previousInteractionId the {@code previous_interaction_id} pointer; when
 *                              set, the new turn inherits the parent's
 *                              {@code conversationId} and rehydrates its history
 *                              from {@code ConversationPersistence}. {@code null}
 *                              starts a fresh chain
 * @param message               the user message for this turn (required)
 * @param agentId               the agent to target, or {@code null}
 * @param model                 model identifier, or {@code null} for the runtime default
 * @param systemPrompt          system prompt, or {@code null}
 * @param tools                 tool definitions to expose this turn (never {@code null}
 *                              after construction)
 * @param metadata              extensible sidecar threaded into the
 *                              {@code AgentExecutionContext} (never {@code null})
 * @param background            {@code true} to launch detached and retrieve later
 * @param store                 whether to persist a durable record (default {@code true})
 */
public record InteractionRequest(
        String previousInteractionId,
        String message,
        String agentId,
        String model,
        String systemPrompt,
        List<ToolDefinition> tools,
        Map<String, Object> metadata,
        boolean background,
        boolean store) {

    public InteractionRequest {
        tools = tools != null ? List.copyOf(tools) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * A foreground, persisted single-turn request — the common case.
     * Defaults {@code store=true}, {@code background=false}, no chaining.
     */
    public static InteractionRequest of(String message) {
        return new InteractionRequest(null, message, null, null, null,
                List.of(), Map.of(), false, true);
    }

    /** A copy that chains onto the given previous interaction. */
    public InteractionRequest withPrevious(String previousId) {
        return new InteractionRequest(previousId, message, agentId, model, systemPrompt,
                tools, metadata, background, store);
    }

    /** A copy with the background flag set as given. */
    public InteractionRequest withBackground(boolean bg) {
        return new InteractionRequest(previousInteractionId, message, agentId, model,
                systemPrompt, tools, metadata, bg, store);
    }

    /** A copy with the store flag set as given. */
    public InteractionRequest withStore(boolean persist) {
        return new InteractionRequest(previousInteractionId, message, agentId, model,
                systemPrompt, tools, metadata, background, persist);
    }
}
