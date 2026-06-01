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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The Interactions API facade: stateful agent turns layered over any
 * {@link AgentRuntime}.
 *
 * <p>An interaction runs a single agent turn through the resolved runtime,
 * capturing the live event stream into a durable {@link InteractionStep} log via
 * {@link InteractionCapturingSession}. Turns chain through
 * {@link InteractionRequest#previousInteractionId()}: a chained turn inherits its
 * parent's {@code conversationId} and rehydrates the prompt history from the
 * supplied {@link AiConversationMemory} — the single source of truth for
 * replayable {@code List<ChatMessage>} history. The durable {@code steps[]} log is
 * the observability record, a distinct store.</p>
 *
 * <p>This class is runtime-agnostic by construction: it only uses the
 * {@link AgentRuntime} SPI, so it works for every adapter with no per-runtime
 * code. The synchronous {@link #create} path is implemented here; detached
 * {@code background} execution is layered on in a sibling method that reuses the
 * same context-building and finalization machinery so both modes produce
 * identical {@code steps[]} (Correctness Invariant #7 — Mode Parity).</p>
 *
 * <p><b>Ownership:</b> the {@link InteractionStore} and {@link AiConversationMemory}
 * are supplied by the caller and their lifecycle remains the caller's
 * responsibility — {@link #start()}/{@link #stop()} manage only resources this
 * service creates (Correctness Invariant #1 — Ownership).</p>
 */
public class InteractionService {

    /** Owner recorded for interactions created without an authenticated principal. */
    public static final String ANONYMOUS = "anonymous";

    /** Default cap on durable steps per interaction. */
    public static final int DEFAULT_MAX_STEPS = 1000;

    /** Default wall-clock ceiling for a synchronous turn. */
    public static final Duration DEFAULT_SYNC_TIMEOUT = Duration.ofSeconds(120);

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionService.class);

    private final AgentRuntime runtime;
    private final InteractionStore store;
    private final AiConversationMemory memory;
    private final InteractionStepMapper mapper;
    private final int maxSteps;
    private final Duration syncTimeout;
    private final Clock clock;

    public InteractionService(AgentRuntime runtime, InteractionStore store) {
        this(runtime, store, null, new InteractionStepMapper(), DEFAULT_MAX_STEPS,
                DEFAULT_SYNC_TIMEOUT, Clock.systemUTC());
    }

    public InteractionService(AgentRuntime runtime, InteractionStore store,
                              AiConversationMemory memory) {
        this(runtime, store, memory, new InteractionStepMapper(), DEFAULT_MAX_STEPS,
                DEFAULT_SYNC_TIMEOUT, Clock.systemUTC());
    }

    public InteractionService(AgentRuntime runtime, InteractionStore store,
                              AiConversationMemory memory, InteractionStepMapper mapper,
                              int maxSteps, Duration syncTimeout, Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.store = Objects.requireNonNull(store, "store");
        this.memory = memory;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive, got " + maxSteps);
        }
        this.maxSteps = maxSteps;
        this.syncTimeout = Objects.requireNonNull(syncTimeout, "syncTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Initialize service-owned resources. */
    public void start() {
        // No service-owned resources yet; symmetric with stop().
    }

    /** Release service-owned resources. */
    public void stop() {
        // No service-owned resources yet; symmetric with start().
    }

    /**
     * Run a synchronous interaction turn: execute the runtime, streaming live to
     * {@code clientSession} while capturing a durable {@code steps[]} log, and
     * return the terminal {@link Interaction} once the run completes.
     *
     * @param request       the turn description
     * @param clientSession the live session to stream to (may be a headless sink)
     * @param principal     the authenticated caller, or {@code null} for anonymous
     * @return the completed (or failed) interaction
     */
    public Interaction create(InteractionRequest request, StreamingSession clientSession,
                              String principal) {
        var plan = prepare(request, principal);
        var capturing = new InteractionCapturingSession(clientSession, plan.id(),
                request.store() ? store : null, mapper, maxSteps);
        if (request.store()) {
            store.save(plan.initial());
        }

        ExecutionHandle handle = ExecutionHandle.completed();
        try {
            handle = runtime.executeWithHandle(plan.context(), capturing);
        } catch (RuntimeException e) {
            // The runtime threw synchronously before completing the stream — funnel
            // it through the capturing session's terminal path so the failure is
            // recorded once and the run reaches a completed state (Invariant #2).
            capturing.error(e);
        }
        var terminated = capturing.awaitTerminal(syncTimeout);
        if (!terminated) {
            // Timed out — cancel the in-flight run so no work outlives this call.
            handle.cancel();
        }
        return finalizeTurn(plan, capturing, terminated);
    }

    /**
     * Continue an existing interaction with a new turn — sugar for
     * {@link #create} with the previous id threaded onto the request.
     *
     * @param previousInteractionId the interaction to chain from
     * @param request               the new turn (its {@code previousInteractionId} is overridden)
     * @param clientSession         the live session to stream to
     * @param principal             the authenticated caller, or {@code null}
     * @return the completed (or failed) interaction
     */
    public Interaction continueInteraction(String previousInteractionId,
                                           InteractionRequest request,
                                           StreamingSession clientSession, String principal) {
        return create(request.withPrevious(previousInteractionId), clientSession, principal);
    }

    /**
     * Retrieve an interaction by id, enforcing caller ownership.
     *
     * @param interactionId the id to load
     * @param principal     the caller; must own the interaction
     * @return the interaction, or empty if unknown or not owned by the caller
     * @throws IllegalArgumentException if the id is malformed (boundary rejection)
     */
    public Optional<Interaction> get(String interactionId, String principal) {
        InteractionIds.requireValid(interactionId);
        var owner = normalize(principal);
        return store.load(interactionId).filter(i -> ownedBy(i, owner));
    }

    /**
     * List the caller's interactions, optionally narrowed by the query's
     * conversation/status filters. The owner filter is always forced to the
     * caller so one principal can never list another's interactions.
     *
     * @param query     additional filters (user filter is overridden with {@code principal})
     * @param principal the caller
     * @return matching interactions owned by the caller
     */
    public List<Interaction> list(InteractionQuery query, String principal) {
        var owner = normalize(principal);
        var scoped = new InteractionQuery(owner, query.conversationId(), query.status(), query.limit());
        return store.list(scoped);
    }

    /**
     * Delete an interaction the caller owns.
     *
     * @param interactionId the id to delete
     * @param principal     the caller; must own the interaction
     * @return {@code true} if deleted, {@code false} if unknown or not owned
     * @throws IllegalArgumentException if the id is malformed
     */
    public boolean delete(String interactionId, String principal) {
        InteractionIds.requireValid(interactionId);
        var owner = normalize(principal);
        var existing = store.load(interactionId);
        if (existing.isEmpty() || !ownedBy(existing.get(), owner)) {
            return false;
        }
        return store.delete(interactionId);
    }

    // --- internal machinery shared by sync and (future) background paths ---

    /** Resolve chaining, rehydrate history, and build the initial record + context. */
    Plan prepare(InteractionRequest request, String principal) {
        Objects.requireNonNull(request, "request");
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        var owner = normalize(principal);
        String conversationId;
        String parentId = null;

        if (request.previousInteractionId() != null) {
            var previousId = InteractionIds.requireValid(request.previousInteractionId());
            var parent = store.load(previousId)
                    .filter(p -> ownedBy(p, owner))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown or unauthorized previous interaction"));
            parentId = parent.id();
            conversationId = parent.conversationId();
        } else {
            conversationId = "conv-" + UUID.randomUUID();
        }

        var history = memory != null ? memory.getHistory(conversationId) : List.<ChatMessage>of();
        if (memory == null && request.previousInteractionId() != null) {
            LOGGER.info("Chaining requested for {} but no conversation memory is configured — "
                    + "continuing as a single turn", request.previousInteractionId());
        }

        var id = InteractionIds.mint();
        var now = Instant.now(clock);
        var initial = new Interaction(id, parentId, conversationId, request.agentId(), owner,
                request.model(), InteractionStatus.RUNNING, request.background(), request.store(),
                List.of(), null, null, null, now, now);

        var context = new AgentExecutionContext(
                request.message(), request.systemPrompt(), request.model(), request.agentId(),
                id, owner, conversationId, request.tools(), null, null, null,
                request.metadata(), history, null, null);
        return new Plan(id, conversationId, owner, request, initial, context);
    }

    /** Build the terminal record from the capturing session and persist the turn. */
    Interaction finalizeTurn(Plan plan, InteractionCapturingSession capturing, boolean terminated) {
        // The capturing session's terminal status is authoritative when present;
        // its absence means the run never signalled completion (timeout) → FAILED.
        var status = capturing.terminalStatus() != null
                ? capturing.terminalStatus() : InteractionStatus.FAILED;
        var steps = capturing.steps();
        var finalText = capturing.finalText();
        var usage = capturing.usage().orElse(null);
        var error = status == InteractionStatus.FAILED
                ? Optional.ofNullable(capturing.errorMessage())
                        .orElse(terminated ? "unknown error" : "timed out after " + syncTimeout)
                : null;

        var result = plan.initial().withResult(status, steps,
                finalText.isEmpty() ? null : finalText, usage, error, Instant.now(clock));
        if (plan.request().store()) {
            store.save(result);
        }
        persistHistory(plan, status, finalText);
        return result;
    }

    private void persistHistory(Plan plan, InteractionStatus status, String finalText) {
        if (memory == null || status != InteractionStatus.COMPLETED) {
            return;
        }
        // The service is the single writer of conversation history: append the
        // user turn and the assistant turn so the next chained interaction sees them.
        memory.addMessage(plan.conversationId(), new ChatMessage("user", plan.request().message()));
        if (!finalText.isEmpty()) {
            memory.addMessage(plan.conversationId(), new ChatMessage("assistant", finalText));
        }
    }

    private boolean ownedBy(Interaction interaction, String owner) {
        return Objects.equals(interaction.userId(), owner);
    }

    private String normalize(String principal) {
        return principal == null || principal.isBlank() ? ANONYMOUS : principal;
    }

    /** A prepared turn: minted id, resolved chain, initial record, and runtime context. */
    record Plan(String id, String conversationId, String owner, InteractionRequest request,
                Interaction initial, AgentExecutionContext context) {
    }
}
