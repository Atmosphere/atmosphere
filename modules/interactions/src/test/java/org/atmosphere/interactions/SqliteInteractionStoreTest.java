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
package org.atmosphere.interactions;

import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durability and round-trip coverage for the SQLite-backed interaction store. */
class SqliteInteractionStoreTest {

    @TempDir
    Path tmp;

    private Interaction sample(String id) {
        var now = Instant.parse("2026-06-01T12:00:00.123456789Z");
        return new Interaction(id, null, "conv-1", "agent-x", "alice", "gpt-4",
                InteractionStatus.COMPLETED, true, true, List.of(),
                "the answer", new TokenUsage(10, 20, 0, 30, "gpt-4"), null, now, now);
    }

    @Test
    void persistsHeaderAndStepsAcrossReopen() {
        var dbPath = tmp.resolve("it.db");
        var first = new SqliteInteractionStore(dbPath);
        first.start();
        try {
            first.save(sample("int-1"));
            first.appendStep("int-1", new InteractionStep(0, "text", "the answer", null,
                    Map.of(), null, Instant.parse("2026-06-01T12:00:00Z")));
            first.appendStep("int-1", new InteractionStep(1, "tool-call", null, "weather",
                    Map.of("arguments", Map.of("city", "Montreal")), null,
                    Instant.parse("2026-06-01T12:00:01Z")));
            first.appendStep("int-1", new InteractionStep(2, "usage", null, null, Map.of(),
                    new TokenUsage(10, 20, 0, 30, "gpt-4"), Instant.parse("2026-06-01T12:00:02Z")));
        } finally {
            first.stop();
        }

        // Reopen the same file in a fresh store — durable across the "restart".
        var second = new SqliteInteractionStore(dbPath);
        second.start();
        try {
            var loaded = second.load("int-1").orElseThrow();
            assertEquals(InteractionStatus.COMPLETED, loaded.status());
            assertEquals("the answer", loaded.finalText());
            assertEquals("alice", loaded.userId());
            assertEquals(30, loaded.usage().total());
            assertEquals(3, loaded.steps().size(), "all appended steps survive the reopen");
            assertEquals("weather", loaded.steps().get(1).toolName());
            assertEquals(Map.of("city", "Montreal"), loaded.steps().get(1).data().get("arguments"));
            assertEquals(30, loaded.steps().get(2).usage().total(), "per-step usage round-trips");

            assertEquals(1, second.list(InteractionQuery.forUser("alice")).size());
            assertTrue(second.list(InteractionQuery.forUser("bob")).isEmpty());

            assertTrue(second.delete("int-1"));
            assertTrue(second.load("int-1").isEmpty());
            assertFalse(second.delete("int-1"), "deleting unknown returns false");
        } finally {
            second.stop();
        }
    }

    @Test
    void listFiltersByStatusAndConversation() {
        var dbPath = tmp.resolve("filter.db");
        var store = new SqliteInteractionStore(dbPath);
        store.start();
        try {
            store.save(sample("int-done"));
            store.save(new Interaction("int-run", null, "conv-2", "agent", "alice", "gpt",
                    InteractionStatus.RUNNING, false, true, List.of(), null, null, null,
                    Instant.parse("2026-06-01T12:05:00Z"), Instant.parse("2026-06-01T12:05:00Z")));

            assertEquals(1, store.list(new InteractionQuery("alice", null,
                    InteractionStatus.COMPLETED, 10)).size());
            assertEquals(1, store.list(InteractionQuery.forConversation("conv-2")).size());
            assertEquals(2, store.list(InteractionQuery.forUser("alice")).size());
        } finally {
            store.stop();
        }
    }
}
