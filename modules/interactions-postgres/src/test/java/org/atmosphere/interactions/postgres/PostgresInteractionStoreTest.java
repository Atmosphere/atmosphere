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
package org.atmosphere.interactions.postgres;

import org.atmosphere.ai.TokenUsage;
import org.atmosphere.interactions.Interaction;
import org.atmosphere.interactions.InteractionQuery;
import org.atmosphere.interactions.InteractionStatus;
import org.atmosphere.interactions.InteractionStep;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for {@link PostgresInteractionStore} exercised against an
 * H2 in-memory database in PostgreSQL-compatibility mode. H2 accepts enough
 * Postgres dialect (and the store deliberately writes portable SQL) to validate
 * schema auto-create, the transactional DELETE-then-INSERT upsert, the step log,
 * filtered listing, and deletion. The scenarios mirror
 * {@code SqliteInteractionStoreTest}. A live Postgres integration test belongs
 * in a separate Testcontainers module.
 */
class PostgresInteractionStoreTest {

    private JdbcDataSource ds;
    private PostgresInteractionStore store;

    @BeforeEach
    void setUp() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:interactions-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        store = new PostgresInteractionStore(ds);
        store.start();
    }

    @AfterEach
    void tearDown() throws SQLException {
        store.stop();
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("SHUTDOWN");
        }
    }

    private Interaction sample(String id) {
        var now = Instant.parse("2026-06-01T12:00:00.123456789Z");
        return new Interaction(id, null, "conv-1", "agent-x", "alice", "gpt-4",
                InteractionStatus.COMPLETED, true, true, List.of(),
                "the answer", new TokenUsage(10, 20, 0, 30, "gpt-4"), null, now, now);
    }

    @Test
    void persistsHeaderAndStepsRoundTrip() {
        store.save(sample("int-1"));
        store.appendStep("int-1", new InteractionStep(0, "text", "the answer", null,
                Map.of(), null, Instant.parse("2026-06-01T12:00:00Z")));
        store.appendStep("int-1", new InteractionStep(1, "tool-call", null, "weather",
                Map.of("arguments", Map.of("city", "Montreal")), null,
                Instant.parse("2026-06-01T12:00:01Z")));
        store.appendStep("int-1", new InteractionStep(2, "usage", null, null, Map.of(),
                new TokenUsage(10, 20, 0, 30, "gpt-4"), Instant.parse("2026-06-01T12:00:02Z")));

        var loaded = store.load("int-1").orElseThrow();
        assertEquals(InteractionStatus.COMPLETED, loaded.status());
        assertEquals("the answer", loaded.finalText());
        assertEquals("alice", loaded.userId());
        assertTrue(loaded.background());
        assertEquals(30, loaded.usage().total());
        assertEquals(Instant.parse("2026-06-01T12:00:00.123456789Z"), loaded.createdAt(),
                "BIGINT epoch-nanos timestamp round-trips with nanosecond precision");
        assertEquals(3, loaded.steps().size(), "all appended steps survive");
        assertEquals("weather", loaded.steps().get(1).toolName());
        assertEquals(Map.of("city", "Montreal"), loaded.steps().get(1).data().get("arguments"));
        assertEquals(30, loaded.steps().get(2).usage().total(), "per-step usage round-trips");
        assertEquals(Instant.parse("2026-06-01T12:00:01Z"), loaded.steps().get(1).createdAt());

        assertEquals(1, store.list(InteractionQuery.forUser("alice")).size());
        assertTrue(store.list(InteractionQuery.forUser("bob")).isEmpty());

        assertTrue(store.delete("int-1"));
        assertTrue(store.load("int-1").isEmpty());
        assertFalse(store.delete("int-1"), "deleting unknown returns false");
    }

    @Test
    void upsertReplacesHeaderInPlace() {
        store.save(sample("int-up"));
        // Save the same id again with a different status/finalText — the
        // transactional DELETE-then-INSERT must overwrite, not duplicate.
        var updated = sample("int-up").withResult(InteractionStatus.FAILED, List.of(),
                "boom", null, "model overloaded", Instant.parse("2026-06-01T12:10:00Z"));
        store.save(updated);

        var loaded = store.load("int-up").orElseThrow();
        assertEquals(InteractionStatus.FAILED, loaded.status(), "status overwritten by upsert");
        assertEquals("boom", loaded.finalText());
        assertEquals("model overloaded", loaded.errorMessage());
        assertEquals(1, store.list(InteractionQuery.forUser("alice")).size(),
                "upsert replaces in place — no duplicate header row");

        // Re-appending the same (interaction_id, seq) replaces the step too.
        store.appendStep("int-up", new InteractionStep(0, "text", "first", null,
                Map.of(), null, Instant.parse("2026-06-01T12:11:00Z")));
        store.appendStep("int-up", new InteractionStep(0, "text", "second", null,
                Map.of(), null, Instant.parse("2026-06-01T12:11:01Z")));
        var reloaded = store.load("int-up").orElseThrow();
        assertEquals(1, reloaded.steps().size(), "duplicate seq replaced, not appended");
        assertEquals("second", reloaded.steps().get(0).text());
    }

    @Test
    void listFiltersByStatusAndConversation() {
        store.save(sample("int-done"));
        store.save(new Interaction("int-run", null, "conv-2", "agent", "alice", "gpt",
                InteractionStatus.RUNNING, false, true, List.of(), null, null, null,
                Instant.parse("2026-06-01T12:05:00Z"), Instant.parse("2026-06-01T12:05:00Z")));

        assertEquals(1, store.list(new InteractionQuery("alice", null,
                InteractionStatus.COMPLETED, 10)).size());
        assertEquals(1, store.list(InteractionQuery.forConversation("conv-2")).size());
        assertEquals(2, store.list(InteractionQuery.forUser("alice")).size());
    }
}
