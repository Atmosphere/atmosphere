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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aTypesRecordTest {

    @Test
    void skillNullCollectionsDefaultToEmpty() {
        var skill = new Skill("s1", "Search", "desc", null, null, null);
        assertNotNull(skill.tags());
        assertTrue(skill.tags().isEmpty());
        assertTrue(skill.inputSchema().isEmpty());
        assertTrue(skill.outputSchema().isEmpty());
    }

    @Test
    void skillDefensivelyCopiesCollections() {
        var tags = new ArrayList<>(List.of("search"));
        var skill = new Skill("s1", "Search", "desc", tags, null, null);
        tags.add("modified");
        assertEquals(1, skill.tags().size());
    }

    @Test
    void skillImmutableCollections() {
        var skill = new Skill("s1", "Name", "desc", List.of("a"), Map.of(), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> skill.tags().add("b"));
    }

    @Test
    void skillRecordAccessors() {
        var skill = new Skill("id1", "MySkill", "A skill", List.of("tag1"), Map.of("type", "string"), Map.of());
        assertEquals("id1", skill.id());
        assertEquals("MySkill", skill.name());
        assertEquals("A skill", skill.description());
        assertEquals(List.of("tag1"), skill.tags());
    }

    @Test
    void taskNullCollectionsDefaultToEmpty() {
        var task = new Task("t1", "ctx1", new Task.TaskStatus(TaskState.WORKING, null),
                null, null, null);
        assertTrue(task.messages().isEmpty());
        assertTrue(task.artifacts().isEmpty());
        assertTrue(task.metadata().isEmpty());
    }

    @Test
    void taskDefensivelyCopiesMessages() {
        var msgs = new ArrayList<>(List.of(Message.user("hello")));
        var task = new Task("t1", "ctx1", new Task.TaskStatus(TaskState.COMPLETED, "done"),
                msgs, null, null);
        msgs.add(Message.agent("world"));
        assertEquals(1, task.messages().size());
    }

    @Test
    void taskStatusHoldsStateAndMessage() {
        var status = new Task.TaskStatus(TaskState.FAILED, "error occurred");
        assertEquals(TaskState.FAILED, status.state());
        assertEquals("error occurred", status.message());
    }

    @Test
    void taskRecordAccessors() {
        var task = new Task("t1", "ctx1", new Task.TaskStatus(TaskState.INPUT_REQUIRED, null),
                List.of(), List.of(), Map.of("key", "val"));
        assertEquals("t1", task.id());
        assertEquals("ctx1", task.contextId());
        assertEquals(TaskState.INPUT_REQUIRED, task.status().state());
        assertNull(task.status().message());
    }

    @Test
    void agentCardDefaultInputOutputModes() {
        var card = new AgentCard("agent", "desc", "http://localhost", "1.0",
                null, null, null, null, null, null, null, null);
        assertEquals(List.of("text"), card.defaultInputModes());
        assertEquals(List.of("text"), card.defaultOutputModes());
    }

    @Test
    void agentCardDefensivelyCopiesSkills() {
        var skills = new ArrayList<>(List.of(
                new Skill("s1", "Skill1", "desc", null, null, null)));
        var card = new AgentCard("agent", "desc", "http://localhost", "1.0",
                null, null, null, skills, null, null, null, null);
        skills.add(new Skill("s2", "Skill2", "desc", null, null, null));
        assertEquals(1, card.skills().size());
    }

    @Test
    void agentCardCapabilities() {
        var caps = new AgentCard.AgentCapabilities(true, false, true);
        assertTrue(caps.streaming());
        assertFalse(caps.pushNotifications());
        assertTrue(caps.stateTransitionHistory());
    }

    @Test
    void agentCardSecuritySchemesDefaults() {
        var card = new AgentCard("agent", "desc", "http://localhost", "1.0",
                null, null, null, null, null, null, null, null);
        assertTrue(card.securitySchemes().isEmpty());
    }

    @Test
    void agentCardCustomModes() {
        var card = new AgentCard("agent", "desc", "http://localhost", "1.0",
                null, null, null, null, null,
                List.of("text", "image"), List.of("text", "audio"), null);
        assertEquals(2, card.defaultInputModes().size());
        assertEquals(2, card.defaultOutputModes().size());
    }

    @Test
    void agentCardRecordAccessors() {
        var card = new AgentCard("MyAgent", "A helpful agent", "http://example.com", "2.0",
                "Acme Corp", "http://docs.example.com", null, null, null, null, null, null);
        assertEquals("MyAgent", card.name());
        assertEquals("A helpful agent", card.description());
        assertEquals("http://example.com", card.url());
        assertEquals("2.0", card.version());
        assertEquals("Acme Corp", card.provider());
        assertEquals("http://docs.example.com", card.documentationUrl());
    }

    @Test
    void taskMetadataDefensivelyCopied() {
        var meta = new HashMap<>(Map.of("key", (Object) "value"));
        var task = new Task("t1", "ctx", new Task.TaskStatus(TaskState.WORKING, null),
                null, null, meta);
        meta.put("extra", "val");
        assertEquals(1, task.metadata().size());
    }

    @Test
    void agentCardGuardrailsNullRemainsNull() {
        var card = new AgentCard("agent", "desc", "url", "1.0",
                null, null, null, null, null, null, null, null);
        assertNull(card.guardrails());
    }

    @Test
    void agentCardGuardrailsCopied() {
        var guardrails = new ArrayList<>(List.of("safety", "ethics"));
        var card = new AgentCard("agent", "desc", "url", "1.0",
                null, null, null, null, null, null, null, guardrails);
        guardrails.add("hacked");
        assertEquals(2, card.guardrails().size());
    }
}
