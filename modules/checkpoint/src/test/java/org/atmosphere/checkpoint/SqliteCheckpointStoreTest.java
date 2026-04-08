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
}
