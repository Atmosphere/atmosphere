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
package org.atmosphere.a2a.types;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCardTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void basicConstruction() {
        var card = new AgentCard("TestAgent", "A test agent", "http://localhost:8080",
                "1.0", "TestCorp", "http://docs.test.com",
                new AgentCard.AgentCapabilities(true, false, true),
                List.of(new Skill("s1", "Skill1", "desc", null, null, null)),
                Map.of("bearer", "jwt"), List.of("text"), List.of("text"), List.of("safety"));
        assertEquals("TestAgent", card.name());
        assertEquals("A test agent", card.description());
        assertEquals("http://localhost:8080", card.url());
        assertEquals("1.0", card.version());
        assertEquals("TestCorp", card.provider());
        assertEquals("http://docs.test.com", card.documentationUrl());
        assertEquals(1, card.skills().size());
        assertEquals(1, card.securitySchemes().size());
        assertEquals(List.of("safety"), card.guardrails());
    }

    @Test
    void nullSkillsDefaultsToEmptyList() {
        var card = new AgentCard("a", "d", "u", "v", null, null, null,
                null, null, null, null, null);
        assertNotNull(card.skills());
        assertTrue(card.skills().isEmpty());
    }

    @Test
    void nullSecuritySchemesDefaultsToEmptyMap() {
        var card = new AgentCard("a", "d", "u", "v", null, null, null,
                null, null, null, null, null);
        assertNotNull(card.securitySchemes());
        assertTrue(card.securitySchemes().isEmpty());
    }

    @Test
    void nullDefaultInputModesDefaultsToText() {
        var card = new AgentCard("a", "d", "u", "v", null, null, null,
                null, null, null, null, null);
        assertEquals(List.of("text"), card.defaultInputModes());
    }

    @Test
    void nullDefaultOutputModesDefaultsToText() {
        var card = new AgentCard("a", "d", "u", "v", null, null, null,
                null, null, null, null, null);
        assertEquals(List.of("text"), card.defaultOutputModes());
    }

    @Test
    void nullGuardrailsStaysNull() {
        var card = new AgentCard("a", "d", "u", "v", null, null, null,
                null, null, null, null, null);
        assertNull(card.guardrails());
    }

    @Test
    void skillsListIsUnmodifiable() {
        var card = new AgentCard("a", "d", "u", "v", null, null, null,
                List.of(new Skill("s1", "S", "d", null, null, null)),
                null, null, null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> card.skills().add(new Skill("s2", "S2", "d2", null, null, null)));
    }

    @Test
    void skillsListDefensivelyCopied() {
        var skills = new ArrayList<>(List.of(new Skill("s1", "S", "d", null, null, null)));
        var card = new AgentCard("a", "d", "u", "v", null, null, null,
                skills, null, null, null, null);
        skills.add(new Skill("s2", "S2", "d2", null, null, null));
        assertEquals(1, card.skills().size());
    }

    @Test
    void agentCapabilitiesConstruction() {
        var caps = new AgentCard.AgentCapabilities(true, true, false);
        assertTrue(caps.streaming());
        assertTrue(caps.pushNotifications());
        assertFalse(caps.stateTransitionHistory());
    }

    @Test
    void agentCapabilitiesAllFalse() {
        var caps = new AgentCard.AgentCapabilities(false, false, false);
        assertFalse(caps.streaming());
        assertFalse(caps.pushNotifications());
        assertFalse(caps.stateTransitionHistory());
    }

    @Test
    void jsonSerializationRoundTrip() throws Exception {
        var card = new AgentCard("Agent", "desc", "http://example.com", "2.0",
                "Provider", "http://docs.example.com",
                new AgentCard.AgentCapabilities(true, false, true),
                List.of(new Skill("s1", "Skill", "A skill", List.of("tag"), null, null)),
                Map.of("oauth", "bearer"), List.of("text", "image"),
                List.of("text"), List.of("safety"));
        String json = mapper.writeValueAsString(card);
        var deserialized = mapper.readValue(json, AgentCard.class);
        assertEquals(card.name(), deserialized.name());
        assertEquals(card.version(), deserialized.version());
        assertEquals(card.skills().size(), deserialized.skills().size());
        assertEquals(card.defaultInputModes(), deserialized.defaultInputModes());
        assertEquals(card.guardrails(), deserialized.guardrails());
    }

    @Test
    void jsonOmitsNullFields() throws Exception {
        var card = new AgentCard("Agent", null, "http://example.com", null,
                null, null, null, null, null, null, null, null);
        String json = mapper.writeValueAsString(card);
        assertFalse(json.contains("\"provider\""));
        assertFalse(json.contains("\"documentationUrl\""));
        assertFalse(json.contains("\"guardrails\""));
    }
}
