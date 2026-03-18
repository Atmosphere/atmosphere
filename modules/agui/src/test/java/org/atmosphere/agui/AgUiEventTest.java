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
package org.atmosphere.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.agui.event.AgUiEvent;
import org.atmosphere.agui.event.AgUiEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgUiEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allEventTypesHaveCorrectTypeString() {
        assertEquals("RUN_STARTED", new AgUiEvent.RunStarted("r1", "t1").type());
        assertEquals("RUN_FINISHED", new AgUiEvent.RunFinished("r1", "t1").type());
        assertEquals("RUN_ERROR", new AgUiEvent.RunError("r1", "err", 500).type());
        assertEquals("STEP_STARTED", new AgUiEvent.StepStarted("s1", "step").type());
        assertEquals("STEP_FINISHED", new AgUiEvent.StepFinished("s1", "step").type());
        assertEquals("TEXT_MESSAGE_START", new AgUiEvent.TextMessageStart("m1", "assistant").type());
        assertEquals("TEXT_MESSAGE_CONTENT", new AgUiEvent.TextMessageContent("m1", "hi").type());
        assertEquals("TEXT_MESSAGE_END", new AgUiEvent.TextMessageEnd("m1").type());
        assertEquals("TEXT_MESSAGE_CHUNK", new AgUiEvent.TextMessageChunk("m1", "chunk").type());
        assertEquals("TOOL_CALL_START", new AgUiEvent.ToolCallStart("tc1", "tool", "m1").type());
        assertEquals("TOOL_CALL_ARGS", new AgUiEvent.ToolCallArgs("tc1", "{}").type());
        assertEquals("TOOL_CALL_END", new AgUiEvent.ToolCallEnd("tc1").type());
        assertEquals("TOOL_CALL_RESULT", new AgUiEvent.ToolCallResult("tc1", "ok").type());
        assertEquals("TOOL_CALL_CHUNK", new AgUiEvent.ToolCallChunk("tc1", "chunk").type());
        assertEquals("STATE_SNAPSHOT", new AgUiEvent.StateSnapshot("{}").type());
        assertEquals("STATE_DELTA", new AgUiEvent.StateDelta("[]").type());
        assertEquals("MESSAGES_SNAPSHOT", new AgUiEvent.MessagesSnapshot("[]").type());
        assertEquals("REASONING_START", new AgUiEvent.ReasoningStart().type());
        assertEquals("REASONING_MESSAGE_START", new AgUiEvent.ReasoningMessageStart("r1").type());
        assertEquals("REASONING_MESSAGE_CONTENT", new AgUiEvent.ReasoningMessageContent("r1", "thinking").type());
        assertEquals("REASONING_MESSAGE_END", new AgUiEvent.ReasoningMessageEnd("r1").type());
        assertEquals("REASONING_MESSAGE_CHUNK", new AgUiEvent.ReasoningMessageChunk("r1", "chunk").type());
        assertEquals("REASONING_END", new AgUiEvent.ReasoningEnd().type());
        assertEquals("ACTIVITY_SNAPSHOT", new AgUiEvent.ActivitySnapshot("active").type());
        assertEquals("ACTIVITY_DELTA", new AgUiEvent.ActivityDelta("delta").type());
        assertEquals("RAW", new AgUiEvent.Raw("data").type());
        assertEquals("CUSTOM", new AgUiEvent.Custom("name", "value").type());
    }

    @Test
    void eventSerialization() throws Exception {
        var event = new AgUiEvent.TextMessageContent("msg-1", "Hello");
        var json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"messageId\":\"msg-1\""));
        assertTrue(json.contains("\"delta\":\"Hello\""));
    }

    @Test
    void sealedInterfaceExhaustiveSwitch() {
        AgUiEvent event = new AgUiEvent.RunStarted("r1", "t1");
        var result = switch (event) {
            case AgUiEvent.RunStarted e -> "started";
            case AgUiEvent.RunFinished e -> "finished";
            case AgUiEvent.RunError e -> "error";
            case AgUiEvent.StepStarted e -> "step-start";
            case AgUiEvent.StepFinished e -> "step-end";
            case AgUiEvent.TextMessageStart e -> "text-start";
            case AgUiEvent.TextMessageContent e -> "text-content";
            case AgUiEvent.TextMessageEnd e -> "text-end";
            case AgUiEvent.TextMessageChunk e -> "text-chunk";
            case AgUiEvent.ToolCallStart e -> "tool-start";
            case AgUiEvent.ToolCallArgs e -> "tool-args";
            case AgUiEvent.ToolCallEnd e -> "tool-end";
            case AgUiEvent.ToolCallResult e -> "tool-result";
            case AgUiEvent.ToolCallChunk e -> "tool-chunk";
            case AgUiEvent.StateSnapshot e -> "state-snap";
            case AgUiEvent.StateDelta e -> "state-delta";
            case AgUiEvent.MessagesSnapshot e -> "msg-snap";
            case AgUiEvent.ReasoningStart e -> "reason-start";
            case AgUiEvent.ReasoningMessageStart e -> "reason-msg-start";
            case AgUiEvent.ReasoningMessageContent e -> "reason-content";
            case AgUiEvent.ReasoningMessageEnd e -> "reason-msg-end";
            case AgUiEvent.ReasoningMessageChunk e -> "reason-chunk";
            case AgUiEvent.ReasoningEnd e -> "reason-end";
            case AgUiEvent.ActivitySnapshot e -> "activity-snap";
            case AgUiEvent.ActivityDelta e -> "activity-delta";
            case AgUiEvent.Raw e -> "raw";
            case AgUiEvent.Custom e -> "custom";
        };
        assertEquals("started", result);
    }

    @Test
    void eventTypeEnumValues() {
        assertEquals(27, AgUiEventType.values().length);
    }
}
