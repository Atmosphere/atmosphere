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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.ai.AiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    private volatile String currentMessageId;
    private volatile String currentToolCallId;

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
                } catch (JsonProcessingException ex) {
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
                var json = serializeQuietly(Map.of(
                        "message", progress.message(),
                        "percentage", progress.percentage() != null ? progress.percentage() : -1));
                yield List.<AgUiEvent>of(new AgUiEvent.ActivityDelta(json));
            }
            case AiEvent.Error error -> List.<AgUiEvent>of(
                    new AgUiEvent.RunError(null, error.message(),
                            error.code() != null ? error.code().hashCode() : -1)
            );
            case AiEvent.Complete ignored -> List.of();
            // StructuredField, EntityStart, EntityComplete, RoutingDecision have no AG-UI mapping
            default -> List.of();
        };
    }

    /**
     * Reset the mapper state, clearing tracked message and tool call IDs.
     * Call this between runs to ensure clean state.
     */
    public void reset() {
        currentMessageId = null;
        currentToolCallId = null;
    }

    private String serializeQuietly(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }
}
