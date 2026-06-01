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
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coverage for the AiEvent -> durable step mapping, including defensive serialization. */
class InteractionStepMapperTest {

    private final InteractionStepMapper mapper = new InteractionStepMapper();

    @Test
    void mapsToolStartToToolCallStep() {
        var step = mapper.toStep(new AiEvent.ToolStart("weather", Map.of("city", "Montreal")), 3).orElseThrow();
        assertEquals(InteractionStepMapper.TYPE_TOOL_CALL, step.type());
        assertEquals("weather", step.toolName());
        assertEquals(3, step.seq());
        assertEquals(Map.of("city", "Montreal"), step.data().get("arguments"));
    }

    @Test
    void mapsSerializableToolResult() {
        var step = mapper.toStep(new AiEvent.ToolResult("weather", Map.of("temp", 22)), 4).orElseThrow();
        assertEquals(InteractionStepMapper.TYPE_TOOL_RESULT, step.type());
        assertEquals(Map.of("temp", 22), step.data().get("result"));
    }

    @Test
    void nonSerializableToolResultFallsBackToStringForm() {
        // A value whose getter throws during serialization must not break capture.
        var step = mapper.toStep(new AiEvent.ToolResult("calc", new Exploding()), 0).orElseThrow();
        assertEquals(InteractionStepMapper.TYPE_TOOL_RESULT, step.type());
        assertTrue(String.valueOf(step.data().get("result")).contains("Exploding"),
                "String fallback projection retained");
        assertEquals(Exploding.class.getName(), step.data().get("resultType"),
                "type hint recorded on fallback");
    }

    /** A bean Jackson cannot serialize — its getter (invoked reflectively during
     *  {@code convertValue}) throws mid-serialization. */
    static final class Exploding {
        public String getValue() {
            throw new IllegalStateException("not serializable");
        }
    }

    @Test
    void mapsToolErrorAndAgentStepAndApproval() {
        assertEquals(InteractionStepMapper.TYPE_TOOL_ERROR,
                mapper.toStep(new AiEvent.ToolError("t", "boom"), 0).orElseThrow().type());
        assertEquals(InteractionStepMapper.TYPE_AGENT_STEP,
                mapper.toStep(new AiEvent.AgentStep("plan", "Planning", Map.of()), 0).orElseThrow().type());
        var approval = mapper.toStep(
                new AiEvent.ApprovalRequired("a1", "delete", Map.of(), "ok?", 30), 0).orElseThrow();
        assertEquals(InteractionStepMapper.TYPE_APPROVAL, approval.type());
        assertEquals("a1", approval.data().get("approvalId"));
    }

    @Test
    void transportOnlyEventsAreDropped() {
        assertTrue(mapper.toStep(new AiEvent.TextDelta("hi"), 0).isEmpty(), "text coalesced elsewhere");
        assertTrue(mapper.toStep(new AiEvent.TextComplete("hi"), 0).isEmpty(), "text coalesced elsewhere");
        assertTrue(mapper.toStep(new AiEvent.Progress("...", 0.5), 0).isEmpty());
        assertTrue(mapper.toStep(new AiEvent.RoutingDecision("a", "b", "why"), 0).isEmpty());
        assertTrue(mapper.toStep(new AiEvent.Handoff("a", "b", "why"), 0).isEmpty());
        assertTrue(mapper.toStep(new AiEvent.StructuredField("f", 1, "integer"), 0).isEmpty());
        assertTrue(mapper.toStep(new AiEvent.EntityStart("T", "{}"), 0).isEmpty());
        assertTrue(mapper.toStep(new AiEvent.EntityComplete("T", new Object()), 0).isEmpty());
    }

    @Test
    void helperStepsCarryExpectedTypes() {
        assertEquals(InteractionStepMapper.TYPE_TEXT, mapper.textStep(0, "x").type());
        assertEquals(InteractionStepMapper.TYPE_COMPLETION, mapper.completionStep(1, "done").type());
        assertEquals(InteractionStepMapper.TYPE_ERROR, mapper.errorStep(2, "bad").type());
        var usage = mapper.usageStep(3, org.atmosphere.ai.TokenUsage.of(10, 20));
        assertEquals(InteractionStepMapper.TYPE_USAGE, usage.type());
        assertEquals(30, usage.usage().total());
    }
}
