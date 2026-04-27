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

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCardTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AgentCard sample() {
        return new AgentCard(
                "demo",
                "Demo agent",
                List.of(new AgentInterface(
                        "https://example.com/a2a", AgentInterface.JSONRPC, "1.0")),
                new AgentProvider("https://example.com", "Example"),
                "1.0.0",
                "https://docs.example.com",
                new AgentCapabilities(true, false, null, true),
                null,
                null,
                List.of("text"),
                List.of("text"),
                List.of(new AgentSkill("s1", "Skill", "A skill", List.of("tag"))),
                null,
                "https://example.com/icon.png");
    }

    @Test
    void supportedInterfacesReplacesUrl() throws Exception {
        var card = sample();
        var json = mapper.writeValueAsString(card);
        assertTrue(json.contains("\"supportedInterfaces\""), json);
        assertTrue(json.contains("\"protocolBinding\":\"JSONRPC\""), json);
    }

    @Test
    void providerIsStructuredRecord() {
        var card = sample();
        assertEquals("Example", card.provider().organization());
        assertEquals("https://example.com", card.provider().url());
    }

    @Test
    void iconUrlIsEmittedWhenNonNull() throws Exception {
        var json = mapper.writeValueAsString(sample());
        assertTrue(json.contains("\"iconUrl\":\"https://example.com/icon.png\""), json);
    }

    @Test
    void capabilitiesExtendedAgentCardField() {
        var card = sample();
        assertEquals(true, card.capabilities().extendedAgentCard());
    }

    @Test
    void skillsAccessor() {
        var card = sample();
        assertNotNull(card.skills());
        assertEquals(1, card.skills().size());
    }

    @Test
    void agentCapabilitiesExtensions() throws Exception {
        var ext = new AgentExtension("https://x/extensions/test/v1", "test", false, java.util.Map.of("k", "v"));
        var caps = new AgentCapabilities(true, false, List.of(ext), true);
        var json = mapper.writeValueAsString(caps);
        assertTrue(json.contains("\"extensions\""), json);
        assertTrue(json.contains("https://x/extensions/test/v1"), json);
    }

    @Test
    void serializationRoundTripPreservesShape() throws Exception {
        var card = sample();
        var json = mapper.writeValueAsString(card);
        var roundTrip = mapper.readValue(json, AgentCard.class);
        assertEquals(card.name(), roundTrip.name());
        assertEquals(card.supportedInterfaces().size(), roundTrip.supportedInterfaces().size());
        assertEquals(card.provider().organization(), roundTrip.provider().organization());
    }
}
