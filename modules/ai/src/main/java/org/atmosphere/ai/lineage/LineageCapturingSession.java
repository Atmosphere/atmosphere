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
package org.atmosphere.ai.lineage;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.DelegatingStreamingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.identity.AgentFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decorating {@link StreamingSession} that observes events flowing through a
 * {@code @Prompt} invocation and emits exactly one
 * {@link LineageEntry} on the terminal callback. Tied together fields:
 * prompt, agent / user / conversation identity, tool calls, RAG sources,
 * token usage, attributed cost, terminal reason.
 *
 * <p>Pure observer — never mutates the stream. All forwarding goes through
 * {@link DelegatingStreamingSession} so the session contract stays
 * complete (forwards every method, including {@code injectables}).</p>
 *
 * <p>Idempotent: a record is emitted at most once per session, on whichever
 * of {@link #complete}, {@link #complete(String)}, or {@link #error}
 * fires first. Subsequent terminal callbacks just forward without
 * re-recording — closes Correctness Invariant #2 (Terminal Path
 * Completeness) for the lineage layer.</p>
 *
 * <p>When built with an {@link AgentFingerprint}, the entry's attributes carry
 * its {@code ai.agent.*} keys, so the turn's tool calls are attributable to the
 * runtime, model, system-prompt version and tool set that produced them. Those
 * keys are reserved: metadata the runtime sends under those names is not
 * recorded, including a name the fingerprint leaves empty.</p>
 *
 * <p>Built with {@code observeEmittedToolEvents} {@code false}, the session
 * does not read the tool events emitted through it: the session it decorates
 * sees every event of the turn, including those emitted on it directly by
 * application code, and hands them to {@link #observeToolEvent(AiEvent)}
 * instead, so each one is recorded once.</p>
 */
public final class LineageCapturingSession extends DelegatingStreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(LineageCapturingSession.class);

    private final LineageRecorder recorder;
    private final String requestId;
    private final String userId;
    private final String agentId;
    private final String conversationId;
    private final String userPrompt;
    private final Instant startedAt;

    private final List<LineageEntry.ToolCall> toolCalls = new ArrayList<>();
    private final List<String> ragSources = new ArrayList<>();
    private final Map<String, Instant> pendingToolStarts = new LinkedHashMap<>();
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final Map<String, String> fingerprintAttributes;
    private final boolean fingerprinted;
    private final boolean observeEmittedToolEvents;
    private final AtomicBoolean recorded = new AtomicBoolean();

    private volatile TokenUsage capturedTokens;
    private volatile BigDecimal capturedCost;

    public LineageCapturingSession(StreamingSession delegate, LineageRecorder recorder,
                                   String userId, String agentId, String conversationId,
                                   String userPrompt) {
        this(delegate, recorder, userId, agentId, conversationId, userPrompt, null);
    }

    /**
     * @param fingerprint the configuration that serves the turn, recorded under
     *                    the entry's {@code ai.agent.*} attributes; {@code null}
     *                    records none
     */
    public LineageCapturingSession(StreamingSession delegate, LineageRecorder recorder,
                                   String userId, String agentId, String conversationId,
                                   String userPrompt, AgentFingerprint fingerprint) {
        this(delegate, recorder, userId, agentId, conversationId, userPrompt, fingerprint, true);
    }

    /**
     * @param fingerprint              the configuration that serves the turn,
     *                                 recorded under the entry's {@code ai.agent.*}
     *                                 attributes; {@code null} records none
     * @param observeEmittedToolEvents {@code false} when the decorated session
     *                                 hands the turn's tool events to
     *                                 {@link #observeToolEvent(AiEvent)} itself
     */
    public LineageCapturingSession(StreamingSession delegate, LineageRecorder recorder,
                                   String userId, String agentId, String conversationId,
                                   String userPrompt, AgentFingerprint fingerprint,
                                   boolean observeEmittedToolEvents) {
        super(delegate);
        this.observeEmittedToolEvents = observeEmittedToolEvents;
        this.fingerprinted = fingerprint != null;
        this.fingerprintAttributes = fingerprint == null ? Map.of() : fingerprint.asAttributes();
        this.recorder = recorder == null ? LineageRecorder.NOOP : recorder;
        this.requestId = "lin_" + UUID.randomUUID();
        this.userId = userId == null ? "" : userId;
        this.agentId = agentId == null ? "" : agentId;
        this.conversationId = conversationId == null ? "" : conversationId;
        this.userPrompt = userPrompt == null ? "" : userPrompt;
        this.startedAt = Instant.now();
    }

    /** @return the request id assigned to this lineage record. */
    public String requestId() {
        return requestId;
    }

    @Override
    public void emit(AiEvent event) {
        if (observeEmittedToolEvents) {
            observeToolEvent(event);
        }
        super.emit(event);
    }

    /**
     * Record a tool event of the turn: a {@code ToolStart} opens a call, a
     * {@code ToolResult} or {@code ToolError} ends it. Other events are
     * ignored, and so is anything after the entry was recorded.
     *
     * @param event an event of the turn
     */
    public void observeToolEvent(AiEvent event) {
        observeToolEvent(event, Instant.now());
    }

    /**
     * As {@link #observeToolEvent(AiEvent)}, for an event emitted at {@code at}
     * (one the decorated session kept until this layer existed).
     *
     * @param event an event of the turn
     * @param at    when it was emitted
     */
    public void observeToolEvent(AiEvent event, Instant at) {
        if (event == null || recorded.get()) {
            return;
        }
        synchronized (toolCalls) {
            observe(event, at != null ? at : Instant.now());
        }
    }

    /** @return whether this layer already recorded its entry; later events are not recorded */
    public boolean isRecorded() {
        return recorded.get();
    }

    @Override
    public void usage(TokenUsage usage) {
        if (usage != null) {
            this.capturedTokens = usage;
        }
        super.usage(usage);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        if (key != null && value != null) {
            switch (key) {
                case "ai.cost.usd" -> capturedCost = toBigDecimal(value);
                case "rag.source", "rag.sources" -> appendRagSources(value);
                default -> {
                    // Capture any other ai.* metadata as audit attributes — string-coerced
                    // so the LineageEntry never holds caller-supplied object refs.
                    if ((key.startsWith("ai.") || key.startsWith("rag."))
                            && !(fingerprinted && AgentFingerprint.isAttribute(key))) {
                        attributes.put(key, value.toString());
                    }
                }
            }
        }
        super.sendMetadata(key, value);
    }

    @Override
    public void complete() {
        recordIfFirst("OK");
        super.complete();
    }

    @Override
    public void complete(String summary) {
        recordIfFirst("OK");
        super.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        recordIfFirst(classifyError(t));
        super.error(t);
    }

    private void observe(AiEvent event, Instant at) {
        switch (event) {
            case AiEvent.ToolStart start ->
                    pendingToolStarts.put(start.toolName(), at);
            case AiEvent.ToolResult result -> {
                var startedToolAt = pendingToolStarts.remove(result.toolName());
                toolCalls.add(new LineageEntry.ToolCall(
                        result.toolName(),
                        startedToolAt != null ? startedToolAt : at,
                        result.result() == null ? "" : result.result().toString()));
            }
            case AiEvent.ToolError failure -> {
                // A call that failed ends with ToolError: it is still a call the
                // turn made, recorded with its error.
                var startedToolAt = pendingToolStarts.remove(failure.toolName());
                toolCalls.add(new LineageEntry.ToolCall(
                        failure.toolName(),
                        startedToolAt != null ? startedToolAt : at,
                        failure.error() == null ? "" : failure.error()));
            }
            default -> {
                // Other event types (Handoff, EntityStart, etc.) don't contribute
                // to the lineage shape today. Future fields can grow here without
                // breaking the LineageEntry record (it only adds optional
                // attributes via the attributes map).
            }
        }
    }

    private Map<String, String> entryAttributes() {
        if (!fingerprinted) {
            return Map.copyOf(attributes);
        }
        var merged = new LinkedHashMap<String, String>(attributes);
        merged.putAll(fingerprintAttributes);
        return Map.copyOf(merged);
    }

    private void recordIfFirst(String terminalReason) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        try {
            List<LineageEntry.ToolCall> calls;
            synchronized (toolCalls) {
                calls = List.copyOf(toolCalls);
            }
            // The entry's timestamp is when the prompt started. A tool call the
            // prompt made before this layer was built (replayed with the time
            // it was emitted) started the prompt earlier than the layer did.
            var promptStartedAt = startedAt;
            for (var call : calls) {
                if (call.invokedAt() != null && call.invokedAt().isBefore(promptStartedAt)) {
                    promptStartedAt = call.invokedAt();
                }
            }
            var entry = new LineageEntry(
                    requestId, userId, agentId, conversationId, sessionId(),
                    promptStartedAt, userPrompt,
                    calls,
                    List.copyOf(ragSources),
                    Optional.ofNullable(capturedTokens),
                    Optional.ofNullable(capturedCost),
                    terminalReason,
                    entryAttributes());
            recorder.record(entry);
        } catch (RuntimeException e) {
            // Lineage capture is best-effort — never block the prompt's
            // terminal path. Log loudly so the operator notices the audit
            // gap without compromising user-visible behavior.
            logger.warn("LineageRecorder.record threw for request {}: {}",
                    requestId, e.toString(), e);
        }
    }

    private String classifyError(Throwable t) {
        if (t == null) {
            return "ERROR";
        }
        var name = t.getClass().getSimpleName();
        if (name.contains("Cancel")) {
            return "CANCELLED";
        }
        if (name.contains("Security") || name.contains("Forbidden")
                || name.contains("Unauthorized")) {
            return "DENIED";
        }
        return "ERROR";
    }

    private void appendRagSources(Object value) {
        if (value instanceof java.util.Collection<?> collection) {
            for (var v : collection) {
                if (v != null) {
                    ragSources.add(v.toString());
                }
            }
        } else {
            ragSources.add(value.toString());
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
