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
}
