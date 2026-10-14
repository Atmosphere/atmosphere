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
package org.atmosphere.agui.event;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.ai.AiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bidirectional mapper between {@link AiEvent} and {@link AgUiEvent}. Maintains
 * stateful counters for message IDs, tool call IDs, and step IDs to produce
 * correctly sequenced AG-UI event streams.
 *
 * <p>This mapper is <em>not</em> thread-safe — each AG-UI session should use
 * its own mapper instance.</p>
 */
public final class AgUiEventMapper {

    private static final Logger logger = LoggerFactory.getLogger(AgUiEventMapper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicInteger messageCounter = new AtomicInteger();
    private final AtomicInteger toolCallCounter = new AtomicInteger();
    private final AtomicInteger stepCounter = new AtomicInteger();
    private final AtomicInteger activityCounter = new AtomicInteger();

    /** {@code activityType} of the activity message progress reports patch. */
    static final String PROGRESS_ACTIVITY = "progress";

    private volatile String currentMessageId;
    private volatile String currentToolCallId;
    private volatile String currentReasoningId;
    private volatile String currentActivityId;

    /**
     * Convert an {@link AiEvent} into zero or more {@link AgUiEvent}s.
     *
     * @param event the AI event to convert
     * @return the corresponding AG-UI events (may be empty for unmapped event types)
     */
    public List<AgUiEvent> toAgUi(AiEvent event) {
        return switch (event) {
            case AiEvent.TextDelta delta -> {
                var events = new ArrayList<AgUiEvent>();
                if (currentMessageId == null) {
                    currentMessageId = "msg-" + messageCounter.incrementAndGet();
                    events.add(new AgUiEvent.TextMessageStart(currentMessageId, "assistant"));
                }
                events.add(new AgUiEvent.TextMessageContent(currentMessageId, delta.text()));
                yield events;
            }
            case AiEvent.TextComplete ignored -> {
                var events = new ArrayList<AgUiEvent>();
                if (currentMessageId != null) {
                    events.add(new AgUiEvent.TextMessageEnd(currentMessageId));
                    currentMessageId = null;
                }
                yield events;
            }
            case AiEvent.ToolStart start -> {
                currentToolCallId = "tc-" + toolCallCounter.incrementAndGet();
                var events = new ArrayList<AgUiEvent>();
                events.add(new AgUiEvent.ToolCallStart(currentToolCallId, start.toolName(), currentMessageId));
                try {
                    var argsJson = MAPPER.writeValueAsString(start.arguments());
                    events.add(new AgUiEvent.ToolCallArgs(currentToolCallId, argsJson));
                } catch (JacksonException ex) {
                    logger.trace("Best-effort serialization failed for tool args", ex);
                }
                yield events;
            }
            case AiEvent.ToolResult result -> {
                var events = new ArrayList<AgUiEvent>();
                var resultStr = result.result() instanceof String s ? s : serializeQuietly(result.result());
                if (currentToolCallId != null) {
                    events.add(new AgUiEvent.ToolCallResult(currentToolCallId, resultStr));
                    events.add(new AgUiEvent.ToolCallEnd(currentToolCallId));
                    currentToolCallId = null;
                }
                yield events;
            }
            case AiEvent.ToolError ignored -> {
                var events = new ArrayList<AgUiEvent>();
                if (currentToolCallId != null) {
                    events.add(new AgUiEvent.ToolCallEnd(currentToolCallId));
                    currentToolCallId = null;
                }
                yield events;
            }
            case AiEvent.AgentStep step -> {
                var stepId = "step-" + stepCounter.incrementAndGet();
                yield List.<AgUiEvent>of(
                        new AgUiEvent.StepStarted(stepId, step.stepName()),
                        new AgUiEvent.StepFinished(stepId, step.stepName())
                );
            }
            case AiEvent.Progress progress -> {
                // An ACTIVITY_DELTA patches an activity message a prior
                // ACTIVITY_SNAPSHOT established: the first progress of a run
                // snapshots it, later ones patch its content.
                var message = progress.message() != null ? progress.message() : "";
                double percentage = progress.percentage() != null ? progress.percentage() : -1.0;
                if (currentActivityId == null) {
                    currentActivityId = "act-" + activityCounter.incrementAndGet();
                    yield List.<AgUiEvent>of(new AgUiEvent.ActivitySnapshot(currentActivityId,
                            PROGRESS_ACTIVITY,
                            Map.of("message", message, "percentage", percentage),
                            Boolean.TRUE));
                }
                yield List.<AgUiEvent>of(new AgUiEvent.ActivityDelta(currentActivityId,
                        PROGRESS_ACTIVITY, List.of(
                                patchOp("replace", "/message", message),
                                patchOp("replace", "/percentage", percentage))));
            }
            case AiEvent.Error error -> List.<AgUiEvent>of(
                    new AgUiEvent.RunError(null, error.message(),
                            error.code() != null ? error.code().hashCode() : -1)
            );
            case AiEvent.Complete ignored -> List.of();
            // Structured output streams into the shared state as RFC 6902
            // patches: the entity opens at /entity with an empty field map,
            // each field lands at /entity/fields/<name>, and the finished
            // entity replaces the state wholesale.
            case AiEvent.EntityStart start -> List.<AgUiEvent>of(new AgUiEvent.StateDelta(List.of(
                    patchOp("add", "/entity", Map.of(
                            "entityType", start.typeName(),
                            "state", "streaming",
                            "fields", Map.of())))));
            case AiEvent.StructuredField field -> {
                var fieldState = new LinkedHashMap<String, Object>();
                fieldState.put("value", field.value() != null ? field.value() : "");
                if (field.schemaType() != null) {
                    fieldState.put("type", field.schemaType());
                }
                yield List.<AgUiEvent>of(new AgUiEvent.StateDelta(List.of(patchOp("add",
                        "/entity/fields/" + escapePointerToken(field.fieldName()), fieldState))));
            }
            case AiEvent.EntityComplete complete -> {
                var snapshot = new LinkedHashMap<String, Object>();
                snapshot.put("entityType", complete.typeName());
                snapshot.put("entity", complete.entity());
                yield List.<AgUiEvent>of(new AgUiEvent.StateSnapshot(snapshot));
            }
            case AiEvent.ReasoningDelta reasoning -> {
                // Reasoning streams inside its own REASONING_* scope
                // (registre#16 / #29): open lazily on the first delta, close
                // on ReasoningComplete. Redacted chunks open the scope (the
                // client learns reasoning happened) without content.
                var events = new ArrayList<AgUiEvent>();
                if (currentReasoningId == null) {
                    currentReasoningId = "rsn-" + messageCounter.incrementAndGet();
                    events.add(new AgUiEvent.ReasoningStart());
                    events.add(new AgUiEvent.ReasoningMessageStart(currentReasoningId));
                }
                if (!reasoning.redacted() && !reasoning.text().isEmpty()) {
                    events.add(new AgUiEvent.ReasoningMessageContent(
                            currentReasoningId, reasoning.text()));
                }
                yield events;
            }
            case AiEvent.ReasoningComplete ignored -> {
                if (currentReasoningId == null) {
                    yield List.of();
                }
                var closing = List.<AgUiEvent>of(
                        new AgUiEvent.ReasoningMessageEnd(currentReasoningId),
                        new AgUiEvent.ReasoningEnd());
                currentReasoningId = null;
                yield closing;
            }
            case AiEvent.Citation citation -> {
                var value = new LinkedHashMap<String, Object>();
                value.put("text", citation.text());
                value.put("start", citation.start());
                value.put("end", citation.end());
                value.put("sources", citation.sources());
                yield List.<AgUiEvent>of(new AgUiEvent.Custom("citation", value));
            }
            default -> List.of();
        };
    }

    /**
     * Close any in-flight tool call, emitting {@link AgUiEvent.ToolCallEnd}
     * for the tracked id. A {@link AiEvent.ToolStart} with no
     * {@link AiEvent.ToolResult}/{@link AiEvent.ToolError} before run
     * completion would otherwise leave {@code TOOL_CALL_START} dangling —
     * {@link #reset()} silently drops the tracked id. Returns an empty list
     * when no tool call is open, so callers get close-once semantics for free.
     *
     * @return the closing event, or an empty list when no tool call is open
     */
    public List<AgUiEvent> closeOpenToolCall() {
        if (currentToolCallId == null) {
            return List.of();
        }
        var events = List.<AgUiEvent>of(new AgUiEvent.ToolCallEnd(currentToolCallId));
        currentToolCallId = null;
        return events;
    }

    /**
     * Reset the mapper state, clearing tracked message and tool call IDs.
     * Call this between runs to ensure clean state.
     */
    public void reset() {
        currentMessageId = null;
        currentToolCallId = null;
        currentReasoningId = null;
        currentActivityId = null;
    }

    private static Map<String, Object> patchOp(String op, String path, Object value) {
        var operation = new LinkedHashMap<String, Object>();
        operation.put("op", op);
        operation.put("path", path);
        operation.put("value", value);
        return operation;
    }

    /** Escape one JSON Pointer reference token (RFC 6901 section 3). */
    private static String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private String serializeQuietly(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            return String.valueOf(obj);
        }
    }
}
