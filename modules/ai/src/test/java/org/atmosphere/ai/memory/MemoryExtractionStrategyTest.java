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

class MemoryExtractionStrategyTest {

    @Test
    void onSessionCloseNeverTriggersPerMessage() {
        var strategy = MemoryExtractionStrategy.onSessionClose();
        assertFalse(strategy.shouldExtract("conv1", "hello", 1));
        assertFalse(strategy.shouldExtract("conv1", "world", 100));
    }

    @Test
    void perMessageAlwaysTriggers() {
        var strategy = MemoryExtractionStrategy.perMessage();
        assertTrue(strategy.shouldExtract("conv1", "hello", 1));
        assertTrue(strategy.shouldExtract("conv1", "world", 100));
    }

    @Test
    void periodicTriggersAtInterval() {
        var strategy = MemoryExtractionStrategy.periodic(5);
        assertFalse(strategy.shouldExtract("c1", "msg", 1));
        assertFalse(strategy.shouldExtract("c1", "msg", 4));
        assertTrue(strategy.shouldExtract("c1", "msg", 5));
        assertFalse(strategy.shouldExtract("c1", "msg", 6));
        assertTrue(strategy.shouldExtract("c1", "msg", 10));
    }

    @Test
    void periodicDoesNotTriggerAtZero() {
        var strategy = MemoryExtractionStrategy.periodic(5);
        assertFalse(strategy.shouldExtract("c1", "msg", 0));
    }

    @Test
    void periodicRejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> MemoryExtractionStrategy.periodic(0));
        assertThrows(IllegalArgumentException.class, () -> MemoryExtractionStrategy.periodic(-1));
    }

    @Test
    void parseJsonArrayBasic() {
        var result = OnSessionCloseStrategy.parseJsonArray(
                "[\"User likes coffee\", \"Lives in Montreal\"]");
        assertEquals(List.of("User likes coffee", "Lives in Montreal"), result);
    }

    @Test
    void parseJsonArrayEmpty() {
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray("[]"));
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray(""));
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray(null));
    }

    @Test
    void parseJsonArrayWithMarkdownCodeBlock() {
        var result = OnSessionCloseStrategy.parseJsonArray(
                "```json\n[\"fact one\", \"fact two\"]\n```");
        assertEquals(List.of("fact one", "fact two"), result);
    }

    @Test
    void parseJsonArrayInvalidJsonReturnsEmpty() {
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray("not json"));
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray("{\"key\": \"value\"}"));
    }
}
