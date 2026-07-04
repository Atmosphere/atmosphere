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
package org.atmosphere.ai.plan;

import org.atmosphere.ai.AiEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link AgentPlan} model: immutability, the Markdown checklist the
 * {@code write_todos} tool returns, the wire-step shape
 * {@link AiEvent.PlanUpdate} carries, lenient {@link PlanStatus} parsing, and
 * the Jackson round-trip the file store and the wire both rely on.
 */
public class AgentPlanTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void stepsAreDefensivelyCopied() {
        var mutable = new java.util.ArrayList<AgentPlan.Step>();
        mutable.add(new AgentPlan.Step("a", PlanStatus.PENDING, null));
        var plan = new AgentPlan(null, mutable);

        mutable.add(new AgentPlan.Step("b", PlanStatus.PENDING, null));

        assertEquals(1, plan.steps().size(), "the plan must not see later list mutations");
        assertThrows(UnsupportedOperationException.class,
                () -> plan.steps().add(new AgentPlan.Step("c", PlanStatus.PENDING, null)));
    }

    @Test
    public void nullStepsCollapseToEmpty() {
        var plan = new AgentPlan("goal", null);
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    public void blankContentIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentPlan.Step("  ", PlanStatus.PENDING, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentPlan.Step(null, PlanStatus.PENDING, null));
    }

    @Test
    public void nullStatusDefaultsToPending() {
        assertEquals(PlanStatus.PENDING, new AgentPlan.Step("a", null, null).status());
    }

    @Test
    public void markdownRendersEveryStatusMarker() {
        var plan = new AgentPlan("Ship it", List.of(
                new AgentPlan.Step("Write tests", PlanStatus.COMPLETED, null),
                new AgentPlan.Step("Fix bug", PlanStatus.IN_PROGRESS, "Fixing bug"),
                new AgentPlan.Step("Update docs", PlanStatus.PENDING, null),
                new AgentPlan.Step("Gold-plate", PlanStatus.ABANDONED, null)));

        var markdown = plan.toMarkdown();

        assertEquals("""
                Goal: Ship it
                - [x] Write tests
                - [~] Fixing bug
                - [ ] Update docs
                - [-] Gold-plate""", markdown);
    }

    @Test
    public void markdownOfEmptyPlanSaysSo() {
        assertEquals("(no steps)", AgentPlan.empty().toMarkdown());
    }

    @Test
    public void wireStepsAreLowerCasedAndOrdered() {
        var plan = new AgentPlan(null, List.of(
                new AgentPlan.Step("first", PlanStatus.IN_PROGRESS, "doing first"),
                new AgentPlan.Step("second", PlanStatus.PENDING, null)));

        var wire = plan.toWireSteps();

        assertEquals(2, wire.size());
        assertEquals("first", wire.get(0).get("content"));
        assertEquals("in_progress", wire.get(0).get("status"));
        assertEquals("doing first", wire.get(0).get("activeForm"));
        assertEquals("second", wire.get(1).get("content"));
        assertEquals("pending", wire.get(1).get("status"));
        assertNull(wire.get(1).get("activeForm"),
                "an absent activeForm must not appear in the wire map");
    }

    @Test
    public void planStatusParsesLeniently() {
        assertEquals(PlanStatus.PENDING, PlanStatus.parse(null));
        assertEquals(PlanStatus.PENDING, PlanStatus.parse("  "));
        assertEquals(PlanStatus.PENDING, PlanStatus.parse("garbage"));
        assertEquals(PlanStatus.IN_PROGRESS, PlanStatus.parse("in_progress"));
        assertEquals(PlanStatus.IN_PROGRESS, PlanStatus.parse(" IN-PROGRESS "));
        assertEquals(PlanStatus.COMPLETED, PlanStatus.parse("done"));
        assertEquals(PlanStatus.COMPLETED, PlanStatus.parse("Completed"));
        assertEquals(PlanStatus.ABANDONED, PlanStatus.parse("cancelled"));
        assertEquals(PlanStatus.ABANDONED, PlanStatus.parse("ABANDONED"));
    }

    @Test
    public void jacksonRoundTripPreservesThePlan() {
        var plan = new AgentPlan("goal", List.of(
                new AgentPlan.Step("a", PlanStatus.COMPLETED, "doing a"),
                new AgentPlan.Step("b", PlanStatus.PENDING, null)));

        var json = MAPPER.writeValueAsString(plan);
        var back = MAPPER.readValue(json, AgentPlan.class);

        assertEquals(plan, back, "the wire mapper must round-trip the record");
    }

    @Test
    public void planUpdateEventSerializesThroughTheWireMapper() {
        // DefaultStreamingSession.buildEventMessage serializes the raw event
        // record as the "data" field with the same mapper type — pin that a
        // PlanUpdate survives it without custom serializers.
        var plan = new AgentPlan("g", List.of(
                new AgentPlan.Step("a", PlanStatus.IN_PROGRESS, "doing a")));
        var event = new AiEvent.PlanUpdate(plan.toWireSteps(), plan.goal());

        assertEquals("plan-update", event.eventType());

        var msg = new LinkedHashMap<String, Object>();
        msg.put("event", event.eventType());
        msg.put("data", event);
        var json = MAPPER.writeValueAsString(msg);

        assertTrue(json.contains("\"event\":\"plan-update\""), json);
        assertTrue(json.contains("\"goal\":\"g\""), json);
        assertTrue(json.contains("\"status\":\"in_progress\""), json);
        assertTrue(json.contains("\"activeForm\":\"doing a\""), json);
    }

    @Test
    public void planUpdateStepsAreDefensivelyCopied() {
        var steps = new java.util.ArrayList<Map<String, Object>>();
        steps.add(Map.of("content", "a", "status", "pending"));
        var event = new AiEvent.PlanUpdate(steps, null);

        steps.add(Map.of("content", "b", "status", "pending"));

        assertEquals(1, event.steps().size());
        assertTrue(new AiEvent.PlanUpdate(null, null).steps().isEmpty(),
                "null steps must collapse to an empty list");
    }
}
