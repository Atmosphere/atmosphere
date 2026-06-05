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
package org.atmosphere.checkpoint.postgres;

import org.atmosphere.checkpoint.CheckpointEvent;
import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link PostgresCheckpointStore} against an H2 in-memory database in
 * PostgreSQL-compatibility mode. The portable SQL the store emits runs
 * identically on H2's Postgres mode and on real Postgres, so this validates the
 * full save/load/list/delete/upsert contract without a live Postgres. A live
 * Postgres integration test belongs in a separate Testcontainers module.
 *
 * <p>Scenarios are ported from {@code SqliteCheckpointStoreTest} so the JDBC
 * backend is held to the same behavioural contract as the SQLite store.</p>
 */
class PostgresCheckpointStoreTest {

    private JdbcDataSource ds;
    private PostgresCheckpointStore store;

    @BeforeEach
    void setUp() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:checkpoints-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        store = new PostgresCheckpointStore(ds);
        store.start();
    }

    @AfterEach
    void tearDown() throws SQLException {
        store.stop();
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("SHUTDOWN");
        }
    }

    @Test
    void saveAndLoad() {
        var snapshot = WorkflowSnapshot.root("coord-1", Map.of("step", "research"));
        store.save(snapshot);

        var loaded = store.load(snapshot.id());
        assertTrue(loaded.isPresent());
        assertEquals(snapshot.id(), loaded.get().id());
        assertEquals("coord-1", loaded.get().coordinationId());
    }

    @Test
    void loadMissingReturnsEmpty() {
        var result = store.load(CheckpointId.of("nonexistent"));
        assertTrue(result.isEmpty());
    }

    @Test
    void saveOverwritesExisting() {
        var id = CheckpointId.random();
        var v1 = WorkflowSnapshot.builder()
                .id(id).coordinationId("c1").state("v1").createdAt(Instant.now()).build();
        var v2 = WorkflowSnapshot.builder()
                .id(id).coordinationId("c1").state("v2").createdAt(Instant.now()).build();

        store.save(v1);
        store.save(v2);

        var loaded = store.load(id);
        assertTrue(loaded.isPresent());
        assertEquals("v2", loaded.get().state());
        // Upsert must not leave a duplicate row behind.
        assertEquals(1, store.list(CheckpointQuery.forCoordination("c1")).size());
    }

    @Test
    void fork() {
        var root = WorkflowSnapshot.root("coord-1", "initial state");
        store.save(root);

        var forked = store.fork(root.id(), "forked state");
        assertNotNull(forked);
        assertEquals(root.id(), forked.parentId());
        assertEquals("coord-1", forked.coordinationId());
        assertEquals("forked state", forked.state());

        // Both should be loadable.
        assertTrue(store.load(root.id()).isPresent());
        assertTrue(store.load(forked.id()).isPresent());
    }

    @Test
    void forkUnknownSourceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> store.fork(CheckpointId.of("ghost"), "state"));
    }

    @Test
    void listByCoordination() {
        store.save(WorkflowSnapshot.root("coord-1", "a"));
        store.save(WorkflowSnapshot.root("coord-1", "b"));
        store.save(WorkflowSnapshot.root("coord-2", "c"));

        var results = store.list(CheckpointQuery.forCoordination("coord-1"));
        assertEquals(2, results.size());
    }

    @Test
    void listByAgent() {
        store.save(WorkflowSnapshot.<String>builder()
                .coordinationId("c1").agentName("alpha").state("a").build());
        store.save(WorkflowSnapshot.<String>builder()
                .coordinationId("c1").agentName("beta").state("b").build());

        var results = store.list(CheckpointQuery.forAgent("alpha"));
        assertEquals(1, results.size());
        assertEquals("alpha", results.getFirst().agentName());
    }

    @Test
    void listWithLimit() {
        for (int i = 0; i < 10; i++) {
            store.save(WorkflowSnapshot.root("c1", "state-" + i));
        }
        var results = store.list(CheckpointQuery.builder()
                .coordinationId("c1").limit(3).build());
        assertEquals(3, results.size());
    }

    @Test
    void listSinceUntilNarrowsByTimestamp() {
        var early = WorkflowSnapshot.<String>builder()
                .coordinationId("c1").state("early")
                .createdAt(Instant.ofEpochMilli(1_000)).build();
        var late = WorkflowSnapshot.<String>builder()
                .coordinationId("c1").state("late")
                .createdAt(Instant.ofEpochMilli(9_000)).build();
        store.save(early);
        store.save(late);

        var sinceFive = store.list(CheckpointQuery.builder()
                .coordinationId("c1").since(Instant.ofEpochMilli(5_000)).build());
        assertEquals(1, sinceFive.size());
        assertEquals("late", sinceFive.getFirst().state());

        var untilFive = store.list(CheckpointQuery.builder()
                .coordinationId("c1").until(Instant.ofEpochMilli(5_000)).build());
        assertEquals(1, untilFive.size());
        assertEquals("early", untilFive.getFirst().state());
    }

    @Test
    void delete() {
        var snapshot = WorkflowSnapshot.root("c1", "data");
        store.save(snapshot);

        assertTrue(store.delete(snapshot.id()));
        assertTrue(store.load(snapshot.id()).isEmpty());
        assertFalse(store.delete(snapshot.id()));
    }

    @Test
    void deleteCoordination() {
        store.save(WorkflowSnapshot.root("c1", "a"));
        store.save(WorkflowSnapshot.root("c1", "b"));
        store.save(WorkflowSnapshot.root("c2", "c"));

        assertEquals(2, store.deleteCoordination("c1"));
        assertEquals(0, store.list(CheckpointQuery.forCoordination("c1")).size());
        assertEquals(1, store.list(CheckpointQuery.forCoordination("c2")).size());
    }

    @Test
    void persistsAcrossRestart() {
        var snapshot = WorkflowSnapshot.root("c1", Map.of("key", "value"));
        store.save(snapshot);
        store.stop();

        // Reopen against the SAME in-memory database (DB_CLOSE_DELAY=-1 keeps it
        // alive between DataSource connections). start() must be idempotent — the
        // table already exists.
        var store2 = new PostgresCheckpointStore(ds);
        store2.start();
        try {
            var loaded = store2.load(snapshot.id());
            assertTrue(loaded.isPresent(), "Snapshot should survive a store restart");
            assertEquals("c1", loaded.get().coordinationId());
        } finally {
            store2.stop();
        }
    }

    @Test
    void listenerReceivesSaveEvents() {
        var saveCount = new AtomicInteger();
        store.addListener(event -> {
            if (event instanceof CheckpointEvent.Saved) {
                saveCount.incrementAndGet();
            }
        });

        store.save(WorkflowSnapshot.root("c1", "data"));
        assertEquals(1, saveCount.get());
    }

    @Test
    void listenerReceivesDeleteEvents() {
        var deleteCount = new AtomicInteger();
        store.addListener(event -> {
            if (event instanceof CheckpointEvent.Deleted) {
                deleteCount.incrementAndGet();
            }
        });

        var snapshot = WorkflowSnapshot.root("c1", "data");
        store.save(snapshot);
        store.delete(snapshot.id());
        assertEquals(1, deleteCount.get());
    }

    @Test
    void removedListenerStopsReceivingEvents() {
        var count = new AtomicInteger();
        org.atmosphere.checkpoint.CheckpointListener listener =
                event -> count.incrementAndGet();
        store.addListener(listener);
        store.save(WorkflowSnapshot.root("c1", "a"));
        store.removeListener(listener);
        store.save(WorkflowSnapshot.root("c1", "b"));
        // Exactly one event observed: the first save before removal.
        assertEquals(1, count.get());
    }

    @Test
    void metadataRoundTrips() {
        var snapshot = WorkflowSnapshot.<String>builder()
                .coordinationId("c1")
                .state("data")
                .metadata(Map.of("tag", "test", "env", "ci"))
                .build();
        store.save(snapshot);

        var loaded = store.load(snapshot.id());
        assertTrue(loaded.isPresent());
        assertEquals("test", loaded.get().metadata().get("tag"));
        assertEquals("ci", loaded.get().metadata().get("env"));
    }

    @Test
    void rejectsInvalidTableName() {
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresCheckpointStore(ds, "DROP TABLE users;"));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresCheckpointStore(ds, "1badstart"));
    }
}
