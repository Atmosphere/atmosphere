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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link ConversationPersistence} interface — its default methods
 * and the full load/save/remove contract via a minimal in-memory implementation.
 */
class ConversationPersistenceTest {

    /** Minimal in-memory ConversationPersistence for contract testing. */
    static class MapPersistence implements ConversationPersistence {
        final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        @Override
        public Optional<String> load(String conversationId) {
            return Optional.ofNullable(store.get(conversationId));
        }

        @Override
        public void save(String conversationId, String data) {
            store.put(conversationId, data);
        }

        @Override
        public void remove(String conversationId) {
            store.remove(conversationId);
        }
    }

    @Test
    void isAvailableReturnsTrueByDefault() {
        var persistence = new MapPersistence();
        assertTrue(persistence.isAvailable());
    }

    @Test
    void saveAndLoadRoundTrip() {
        var persistence = new MapPersistence();
        persistence.save("conv-1", "{\"messages\":[]}");

        var loaded = persistence.load("conv-1");
        assertTrue(loaded.isPresent());
        assertEquals("{\"messages\":[]}", loaded.get());
    }

    @Test
    void loadReturnsEmptyForMissingConversation() {
        var persistence = new MapPersistence();
        assertTrue(persistence.load("nonexistent").isEmpty());
    }

    @Test
    void saveOverwritesPreviousData() {
        var persistence = new MapPersistence();
        persistence.save("conv-1", "old-data");
        persistence.save("conv-1", "new-data");

        assertEquals("new-data", persistence.load("conv-1").orElseThrow());
    }

    @Test
    void removeDeletesData() {
        var persistence = new MapPersistence();
        persistence.save("conv-1", "data");
        persistence.remove("conv-1");

        assertTrue(persistence.load("conv-1").isEmpty());
    }

    @Test
    void removeOnMissingConversationDoesNotThrow() {
        var persistence = new MapPersistence();
        persistence.remove("nonexistent");
        // No exception expected
    }

    @Test
    void conversationsAreIsolated() {
        var persistence = new MapPersistence();
        persistence.save("conv-1", "data-1");
        persistence.save("conv-2", "data-2");

        assertEquals("data-1", persistence.load("conv-1").orElseThrow());
        assertEquals("data-2", persistence.load("conv-2").orElseThrow());
    }

    @Test
    void removeDoesNotAffectOtherConversations() {
        var persistence = new MapPersistence();
        persistence.save("conv-1", "data-1");
        persistence.save("conv-2", "data-2");

        persistence.remove("conv-1");

        assertTrue(persistence.load("conv-1").isEmpty());
        assertEquals("data-2", persistence.load("conv-2").orElseThrow());
    }

    @Test
    void customIsAvailableCanReturnFalse() {
        ConversationPersistence unavailable = new ConversationPersistence() {
            @Override
            public Optional<String> load(String conversationId) {
                return Optional.empty();
            }

            @Override
            public void save(String conversationId, String data) { }

            @Override
            public void remove(String conversationId) { }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
        assertTrue(!unavailable.isAvailable());
    }

    @Test
    void saveWithEmptyStringPreservesData() {
        var persistence = new MapPersistence();
        persistence.save("conv-1", "");

        var loaded = persistence.load("conv-1");
        assertTrue(loaded.isPresent());
        assertEquals("", loaded.get());
    }

    @Test
    void saveWithLargePayload() {
        var persistence = new MapPersistence();
        var largeData = "x".repeat(100_000);
        persistence.save("conv-1", largeData);

        assertEquals(largeData, persistence.load("conv-1").orElseThrow());
    }

    @Test
    void multipleConversationsIndependent() {
        var persistence = new MapPersistence();
        for (int i = 0; i < 10; i++) {
            persistence.save("conv-" + i, "data-" + i);
        }
        for (int i = 0; i < 10; i++) {
            assertEquals("data-" + i, persistence.load("conv-" + i).orElseThrow());
        }
    }
}
