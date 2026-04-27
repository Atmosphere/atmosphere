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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Smoke coverage of the v1.0.0 type surface — defensive copies, factories, immutability. */
class A2aTypesRecordTest {

    @Test
    void agentSkillBasicCtor() {
        var skill = new AgentSkill("s1", "Search", "desc", List.of("tag"));
        assertEquals("s1", skill.id());
        assertEquals(1, skill.tags().size());
        assertNull(skill.examples());
    }

    @Test
    void agentSkillDefensiveTagsCopy() {
        var src = new ArrayList<>(List.of("a", "b"));
        var skill = new AgentSkill("s1", "Search", "desc", src);
        src.clear();
        assertEquals(2, skill.tags().size());
    }

    @Test
    void agentSkillNullTagsBecomesEmpty() {
        var skill = new AgentSkill("s1", "X", "Y", null);
        assertEquals(0, skill.tags().size());
    }

    @Test
    void taskStatusOfStoresState() {
        var s = TaskStatus.of(TaskState.WORKING, "go");
        assertEquals(TaskState.WORKING, s.state());
        assertNotNull(s.timestamp());
    }

    @Test
    void taskStatusMessageNullable() {
        var s = TaskStatus.of(TaskState.COMPLETED);
        assertNull(s.message());
    }

    @Test
    void taskRecordHistoryDefensiveCopy() {
        var history = new ArrayList<>(List.of(Message.user("hi")));
        var task = new Task("t1", "ctx1", TaskStatus.of(TaskState.WORKING),
                List.of(), history, null);
        history.clear();
        assertEquals(1, task.history().size());
    }

    @Test
    void agentCardDefaultModesFallback() {
        var card = new AgentCard(
                "x", "y", List.of(),
                null, "1.0", null,
                new AgentCapabilities(true, false, null, false),
                null, null, null, null, List.of(), null, null);
        assertEquals(List.of("text"), card.defaultInputModes());
        assertEquals(List.of("text"), card.defaultOutputModes());
    }

    @Test
    void agentCardSkillsImmutable() {
        var card = new AgentCard(
                "x", "y", List.of(),
                null, "1.0", null,
                new AgentCapabilities(true, false, null, false),
                null, null, null, null,
                List.of(new AgentSkill("s1", "S", "d", null)),
                null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> card.skills().add(new AgentSkill("s2", "S", "d", null)));
    }

    @Test
    void agentExtensionParamsDefensiveCopy() {
        var src = new java.util.HashMap<String, Object>(Map.of("k", "v"));
        var ext = new AgentExtension("https://x/ext", "desc", true, src);
        src.clear();
        assertEquals(1, ext.params().size());
    }

    @Test
    void securityRequirementSchemesImmutable() {
        var req = new SecurityRequirement(Map.of("oauth", List.of("read")));
        assertThrows(UnsupportedOperationException.class,
                () -> req.schemes().put("apiKey", List.of()));
    }
}
