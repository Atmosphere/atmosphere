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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sealed interface representing all 28 AG-UI protocol events. Each record variant
 * maps to a specific wire-format event type defined by {@link AgUiEventType}.
 *
 * <p>Being sealed enables exhaustive pattern matching in switch expressions,
 * ensuring compile-time safety when handling AG-UI events.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface AgUiEvent {

    /**
     * Returns the wire-format event type string (e.g., "RUN_STARTED", "TEXT_MESSAGE_CONTENT").
     */
    String type();

    // ── Lifecycle (5) ───────────────────────────────────────────────────

    record RunStarted(String runId, String threadId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.RUN_STARTED.value();
        }
    }

    record RunFinished(String runId, String threadId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.RUN_FINISHED.value();
        }
    }

    record RunError(String runId, String message, int code) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.RUN_ERROR.value();
        }
    }

    record StepStarted(String stepId, String name) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.STEP_STARTED.value();
        }
    }

    record StepFinished(String stepId, String name) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.STEP_FINISHED.value();
        }
    }

    // ── Text Messages (4) ───────────────────────────────────────────────

    record TextMessageStart(String messageId, String role) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TEXT_MESSAGE_START.value();
        }
    }

    record TextMessageContent(String messageId, String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TEXT_MESSAGE_CONTENT.value();
        }
    }

    record TextMessageEnd(String messageId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TEXT_MESSAGE_END.value();
        }
    }

    record TextMessageChunk(String messageId, String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TEXT_MESSAGE_CHUNK.value();
        }
    }

    // ── Tool Calls (5) ──────────────────────────────────────────────────

    record ToolCallStart(String toolCallId, String name, String parentMessageId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_START.value();
        }
    }

    record ToolCallArgs(String toolCallId, String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_ARGS.value();
        }
    }

    record ToolCallEnd(String toolCallId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_END.value();
        }
    }

    record ToolCallResult(String toolCallId, String result) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_RESULT.value();
        }
    }

    record ToolCallChunk(String toolCallId, String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_CHUNK.value();
        }
    }

    // ── State (3) ───────────────────────────────────────────────────────

    record StateSnapshot(String snapshot) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.STATE_SNAPSHOT.value();
        }
    }

    record StateDelta(String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.STATE_DELTA.value();
        }
    }

    record MessagesSnapshot(String messages) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.MESSAGES_SNAPSHOT.value();
        }
    }

    // ── Reasoning (6) ───────────────────────────────────────────────────

    record ReasoningStart() implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.REASONING_START.value();
        }
    }

    record ReasoningMessageStart(String messageId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.REASONING_MESSAGE_START.value();
        }
    }

    record ReasoningMessageContent(String messageId, String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.REASONING_MESSAGE_CONTENT.value();
        }
    }

    record ReasoningMessageEnd(String messageId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.REASONING_MESSAGE_END.value();
        }
    }

    record ReasoningMessageChunk(String messageId, String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.REASONING_MESSAGE_CHUNK.value();
        }
    }

    record ReasoningEnd() implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.REASONING_END.value();
        }
    }

    // ── Activity (2) ────────────────────────────────────────────────────

    record ActivitySnapshot(String activity) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.ACTIVITY_SNAPSHOT.value();
        }
    }

    record ActivityDelta(String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.ACTIVITY_DELTA.value();
        }
    }

    // ── Special (2) ─────────────────────────────────────────────────────

    record Raw(String data) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.RAW.value();
        }
    }

    record Custom(String name, String value) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.CUSTOM.value();
        }
    }
}
