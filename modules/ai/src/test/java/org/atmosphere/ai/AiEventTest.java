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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiEventTest {

    @Test
    void textDeltaEventType() {
        var event = new AiEvent.TextDelta("hello");
        assertEquals("text-delta", event.eventType());
        assertEquals("hello", event.text());
    }

    @Test
    void textCompleteEventType() {
        var event = new AiEvent.TextComplete("full text");
        assertEquals("text-complete", event.eventType());
        assertEquals("full text", event.fullText());
    }

    @Test
    void toolStartEventType() {
        var event = new AiEvent.ToolStart("weather", Map.of("city", "Montreal"));
        assertEquals("tool-start", event.eventType());
        assertEquals("weather", event.toolName());
        assertEquals("Montreal", event.arguments().get("city"));
    }

    @Test
    void toolStartDefensiveCopiesArguments() {
        var event = new AiEvent.ToolStart("t", Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.arguments().put("x", "y"));
    }

    @Test
    void toolStartHandlesNullArguments() {
        var event = new AiEvent.ToolStart("t", null);
        assertNotNull(event.arguments());
        assertTrue(event.arguments().isEmpty());
    }

    @Test
    void toolResultEventType() {
        var event = new AiEvent.ToolResult("weather", "22°C");
        assertEquals("tool-result", event.eventType());
        assertEquals("weather", event.toolName());
        assertEquals("22°C", event.result());
    }

    @Test
    void toolErrorEventType() {
        var event = new AiEvent.ToolError("weather", "timeout");
        assertEquals("tool-error", event.eventType());
    }

    @Test
    void agentStepEventType() {
        var event = new AiEvent.AgentStep("plan", "Planning next action", Map.of("k", "v"));
        assertEquals("agent-step", event.eventType());
        assertEquals("plan", event.stepName());
    }

    @Test
    void agentStepHandlesNullData() {
        var event = new AiEvent.AgentStep("s", "d", null);
        assertNotNull(event.data());
        assertTrue(event.data().isEmpty());
    }

    @Test
    void structuredFieldEventType() {
        var event = new AiEvent.StructuredField("name", "John", "string");
        assertEquals("structured-field", event.eventType());
    }

    @Test
    void entityStartEventType() {
        var event = new AiEvent.EntityStart("User", "{}");
        assertEquals("entity-start", event.eventType());
    }

    @Test
    void entityCompleteEventType() {
        var event = new AiEvent.EntityComplete("User", Map.of());
        assertEquals("entity-complete", event.eventType());
    }

    @Test
    void routingDecisionEventType() {
        var event = new AiEvent.RoutingDecision("gpt4", "claude", "rate limit");
        assertEquals("routing-decision", event.eventType());
    }

    @Test
    void progressEventType() {
        var event = new AiEvent.Progress("Processing...", 0.5);
        assertEquals("progress", event.eventType());
        assertEquals(0.5, event.percentage());
    }

    @Test
    void progressWithNullPercentage() {
        var event = new AiEvent.Progress("Working...", null);
        assertNull(event.percentage());
    }

    @Test
    void handoffEventType() {
        var event = new AiEvent.Handoff("agent-a", "agent-b", "billing");
        assertEquals("handoff", event.eventType());
    }

    @Test
    void errorEventType() {
        var event = new AiEvent.Error("Rate limit exceeded", "rate_limit", true);
        assertEquals("error", event.eventType());
        assertTrue(event.recoverable());
    }

    @Test
    void errorNonRecoverable() {
        var event = new AiEvent.Error("Fatal", "fatal", false);
        assertFalse(event.recoverable());
    }

    @Test
    void completeEventType() {
        var event = new AiEvent.Complete("summary", Map.of("tokens", 100));
        assertEquals("complete", event.eventType());
        assertEquals(100, event.usage().get("tokens"));
    }

    @Test
    void completeHandlesNullUsage() {
        var event = new AiEvent.Complete("done", null);
        assertNotNull(event.usage());
        assertTrue(event.usage().isEmpty());
    }

    @Test
    void approvalRequiredEventType() {
        var event = new AiEvent.ApprovalRequired("ap-1", "delete", Map.of(), "Confirm?", 30);
        assertEquals("approval-required", event.eventType());
        assertEquals("ap-1", event.approvalId());
        assertEquals(30, event.expiresIn());
    }

    @Test
    void approvalRequiredHandlesNullArguments() {
        var event = new AiEvent.ApprovalRequired("ap-1", "t", null, "ok?", 10);
        assertNotNull(event.arguments());
        assertTrue(event.arguments().isEmpty());
    }
}
