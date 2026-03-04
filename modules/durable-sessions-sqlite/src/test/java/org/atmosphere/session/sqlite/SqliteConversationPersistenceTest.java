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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SqliteConversationPersistence} using in-memory SQLite.
 */
public class SqliteConversationPersistenceTest {

    private SqliteConversationPersistence persistence;

    @BeforeEach
    public void setUp() {
        persistence = SqliteConversationPersistence.inMemory();
    }

    @AfterEach
    public void tearDown() {
        persistence.close();
    }

    @Test
    public void testSaveAndLoad() {
        persistence.save("conv-1", "{\"messages\":[]}");

        var loaded = persistence.load("conv-1");
        assertTrue(loaded.isPresent());
        assertEquals("{\"messages\":[]}", loaded.get());
    }

    @Test
    public void testLoadNonExistent() {
        var loaded = persistence.load("nonexistent");
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void testSaveOverwrites() {
        persistence.save("conv-1", "v1");
        persistence.save("conv-1", "v2");

        var loaded = persistence.load("conv-1");
        assertTrue(loaded.isPresent());
        assertEquals("v2", loaded.get());
    }

    @Test
    public void testRemove() {
        persistence.save("conv-1", "data");
        persistence.remove("conv-1");

        assertTrue(persistence.load("conv-1").isEmpty());
    }

    @Test
    public void testRemoveNonExistentDoesNotThrow() {
        assertDoesNotThrow(() -> persistence.remove("nonexistent"));
    }

    @Test
    public void testConversationIsolation() {
        persistence.save("conv-1", "data-1");
        persistence.save("conv-2", "data-2");

        assertEquals("data-1", persistence.load("conv-1").orElse(null));
        assertEquals("data-2", persistence.load("conv-2").orElse(null));
    }

    @Test
    public void testLargePayload() {
        var largeData = "x".repeat(100_000);
        persistence.save("conv-large", largeData);

        var loaded = persistence.load("conv-large");
        assertTrue(loaded.isPresent());
        assertEquals(100_000, loaded.get().length());
    }

    @Test
    public void testSpecialCharacters() {
        var data = "{\"role\":\"user\",\"content\":\"Hello \\\"world\\\"\\nLine2\"}";
        persistence.save("conv-special", data);

        var loaded = persistence.load("conv-special");
        assertTrue(loaded.isPresent());
        assertEquals(data, loaded.get());
    }
}
