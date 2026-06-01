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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the live {@code org.atmosphere.ai.AiEvent} stream to the durable
 * {@link InteractionStep} log.
 *
 * <p>Only the variants that carry replayable meaning are persisted:
 * {@code ToolStart}, {@code ToolResult}, {@code ToolError}, {@code AgentStep},
 * {@code ApprovalRequired}, {@code Complete}, and {@code Error}. Text deltas are
 * coalesced into {@code text} steps by {@link InteractionCapturingSession} (one
 * row per token would blow the {@code steps[]} bound), and transport/UI-only
 * variants ({@code StructuredField}, {@code EntityStart}, {@code EntityComplete},
 * {@code RoutingDecision}, {@code Progress}, {@code Handoff}) are dropped from
 * the durable log — {@link #toStep(AiEvent, long)} returns {@link Optional#empty()}
 * for those.</p>
 *
 * <p>Tool results are typed {@code Object} and only contractually JSON-serializable.
 * Serialization is therefore defensive: a value that Jackson cannot convert falls
 * back to its {@code String} form plus a {@code resultType} hint, and never throws
 * out of the capture path — a serialization failure must not break the live stream
 * or the terminal write (Correctness Invariant #2 — Terminal Path Completeness).</p>
 */
public final class InteractionStepMapper {

    /** Step type constants — also the wire-aligned names used by the SQLite store. */
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_TOOL_CALL = "tool-call";
    public static final String TYPE_TOOL_RESULT = "tool-result";
    public static final String TYPE_TOOL_ERROR = "tool-error";
    public static final String TYPE_AGENT_STEP = "agent-step";
    public static final String TYPE_APPROVAL = "approval";
    public static final String TYPE_USAGE = "usage";
    public static final String TYPE_COMPLETION = "completion";
    public static final String TYPE_ERROR = "error";

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionStepMapper.class);

    private final ObjectMapper mapper;
    private final Clock clock;

    public InteractionStepMapper() {
        this(new ObjectMapper(), Clock.systemUTC());
    }

    public InteractionStepMapper(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Map a structured event to a durable step, or {@link Optional#empty()} when
     * the event is text (coalesced elsewhere) or transport-only (dropped).
     */
    public Optional<InteractionStep> toStep(AiEvent event, long seq) {
        return switch (event) {
            case AiEvent.ToolStart e -> Optional.of(step(seq, TYPE_TOOL_CALL, null, e.toolName(),
                    Map.of("arguments", e.arguments()), null));
            case AiEvent.ToolResult e -> Optional.of(step(seq, TYPE_TOOL_RESULT, null, e.toolName(),
                    serializeResult(e.result()), null));
            case AiEvent.ToolError e -> Optional.of(step(seq, TYPE_TOOL_ERROR, e.error(), e.toolName(),
                    Map.of(), null));
            case AiEvent.AgentStep e -> Optional.of(step(seq, TYPE_AGENT_STEP, e.description(),
                    e.stepName(), Map.of("data", e.data()), null));
            case AiEvent.ApprovalRequired e -> Optional.of(step(seq, TYPE_APPROVAL, e.message(),
                    e.toolName(), Map.of("approvalId", e.approvalId(),
                            "arguments", e.arguments(), "expiresIn", e.expiresIn()), null));
            case AiEvent.Complete e -> Optional.of(step(seq, TYPE_COMPLETION, e.summary(), null,
                    Map.of("usage", e.usage()), null));
            case AiEvent.Error e -> Optional.of(step(seq, TYPE_ERROR, e.message(), null,
                    Map.of("code", e.code() != null ? e.code() : "",
                            "recoverable", e.recoverable()), null));
            default -> Optional.empty();
        };
    }

    /** A coalesced text step. */
    public InteractionStep textStep(long seq, String text) {
        return step(seq, TYPE_TEXT, text, null, Map.of(), null);
    }

    /** A typed token-usage step. */
    public InteractionStep usageStep(long seq, TokenUsage usage) {
        return step(seq, TYPE_USAGE, null, null, Map.of(), usage);
    }

    /** A terminal completion step carrying the aggregated final text. */
    public InteractionStep completionStep(long seq, String finalText) {
        return step(seq, TYPE_COMPLETION, finalText, null, Map.of(), null);
    }

    /** A terminal error step carrying the failure message. */
    public InteractionStep errorStep(long seq, String message) {
        return step(seq, TYPE_ERROR, message, null, Map.of(), null);
    }

    private InteractionStep step(long seq, String type, String text, String toolName,
                                 Map<String, Object> data, TokenUsage usage) {
        return new InteractionStep(seq, type, text, toolName, data, usage, Instant.now(clock));
    }

    private Map<String, Object> serializeResult(Object result) {
        if (result == null) {
            return Map.of();
        }
        var data = new LinkedHashMap<String, Object>();
        try {
            data.put("result", mapper.convertValue(result, Object.class));
        } catch (JacksonException | IllegalArgumentException e) {
            // Non-serializable tool result: keep a faithful String projection and
            // the type hint instead of throwing out of the capture path.
            LOGGER.debug("Tool result of type {} not JSON-serializable; storing String form",
                    result.getClass().getName(), e);
            data.put("result", String.valueOf(result));
            data.put("resultType", result.getClass().getName());
        }
        return data;
    }
}
