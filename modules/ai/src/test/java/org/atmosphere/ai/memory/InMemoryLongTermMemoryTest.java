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
package org.atmosphere.ai.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryLongTermMemoryTest {

    @Test
    void saveAndRetrieveFacts() {
        var mem = new InMemoryLongTermMemory();
        mem.saveFact("user-1", "Has a dog named Max");
        mem.saveFact("user-1", "Lives in Montreal");

        var facts = mem.getFacts("user-1", 10);
        assertEquals(2, facts.size());
        assertTrue(facts.contains("Has a dog named Max"));
        assertTrue(facts.contains("Lives in Montreal"));
    }

    @Test
    void getFactsReturnsEmptyForUnknownUser() {
        var mem = new InMemoryLongTermMemory();
        assertEquals(List.of(), mem.getFacts("unknown", 10));
    }

    @Test
    void maxFactsEvictsOldest() {
        var mem = new InMemoryLongTermMemory(3);
        mem.saveFact("u1", "fact-1");
        mem.saveFact("u1", "fact-2");
        mem.saveFact("u1", "fact-3");
        mem.saveFact("u1", "fact-4");

        var facts = mem.getFacts("u1", 10);
        assertEquals(3, facts.size());
        assertFalse(facts.contains("fact-1"));
        assertTrue(facts.contains("fact-4"));
    }

    @Test
    void getFactsLimitsResults() {
        var mem = new InMemoryLongTermMemory();
        mem.saveFact("u1", "a");
        mem.saveFact("u1", "b");
        mem.saveFact("u1", "c");

        var facts = mem.getFacts("u1", 2);
        assertEquals(2, facts.size());
        // Most recent facts returned
        assertTrue(facts.contains("b"));
        assertTrue(facts.contains("c"));
    }

    @Test
    void clearRemovesAllFacts() {
        var mem = new InMemoryLongTermMemory();
        mem.saveFact("u1", "fact");
        mem.clear("u1");
        assertEquals(List.of(), mem.getFacts("u1", 10));
    }

    @Test
    void saveBatchFacts() {
        var mem = new InMemoryLongTermMemory();
        mem.saveFacts("u1", List.of("a", "b", "c"));
        assertEquals(3, mem.getFacts("u1", 10).size());
    }

    @Test
    void usersAreIsolated() {
        var mem = new InMemoryLongTermMemory();
        mem.saveFact("u1", "fact-u1");
        mem.saveFact("u2", "fact-u2");

        assertEquals(1, mem.getFacts("u1", 10).size());
        assertEquals("fact-u1", mem.getFacts("u1", 10).get(0));
        assertEquals("fact-u2", mem.getFacts("u2", 10).get(0));
    }
}
