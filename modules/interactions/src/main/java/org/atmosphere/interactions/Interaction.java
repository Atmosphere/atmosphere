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

import org.atmosphere.ai.TokenUsage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One stateful agent turn: the Atmosphere-native equivalent of a Gemini
 * Interactions API {@code Interaction} resource.
 *
 * <p>An interaction is created by {@link InteractionService}, runs through an
 * {@code org.atmosphere.ai.AgentRuntime}, and accumulates a durable
 * {@link InteractionStep} log. Turns chain via {@link #parentId}
 * (the {@code previous_interaction_id} pointer): a chained turn inherits its
 * parent's {@link #conversationId}, which is the single source of truth for the
 * replayable {@code List<ChatMessage>} history (stored by the framework's
 * {@code ConversationPersistence}). The {@code steps[]} log here is the durable
 * observability record, not the prompt history — the two are intentionally
 * distinct stores.</p>
 *
 * @param id             server-minted, validated identifier ({@code "int-"+UUID})
 * @param parentId       previous interaction in the chain, or {@code null} to start fresh
 * @param conversationId join key into the authoritative history store; shared across a chain
 * @param agentId        the agent this turn targeted, or {@code null}
 * @param userId         owning principal ({@code "anonymous"} when unauthenticated)
 * @param model          model identifier, or {@code null} to use the runtime default
 * @param status         lifecycle state
 * @param background     {@code true} if launched detached via {@code createBackground}
 * @param store          whether this interaction is persisted; {@code false} means
 *                       no durable record is written (only live-streamable)
 * @param steps          durable event log (never {@code null}; defensively copied)
 * @param finalText      aggregated assistant output, or {@code null} until terminal
 * @param usage          aggregated token usage, or {@code null} when unreported
 * @param errorMessage   failure cause for {@link InteractionStatus#FAILED}, else {@code null}
 * @param createdAt      when the interaction was created
 * @param updatedAt      when the interaction last changed state
 */
public record Interaction(
        String id,
        String parentId,
        String conversationId,
        String agentId,
        String userId,
        String model,
        InteractionStatus status,
        boolean background,
        boolean store,
        List<InteractionStep> steps,
        String finalText,
        TokenUsage usage,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public Interaction {
        steps = steps != null ? List.copyOf(steps) : List.of();
    }

    /** Whether this interaction has reached a terminal state. */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /** A copy with a new status and {@code updatedAt} stamp, preserving everything else. */
    public Interaction withStatus(InteractionStatus newStatus, Instant when) {
        return new Interaction(id, parentId, conversationId, agentId, userId, model,
                newStatus, background, store, steps, finalText, usage, errorMessage,
                createdAt, when);
    }

    /** A copy with one step appended to the log and an updated stamp. */
    public Interaction withAppendedStep(InteractionStep step, Instant when) {
        var next = new ArrayList<InteractionStep>(steps.size() + 1);
        next.addAll(steps);
        next.add(step);
        return new Interaction(id, parentId, conversationId, agentId, userId, model,
                status, background, store, next, finalText, usage, errorMessage,
                createdAt, when);
    }

    /** A copy with the given steps, terminal text/usage/error, status, and stamp. */
    public Interaction withResult(InteractionStatus newStatus, List<InteractionStep> newSteps,
                                  String newFinalText, TokenUsage newUsage,
                                  String newErrorMessage, Instant when) {
        return new Interaction(id, parentId, conversationId, agentId, userId, model,
                newStatus, background, store, newSteps, newFinalText, newUsage,
                newErrorMessage, createdAt, when);
    }
}
