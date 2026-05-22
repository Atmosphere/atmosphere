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
package org.atmosphere.session.sqlite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteLongTermMemoryTest {

    private SqliteLongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = SqliteLongTermMemory.inMemory();
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    @Test
    void saveAndRetrieveFacts() {
        memory.saveFact("user-1", "Has a dog named Max");
        memory.saveFact("user-1", "Lives in Montreal");

        var facts = memory.getFacts("user-1", 10);
        assertEquals(2, facts.size());
        assertTrue(facts.contains("Has a dog named Max"));
        assertTrue(facts.contains("Lives in Montreal"));
    }

    @Test
    void getFactsReturnsEmptyForUnknownUser() {
        assertEquals(List.of(), memory.getFacts("unknown", 10));
    }

    @Test
    void maxFactsEvictsOldest() {
        var capped = SqliteLongTermMemory.inMemory(3);
        try {
            capped.saveFact("u1", "fact-1");
            capped.saveFact("u1", "fact-2");
            capped.saveFact("u1", "fact-3");
            capped.saveFact("u1", "fact-4");

            var facts = capped.getFacts("u1", 10);
            assertEquals(3, facts.size());
            assertFalse(facts.contains("fact-1"));
            assertTrue(facts.contains("fact-4"));
        } finally {
            capped.close();
        }
    }

    @Test
    void getFactsLimitsResults() {
        memory.saveFact("u1", "a");
        memory.saveFact("u1", "b");
        memory.saveFact("u1", "c");

        var facts = memory.getFacts("u1", 2);
        assertEquals(2, facts.size());
        assertTrue(facts.contains("b"));
        assertTrue(facts.contains("c"));
    }

    @Test
    void clearRemovesAllFacts() {
        memory.saveFact("u1", "fact");
        memory.clear("u1");
        assertEquals(List.of(), memory.getFacts("u1", 10));
    }

    @Test
    void saveBatchFacts() {
        memory.saveFacts("u1", List.of("a", "b", "c"));
        assertEquals(3, memory.getFacts("u1", 10).size());
    }

    @Test
    void usersAreIsolated() {
        memory.saveFact("u1", "fact-u1");
        memory.saveFact("u2", "fact-u2");

        assertEquals(1, memory.getFacts("u1", 10).size());
        assertEquals("fact-u1", memory.getFacts("u1", 10).get(0));
        assertEquals("fact-u2", memory.getFacts("u2", 10).get(0));
    }

    @Test
    void factsSurviveAcrossClose() throws Exception {
        // File-backed memory persists.
        var tmp = java.nio.file.Files.createTempFile("atmosphere-facts-", ".db");
        try {
            var first = new SqliteLongTermMemory(tmp, 100);
            first.saveFact("u1", "persisted");
            first.close();

            var second = new SqliteLongTermMemory(tmp, 100);
            try {
                var facts = second.getFacts("u1", 10);
                assertEquals(1, facts.size());
                assertEquals("persisted", facts.get(0));
            } finally {
                second.close();
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }
}
