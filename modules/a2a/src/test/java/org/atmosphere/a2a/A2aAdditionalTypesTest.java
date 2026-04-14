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
package org.atmosphere.a2a;

import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.Part;
import org.atmosphere.a2a.types.Task;
import org.atmosphere.a2a.types.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aAdditionalTypesTest {

    // --- TaskState enum ---

    @Test
    void taskStateContainsAllExpectedValues() {
        var values = TaskState.values();
        assertTrue(values.length >= 7);
        assertNotNull(TaskState.valueOf("WORKING"));
        assertNotNull(TaskState.valueOf("COMPLETED"));
        assertNotNull(TaskState.valueOf("FAILED"));
        assertNotNull(TaskState.valueOf("CANCELED"));
        assertNotNull(TaskState.valueOf("REJECTED"));
        assertNotNull(TaskState.valueOf("INPUT_REQUIRED"));
        assertNotNull(TaskState.valueOf("AUTH_REQUIRED"));
    }

    // --- AgentCard ---

    @Test
    void agentCardDefensiveCopies() {
        var card = new AgentCard("test", "desc", "http://localhost",
                "1.0", "provider", null, null,
                null, null, null, null, null);
        assertEquals(List.of(), card.skills());
        assertEquals(Map.of(), card.securitySchemes());
        assertEquals(List.of("text"), card.defaultInputModes());
        assertEquals(List.of("text"), card.defaultOutputModes());
        assertNull(card.guardrails());
    }

    @Test
    void agentCardRetainsFields() {
        var caps = new AgentCard.AgentCapabilities(true, false, true);
        var card = new AgentCard("myAgent", "A helper", "http://example.com",
                "2.0", "Acme", "http://docs.example.com", caps,
                List.of(), Map.of("bearer", Map.of()), List.of("text", "image"),
                List.of("text"), List.of("guardrail1"));
        assertEquals("myAgent", card.name());
        assertEquals("A helper", card.description());
        assertTrue(caps.streaming());
        assertFalse(caps.pushNotifications());
        assertTrue(caps.stateTransitionHistory());
        assertEquals(List.of("guardrail1"), card.guardrails());
    }

    // --- Task ---

    @Test
    void taskDefensiveCopies() {
        var status = new Task.TaskStatus(TaskState.WORKING, null);
        var task = new Task("t1", "ctx1", status, null, null, null);
        assertEquals(List.of(), task.messages());
        assertEquals(List.of(), task.artifacts());
        assertEquals(Map.of(), task.metadata());
    }

    @Test
    void taskRetainsFields() {
        var msg = Message.user("hello");
        var art = Artifact.text("result");
        var status = new Task.TaskStatus(TaskState.COMPLETED, "done");
        var task = new Task("t1", "ctx1", status,
                List.of(msg), List.of(art), Map.of("key", "value"));
        assertEquals("t1", task.id());
        assertEquals("ctx1", task.contextId());
        assertEquals(TaskState.COMPLETED, task.status().state());
        assertEquals("done", task.status().message());
        assertEquals(1, task.messages().size());
        assertEquals(1, task.artifacts().size());
        assertEquals("value", task.metadata().get("key"));
    }

    // --- Message factories ---

    @Test
    void messageUserFactory() {
        var msg = Message.user("prompt");
        assertEquals("user", msg.role());
        assertEquals(1, msg.parts().size());
        var part = msg.parts().getFirst();
        assertTrue(part instanceof Part.TextPart);
        assertEquals("prompt", ((Part.TextPart) part).text());
    }

    @Test
    void messageAgentFactory() {
        var msg = Message.agent("response");
        assertEquals("agent", msg.role());
        assertEquals(1, msg.parts().size());
    }

    // --- Artifact factories ---

    @Test
    void artifactTextFactory() {
        var art = Artifact.text("hello world");
        assertNotNull(art.artifactId());
        assertEquals(1, art.parts().size());
        var part = art.parts().getFirst();
        assertTrue(part instanceof Part.TextPart);
    }

    @Test
    void artifactNamedFactory() {
        var parts = List.<Part>of(new Part.TextPart("data"));
        var art = Artifact.named("doc", "a document", parts);
        assertEquals("doc", art.name());
        assertEquals("a document", art.description());
        assertEquals(1, art.parts().size());
    }
}
