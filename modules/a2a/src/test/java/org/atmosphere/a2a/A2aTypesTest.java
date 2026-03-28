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

import org.atmosphere.a2a.registry.A2aRegistry;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.Part;
import org.atmosphere.a2a.types.Skill;
import org.atmosphere.a2a.types.Task;
import org.atmosphere.a2a.types.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class A2aTypesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void messageFactories() {
        var user = Message.user("hello");
        assertEquals("user", user.role());
        assertEquals(1, user.parts().size());
        assertInstanceOf(Part.TextPart.class, user.parts().getFirst());

        var agent = Message.agent("hi");
        assertEquals("agent", agent.role());
    }

    @Test
    void artifactText() {
        var artifact = Artifact.text("result");
        assertNotNull(artifact.artifactId());
        assertEquals(1, artifact.parts().size());
    }

    @Test
    void taskStateSerialization() throws Exception {
        var status = new Task.TaskStatus(TaskState.WORKING, "Processing");
        var json = mapper.writeValueAsString(status);
        assertTrue(json.contains("WORKING"));
        assertTrue(json.contains("Processing"));
    }

    @Test
    void agentCardSerialization() throws Exception {
        var skill = new Skill("s1", "Skill One", "desc", List.of("tag1"), Map.of(), Map.of());
        var caps = new AgentCard.AgentCapabilities(true, false, true);
        var card = new AgentCard("test", "desc", "http://localhost/a2a", "1.0", null, null,
                caps, List.of(skill), Map.of(), List.of("text"), List.of("text"), null);
        var json = mapper.writeValueAsString(card);
        assertTrue(json.contains("\"name\":\"test\""));
        assertTrue(json.contains("\"streaming\":true"));
    }

    @Test
    void partSealedInterface() {
        Part text = new Part.TextPart("hello");
        Part file = new Part.FilePart("doc.txt", "text/plain", "file:///doc.txt");
        Part data = new Part.DataPart(Map.of("key", "value"), Map.of());

        switch (text) {
            case Part.TextPart t -> assertEquals("hello", t.text());
            case Part.FilePart f -> fail("Expected TextPart");
            case Part.DataPart d -> fail("Expected TextPart");
        }
    }

    @Test
    void taskStates() {
        assertEquals(7, TaskState.values().length);
        assertNotNull(TaskState.valueOf("INPUT_REQUIRED"));
    }

    // ── Guardrails Metadata ─────────────────────────────────────────────

    @Test
    void agentCardWithGuardrailsSerializesToJson() throws Exception {
        var caps = new AgentCard.AgentCapabilities(true, false, false);
        var card = new AgentCard("guarded", "A guarded agent", "http://localhost/a2a",
                "1.0", null, null, caps, List.of(), Map.of(),
                List.of("text"), List.of("text"),
                List.of("No medical advice", "No financial advice"));

        var json = mapper.writeValueAsString(card);
        assertTrue(json.contains("\"guardrails\""), "JSON should contain guardrails field");
        assertTrue(json.contains("No medical advice"));
        assertTrue(json.contains("No financial advice"));

        // Verify round-trip deserialization
        var node = mapper.readTree(json);
        var guardrails = node.get("guardrails");
        assertNotNull(guardrails, "guardrails field should be present");
        assertTrue(guardrails.isArray());
        assertEquals(2, guardrails.size());
        assertEquals("No medical advice", guardrails.get(0).stringValue());
        assertEquals("No financial advice", guardrails.get(1).stringValue());
    }

    @Test
    void agentCardWithNullGuardrailsOmitsField() throws Exception {
        var caps = new AgentCard.AgentCapabilities(true, false, false);
        var card = new AgentCard("unguarded", "No guardrails", "http://localhost/a2a",
                "1.0", null, null, caps, List.of(), Map.of(),
                List.of("text"), List.of("text"), null);

        var json = mapper.writeValueAsString(card);
        assertFalse(json.contains("\"guardrails\""),
                "JSON should NOT contain guardrails field when null");
        assertNull(card.guardrails());
    }

    @Test
    void registryBuildAgentCardFiveParamIncludesGuardrails() {
        var registry = new A2aRegistry();
        var guardrails = List.of("PII redaction", "Content filtering");
        var card = registry.buildAgentCard("test", "Test Agent", "1.0", "/a2a", guardrails);

        assertNotNull(card.guardrails());
        assertEquals(2, card.guardrails().size());
        assertEquals("PII redaction", card.guardrails().get(0));
        assertEquals("Content filtering", card.guardrails().get(1));
    }

    @Test
    void registryBuildAgentCardFourParamHasNullGuardrails() {
        var registry = new A2aRegistry();
        var card = registry.buildAgentCard("test", "Test Agent", "1.0", "/a2a");

        assertNull(card.guardrails(),
                "4-param buildAgentCard should produce null guardrails");
    }
}
