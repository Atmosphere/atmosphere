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

import org.atmosphere.a2a.annotation.A2aParam;
import org.atmosphere.a2a.annotation.A2aSkill;
import org.atmosphere.a2a.annotation.A2aTaskHandler;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.runtime.TaskContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aRegistryTest {

    static class TestAgent {
        @A2aSkill(id = "s1", name = "Skill One", description = "First skill", tags = {"tag1", "tag2"})
        @A2aTaskHandler
        public void skill1(TaskContext task, @A2aParam(name = "input") String input) {
        }

        @A2aSkill(id = "s2", name = "Skill Two", description = "Second skill")
        @A2aTaskHandler
        public void skill2(TaskContext task) {
        }

        // Not a skill - no @A2aTaskHandler
        @A2aSkill(id = "s3", name = "Not a skill", description = "No handler")
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
        assertFalse(registry.skill("s3").isPresent()); // missing @A2aTaskHandler
    }

    @Test
    void skillEntryHasCorrectMetadata() {
        var registry = new A2aRegistry();
        registry.scan(new TestAgent());

        var s1 = registry.skill("s1").orElseThrow();
        assertEquals("Skill One", s1.name());
        assertEquals("First skill", s1.description());
        assertEquals(2, s1.tags().size());
        assertEquals(1, s1.params().size()); // TaskContext is excluded
        assertEquals("input", s1.params().getFirst().name());
    }

    @Test
    void buildAgentCard() {
        var registry = new A2aRegistry();
        registry.scan(new TestAgent());

        var card = registry.buildAgentCard("test", "Test Agent", "1.0", "/a2a");
        assertEquals("test", card.name());
        assertEquals("1.0", card.version());
        assertEquals(2, card.skills().size());
        assertTrue(card.capabilities().streaming());
    }
}
