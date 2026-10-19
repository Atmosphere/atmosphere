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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.governance.scope.ScopeGuardrailResolver;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.governance.scope.ScopePolicyInstaller;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In demo mode each room answers with its own persona. The demo strategy sees
 * the system prompt the runtime receives: the framework's scope preamble
 * (purpose, forbidden topics, redirect) followed by the room's persona. Picking
 * the persona by a keyword anywhere in that text answered the science room as
 * the Code Mentor, because its preamble forbids "source code".
 */
class DemoResponseProducerTest {

    private final RoomScopesConfig.Rooms rooms = new RoomScopesConfig().rooms();

    /** The system prompt a room's request reaches the runtime with. */
    private String hardenedSystemPrompt(String room) {
        var selected = rooms.byKey().get(room);
        var policy = new ScopePolicy(room + "-room", "yaml:classroom-test", "1",
                selected.scope(), ScopeGuardrailResolver.resolve(selected.scope().tier()));
        return ScopePolicyInstaller.hardenSystemPrompt(selected.systemPrompt(), List.of(policy));
    }

    private static AgentExecutionContext context(String systemPrompt) {
        return new AgentExecutionContext("Tell me something interesting.", systemPrompt, "demo",
                null, null, null, null, List.of(), null, null, List.of(), null, List.of(),
                null, null, List.of(), List.of(), null, null);
    }

    private static final Map<String, String> PERSONAS = Map.of(
            "math", "**Math Tutor**",
            "code", "**Code Mentor**",
            "science", "**Science Educator**",
            "general", "**Classroom AI**");

    @Test
    void eachRoomAnswersWithItsOwnPersona() {
        PERSONAS.forEach((room, persona) -> {
            var reply = DemoResponseProducer.generateFor(context(hardenedSystemPrompt(room)));
            assertTrue(reply.startsWith(persona), room + " room answered: " + reply);
        });
    }

    @Test
    void eachRoomAnswersWithItsOwnPersonaWithoutAScopePreamble() {
        PERSONAS.forEach((room, persona) -> {
            var reply = DemoResponseProducer.generateFor(context(rooms.byKey().get(room).systemPrompt()));
            assertTrue(reply.startsWith(persona), room + " room answered: " + reply);
        });
    }
}
