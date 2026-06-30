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
package org.atmosphere.checkpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteCheckpointStoreTest {

    @TempDir
    Path tempDir;

    private SqliteCheckpointStore store;

    @BeforeEach
    void setUp() {
        store = new SqliteCheckpointStore(tempDir.resolve("test-checkpoints.db"));
        store.start();
    }

    @AfterEach
    void tearDown() {
        store.stop();
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

        // Both should be loadable
        assertTrue(store.load(root.id()).isPresent());
        assertTrue(store.load(forked.id()).isPresent());
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

        // Reopen the same database file
        var store2 = new SqliteCheckpointStore(tempDir.resolve("test-checkpoints.db"));
        store2.start();
        try {
            var loaded = store2.load(snapshot.id());
            assertTrue(loaded.isPresent(), "Snapshot should survive JVM restart");
            assertEquals("c1", loaded.get().coordinationId());
        } finally {
            store2.stop();
        }
    }

    @Test
    void listenerReceivesEvents() {
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

    /** A record used as workflow state — the {@code ClassCastException} case. */
    record Person(String name, int age) { }

    /**
     * Regression for the type-erasure bug: before the {@code state_type}
     * column, {@code fromRow} deserialized to {@code Object.class}, so a typed
     * record came back as a {@code LinkedHashMap} and any consumer casting the
     * state (e.g. {@code AgentPassivation.resume}) threw
     * {@code ClassCastException}. The recorded class name now restores the
     * original type.
     */
    @Test
    void typedRecordStateRoundTripsFaithfully() {
        var snap = WorkflowSnapshot.<Person>builder()
                .coordinationId("c-typed")
                .state(new Person("Alice", 42))
                .build();
        store.save(snap);

        var loaded = store.load(snap.id());
        assertTrue(loaded.isPresent());
        var state = loaded.get().state();
        assertInstanceOf(Person.class, state,
                "typed record state must round-trip as its original type, not a LinkedHashMap");
        assertEquals(new Person("Alice", 42), state);
    }

    /**
     * Graceful fallback: {@code Map.of(...)} state is not Jackson-reconstructible
     * via its concrete (immutable) class, so the store falls back to generic
     * mapping rather than failing — preserving the pre-fix behaviour for
     * collection state.
     */
    @Test
    void collectionStateFallsBackToGenericMapping() {
        var snap = WorkflowSnapshot.root("c-map", Map.of("step", "research"));
        store.save(snap);

        var loaded = store.load(snap.id());
        assertTrue(loaded.isPresent());
        assertInstanceOf(Map.class, loaded.get().state());
        assertEquals("research", ((Map<?, ?>) loaded.get().state()).get("step"));
    }

    /**
     * Regression for the unbounded-growth DoS vector (Correctness Invariant #3):
     * the store must retain at most its configured cap, evicting the oldest.
     */
    @Test
    void evictsOldestBeyondCap() {
        var capped = new SqliteCheckpointStore(tempDir.resolve("capped.db"), 3);
        capped.start();
        try {
            var base = Instant.parse("2026-01-01T00:00:00Z");
            for (int i = 0; i < 5; i++) {
                capped.save(WorkflowSnapshot.<String>builder()
                        .coordinationId("c")
                        .state("s" + i)
                        .createdAt(base.plusSeconds(i))
                        .build());
            }
            var all = capped.list(CheckpointQuery.builder().build());
            assertEquals(3, all.size(), "store must retain at most the configured cap");
            var states = all.stream().map(WorkflowSnapshot::state).toList();
            assertTrue(states.contains("s2") && states.contains("s3") && states.contains("s4"),
                    "the three newest snapshots must survive");
            assertFalse(states.contains("s0") || states.contains("s1"),
                    "the two oldest snapshots must be evicted");
        } finally {
            capped.stop();
        }
    }

    /**
     * Pins the eviction policy as a <em>global</em> cap, not a per-coordination
     * one. Five snapshots spread across two coordinations with a cap of three
     * must collapse to the three newest <em>overall</em> — a per-coordination
     * cap of three would keep all five (each coordination is within its own
     * cap). This bounds total table size (Invariant #3) even when an attacker
     * fans writes across unbounded distinct coordination ids.
     */
    @Test
    void capIsGlobalAcrossCoordinations() {
        var capped = new SqliteCheckpointStore(tempDir.resolve("global-cap.db"), 3);
        capped.start();
        try {
            var base = Instant.parse("2026-01-01T00:00:00Z");
            // Insertion order == createdAt order; alternate coordinations a/b.
            capped.save(snap("a", "s0", base.plusSeconds(0)));
            capped.save(snap("b", "s1", base.plusSeconds(1)));
            capped.save(snap("a", "s2", base.plusSeconds(2)));
            capped.save(snap("b", "s3", base.plusSeconds(3)));
            capped.save(snap("a", "s4", base.plusSeconds(4)));

            var states = capped.list(CheckpointQuery.builder().build())
                    .stream().map(WorkflowSnapshot::state).toList();
            assertEquals(3, states.size(),
                    "global cap must bound the whole table, not each coordination");
            assertTrue(states.contains("s2") && states.contains("s3") && states.contains("s4"),
                    "the three newest snapshots overall must survive");
            assertFalse(states.contains("s0") || states.contains("s1"),
                    "the two oldest snapshots overall must be evicted");
        } finally {
            capped.stop();
        }
    }

    /**
     * Migration regression: a database created before the {@code state_type}
     * column existed must keep working. {@code start()} adds the column via
     * {@code ALTER TABLE}, the pre-existing row loads (its untyped state falls
     * back to generic mapping), and new saves record the type going forward.
     */
    @Test
    void migratesLegacySchemaMissingStateType() throws Exception {
        var dbPath = tempDir.resolve("legacy.db");
        // Pre-create a legacy-schema table (no state_type) and seed a row.
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath.toAbsolutePath());
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE checkpoints (
                    id TEXT PRIMARY KEY,
                    parent_id TEXT,
                    coordination_id TEXT NOT NULL,
                    agent_name TEXT,
                    state_json TEXT,
                    metadata_json TEXT,
                    created_at TEXT NOT NULL
                )""");
            stmt.execute("INSERT INTO checkpoints "
                    + "(id, coordination_id, state_json, metadata_json, created_at) VALUES "
                    + "('legacy-1', 'c-legacy', '{\"k\":\"v\"}', '{}', '2026-01-01T00:00:00Z')");
        }

        var migrated = new SqliteCheckpointStore(dbPath);
        migrated.start();
        try {
            var loaded = migrated.load(CheckpointId.of("legacy-1"));
            assertTrue(loaded.isPresent(), "legacy row must survive the state_type migration");
            assertEquals("c-legacy", loaded.get().coordinationId());
            assertInstanceOf(Map.class, loaded.get().state());
            assertEquals("v", ((Map<?, ?>) loaded.get().state()).get("k"));

            // New saves on the migrated schema must round-trip their type.
            var snap = WorkflowSnapshot.<Person>builder()
                    .coordinationId("c-legacy").state(new Person("Bob", 7)).build();
            migrated.save(snap);
            assertInstanceOf(Person.class, migrated.load(snap.id()).orElseThrow().state());
        } finally {
            migrated.stop();
        }
    }

    private static WorkflowSnapshot<String> snap(String coord, String state, Instant at) {
        return WorkflowSnapshot.<String>builder()
                .coordinationId(coord).state(state).createdAt(at).build();
    }
}
