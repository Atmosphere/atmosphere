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

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.AgentExtension;
import org.atmosphere.a2a.types.AgentInterface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aRegistryTest {

    static class TestAgent {
        @AgentSkill(id = "s1", name = "Skill One", description = "First skill", tags = {"tag1", "tag2"})
        @AgentSkillHandler
        public void skill1(TaskContext task, @AgentSkillParam(name = "input") String input) {
        }

        @AgentSkill(id = "s2", name = "Skill Two", description = "Second skill")
        @AgentSkillHandler
        public void skill2(TaskContext task) {
        }

        @AgentSkill(id = "s3", name = "Not a skill", description = "No handler")
        public void notASkill() {
        }
    }

    @Test
    void scanFindsAnnotatedSkills() {
        var registry = new A2aRegistry();
        registry.scan(new TestAgent());

        assertEquals(2, registry.skills().size());
        assertTrue(registry.skill("s1").isPresent());
        assertTrue(registry.skill("s2").isPresent());
        assertFalse(registry.skill("s3").isPresent());
    }

    @Test
    void buildAgentCardLegacyUrlOverloadProducesJsonRpcInterface() {
        var registry = new A2aRegistry();
        registry.scan(new TestAgent());
        var card = registry.buildAgentCard("test", "Test Agent", "1.0", "/a2a");

        assertEquals("test", card.name());
        assertEquals("1.0", card.version());
        assertEquals(2, card.skills().size());
        assertEquals(1, card.supportedInterfaces().size());
        var iface = card.supportedInterfaces().getFirst();
        assertEquals("/a2a", iface.url());
        assertEquals(AgentInterface.JSONRPC, iface.protocolBinding());
    }

    @Test
    void buildAgentCardPropagatesGuardrailsAsExtension() {
        var registry = new A2aRegistry();
        var card = registry.buildAgentCard("test", "Test", "1.0", "/a2a",
                List.of("no-pii", "no-medical"));

        assertNotNull(card.capabilities().extensions());
        assertEquals(1, card.capabilities().extensions().size());
        var ext = card.capabilities().extensions().getFirst();
        assertEquals(AgentExtension.GUARDRAILS_URI, ext.uri());
        @SuppressWarnings("unchecked")
        var guardrails = (List<String>) ext.params().get("guardrails");
        assertEquals(2, guardrails.size());
    }

    @Test
    void buildAgentCardWithMultipleInterfaces() {
        var registry = new A2aRegistry();
        var card = registry.buildAgentCard("test", "Test", "1.0",
                List.of(
                        new AgentInterface("/a2a", AgentInterface.JSONRPC, "1.0"),
                        new AgentInterface("/a2a/rest", AgentInterface.HTTP_JSON, "1.0")),
                null);
        assertEquals(2, card.supportedInterfaces().size());
    }

    @Test
    void buildAgentCardCapabilitiesAdvertiseStreamingAndExtendedCard() {
        var registry = new A2aRegistry();
        var card = registry.buildAgentCard("test", "Test", "1.0", "/a2a");
        assertEquals(true, card.capabilities().streaming());
        assertEquals(true, card.capabilities().extendedAgentCard());
        assertEquals(false, card.capabilities().pushNotifications());
    }
}
