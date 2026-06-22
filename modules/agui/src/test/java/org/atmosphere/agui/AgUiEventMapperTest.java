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

import org.atmosphere.agui.event.AgUiEvent;
import org.atmosphere.agui.event.AgUiEventMapper;
import org.atmosphere.ai.AiEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgUiEventMapperTest {

    private AgUiEventMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AgUiEventMapper();
    }

    @Test
    void textDeltaStartsMessageAndEmitsContent() {
        var events = mapper.toAgUi(new AiEvent.TextDelta("Hello"));
        assertEquals(2, events.size());
        assertInstanceOf(AgUiEvent.TextMessageStart.class, events.get(0));
        assertInstanceOf(AgUiEvent.TextMessageContent.class, events.get(1));

        var start = (AgUiEvent.TextMessageStart) events.get(0);
        assertEquals("assistant", start.role());

        var content = (AgUiEvent.TextMessageContent) events.get(1);
        assertEquals("Hello", content.delta());
    }

    @Test
    void subsequentTextDeltasDoNotStartNewMessage() {
        mapper.toAgUi(new AiEvent.TextDelta("Hello"));
        var events = mapper.toAgUi(new AiEvent.TextDelta(" World"));
        assertEquals(1, events.size());
        assertInstanceOf(AgUiEvent.TextMessageContent.class, events.get(0));
    }

    @Test
    void textCompleteEndsMessage() {
        mapper.toAgUi(new AiEvent.TextDelta("Hello"));
        var events = mapper.toAgUi(new AiEvent.TextComplete("Hello World"));
        assertEquals(1, events.size());
        assertInstanceOf(AgUiEvent.TextMessageEnd.class, events.get(0));
    }

    @Test
    void toolStartCreatesToolCallEvents() {
        var events = mapper.toAgUi(new AiEvent.ToolStart("weather", Map.of("city", "Montreal")));
        assertEquals(2, events.size());
        assertInstanceOf(AgUiEvent.ToolCallStart.class, events.get(0));
        assertInstanceOf(AgUiEvent.ToolCallArgs.class, events.get(1));

        var start = (AgUiEvent.ToolCallStart) events.get(0);
        assertEquals("weather", start.name());
    }

    @Test
    void toolResultEndsToolCall() {
        mapper.toAgUi(new AiEvent.ToolStart("weather", Map.of()));
        var events = mapper.toAgUi(new AiEvent.ToolResult("weather", "22\u00b0C"));
        assertEquals(2, events.size());
        assertInstanceOf(AgUiEvent.ToolCallResult.class, events.get(0));
        assertInstanceOf(AgUiEvent.ToolCallEnd.class, events.get(1));
    }

    @Test
    void toolErrorEndsToolCall() {
        mapper.toAgUi(new AiEvent.ToolStart("weather", Map.of()));
        var events = mapper.toAgUi(new AiEvent.ToolError("weather", "timeout"));
        assertEquals(1, events.size());
        assertInstanceOf(AgUiEvent.ToolCallEnd.class, events.get(0));
    }

    @Test
    void agentStepMapsToStepEvents() {
        var events = mapper.toAgUi(new AiEvent.AgentStep("s1", "Analyzing", Map.of()));
        assertEquals(2, events.size());
        assertInstanceOf(AgUiEvent.StepStarted.class, events.get(0));
        assertInstanceOf(AgUiEvent.StepFinished.class, events.get(1));
    }

    @Test
    void progressMapsToActivityDelta() {
        var events = mapper.toAgUi(new AiEvent.Progress("Loading...", 0.5));
        assertEquals(1, events.size());
        assertInstanceOf(AgUiEvent.ActivityDelta.class, events.get(0));
    }

    @Test
    void errorMapsToRunError() {
        var events = mapper.toAgUi(new AiEvent.Error("failed", "500", false));
        assertEquals(1, events.size());
        assertInstanceOf(AgUiEvent.RunError.class, events.get(0));
    }

    @Test
    void completeReturnsEmpty() {
        var events = mapper.toAgUi(new AiEvent.Complete("done", Map.of()));
        assertTrue(events.isEmpty());
    }

    @Test
    void resetClearsState() {
        mapper.toAgUi(new AiEvent.TextDelta("Hello"));
        mapper.reset();
        // After reset, next TextDelta should start a new message
        var events = mapper.toAgUi(new AiEvent.TextDelta("Hi"));
        assertEquals(2, events.size());
        assertInstanceOf(AgUiEvent.TextMessageStart.class, events.get(0));
    }

    /**
     * Regression for the real-pipeline streaming shape: a multi-delta message
     * must produce exactly one TEXT_MESSAGE_START, one TEXT_MESSAGE_CONTENT per
     * delta, and one TEXT_MESSAGE_END — all carrying a single stable messageId.
     */
    @Test
    void multiDeltaProducesSingleStartManyContentSingleEndWithStableId() {
        var d1 = mapper.toAgUi(new AiEvent.TextDelta("Hello"));
        var d2 = mapper.toAgUi(new AiEvent.TextDelta(" "));
        var d3 = mapper.toAgUi(new AiEvent.TextDelta("World"));
        var end = mapper.toAgUi(new AiEvent.TextComplete("Hello World"));

        // First delta: START + CONTENT; subsequent deltas: CONTENT only.
        assertEquals(2, d1.size());
        assertInstanceOf(AgUiEvent.TextMessageStart.class, d1.get(0));
        assertInstanceOf(AgUiEvent.TextMessageContent.class, d1.get(1));
        assertEquals(1, d2.size());
        assertInstanceOf(AgUiEvent.TextMessageContent.class, d2.get(0));
        assertEquals(1, d3.size());
        assertInstanceOf(AgUiEvent.TextMessageContent.class, d3.get(0));

        // Terminal: exactly one TEXT_MESSAGE_END.
        assertEquals(1, end.size());
        assertInstanceOf(AgUiEvent.TextMessageEnd.class, end.get(0));

        // One stable messageId across START, all CONTENT, and END.
        var start = (AgUiEvent.TextMessageStart) d1.get(0);
        var messageId = start.messageId();
        assertEquals(messageId, ((AgUiEvent.TextMessageContent) d1.get(1)).messageId());
        assertEquals(messageId, ((AgUiEvent.TextMessageContent) d2.get(0)).messageId());
        assertEquals(messageId, ((AgUiEvent.TextMessageContent) d3.get(0)).messageId());
        assertEquals(messageId, ((AgUiEvent.TextMessageEnd) end.get(0)).messageId());

        // Concatenated deltas reproduce the streamed text verbatim.
        var joined = ((AgUiEvent.TextMessageContent) d1.get(1)).delta()
                + ((AgUiEvent.TextMessageContent) d2.get(0)).delta()
                + ((AgUiEvent.TextMessageContent) d3.get(0)).delta();
        assertEquals("Hello World", joined);
    }

    /**
     * Regression for real {@code @AiTool} dispatch through the bridge:
     * ToolStart('get_weather', {city}) → TOOL_CALL_START(name) + TOOL_CALL_ARGS,
     * ToolResult → TOOL_CALL_RESULT + TOOL_CALL_END with the matching toolCallId,
     * and a second tool call advances the id (tc-1 → tc-2) — proving
     * currentToolCallId resets between calls.
     */
    @Test
    void twoToolCallsAdvanceToolCallIdAndMatchResultToStart() {
        // First tool call: get_weather.
        var start1 = mapper.toAgUi(new AiEvent.ToolStart("get_weather", Map.of("city", "Paris")));
        assertEquals(2, start1.size());
        var callStart1 = (AgUiEvent.ToolCallStart) start1.get(0);
        var args1 = (AgUiEvent.ToolCallArgs) start1.get(1);
        assertEquals("get_weather", callStart1.name());
        assertEquals("tc-1", callStart1.toolCallId());
        assertEquals("tc-1", args1.toolCallId());
        assertTrue(args1.delta().contains("Paris"), "args JSON should carry the city argument");

        var result1 = mapper.toAgUi(new AiEvent.ToolResult("get_weather", "Paris: Clear, 20C"));
        assertEquals(2, result1.size());
        var callResult1 = (AgUiEvent.ToolCallResult) result1.get(0);
        var callEnd1 = (AgUiEvent.ToolCallEnd) result1.get(1);
        assertEquals("tc-1", callResult1.toolCallId());
        assertEquals("tc-1", callEnd1.toolCallId());

        // Second tool call: get_time — must advance to tc-2 (id reset proven).
        var start2 = mapper.toAgUi(new AiEvent.ToolStart("get_time", Map.of("timezone", "Europe/Paris")));
        var callStart2 = (AgUiEvent.ToolCallStart) start2.get(0);
        assertEquals("get_time", callStart2.name());
        assertEquals("tc-2", callStart2.toolCallId());

        var result2 = mapper.toAgUi(new AiEvent.ToolResult("get_time", "12:00"));
        var callResult2 = (AgUiEvent.ToolCallResult) result2.get(0);
        var callEnd2 = (AgUiEvent.ToolCallEnd) result2.get(1);
        assertEquals("tc-2", callResult2.toolCallId());
        assertEquals("tc-2", callEnd2.toolCallId());
    }
}
