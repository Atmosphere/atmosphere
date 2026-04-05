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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCheckpointStoreTest {

    private InMemoryCheckpointStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCheckpointStore();
        store.start();
    }

    @AfterEach
    void tearDown() {
        store.stop();
    }

    @Test
    void saveAndLoadRoundTrip() {
        var snapshot = WorkflowSnapshot.root("c1", "hello");
        store.save(snapshot);

        var loaded = store.<String>load(snapshot.id()).orElseThrow();
        assertEquals(snapshot.id(), loaded.id());
        assertEquals("hello", loaded.state());
        assertEquals("c1", loaded.coordinationId());
    }

    @Test
    void loadMissingReturnsEmpty() {
        assertTrue(store.load(CheckpointId.random()).isEmpty());
    }

    @Test
    void saveReplacesExistingId() {
        var snap1 = WorkflowSnapshot.<String>builder()
                .id(CheckpointId.of("fixed"))
                .coordinationId("c")
                .state("v1")
                .build();
        var snap2 = WorkflowSnapshot.<String>builder()
                .id(CheckpointId.of("fixed"))
                .coordinationId("c")
                .state("v2")
                .build();
        store.save(snap1);
        store.save(snap2);

        assertEquals("v2", store.<String>load(CheckpointId.of("fixed")).orElseThrow().state());
        assertEquals(1, store.size());
    }

    @Test
    void forkCreatesChildWithParentLink() {
        var root = WorkflowSnapshot.root("c1", "root-state");
        store.save(root);

        var forked = store.fork(root.id(), "branch-state");

        assertEquals("branch-state", forked.state());
        assertEquals(root.id(), forked.parent().orElseThrow());
        assertEquals("c1", forked.coordinationId());
        assertNotEquals(root.id(), forked.id());
        assertTrue(store.load(forked.id()).isPresent());
    }

    @Test
    void forkUnknownSourceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> store.fork(CheckpointId.random(), "state"));
    }

    @Test
    void listFiltersByCoordinationId() {
        store.save(WorkflowSnapshot.root("c1", "a"));
        store.save(WorkflowSnapshot.root("c1", "b"));
        store.save(WorkflowSnapshot.root("c2", "c"));

        var c1 = store.list(CheckpointQuery.forCoordination("c1"));
        assertEquals(2, c1.size());
        assertTrue(c1.stream().allMatch(s -> "c1".equals(s.coordinationId())));
    }

    @Test
    void listFiltersByAgentName() {
        store.save(WorkflowSnapshot.<String>builder()
                .coordinationId("c").agentName("alpha").state("x").build());
        store.save(WorkflowSnapshot.<String>builder()
                .coordinationId("c").agentName("beta").state("y").build());

        var alpha = store.list(CheckpointQuery.forAgent("alpha"));
        assertEquals(1, alpha.size());
        assertEquals("alpha", alpha.get(0).agentName());
    }

    @Test
    void listFiltersByTimeRange() {
        var early = Instant.parse("2025-01-01T00:00:00Z");
        var mid = Instant.parse("2025-06-01T00:00:00Z");
        var late = Instant.parse("2025-12-01T00:00:00Z");

        store.save(WorkflowSnapshot.<String>builder()
                .coordinationId("c").state("a").createdAt(early).build());
        store.save(WorkflowSnapshot.<String>builder()
                .coordinationId("c").state("b").createdAt(mid).build());
        store.save(WorkflowSnapshot.<String>builder()
                .coordinationId("c").state("c").createdAt(late).build());

        var midOnly = store.list(CheckpointQuery.builder()
                .since(Instant.parse("2025-03-01T00:00:00Z"))
                .until(Instant.parse("2025-09-01T00:00:00Z"))
                .build());
        assertEquals(1, midOnly.size());
        assertEquals("b", midOnly.get(0).state());
    }

    @Test
    void listAppliesLimit() {
        for (int i = 0; i < 5; i++) {
            store.save(WorkflowSnapshot.<String>builder()
                    .coordinationId("c")
                    .state("s" + i)
                    .createdAt(Instant.ofEpochMilli(1000L + i))
                    .build());
        }
        var limited = store.list(CheckpointQuery.builder().limit(2).build());
        assertEquals(2, limited.size());
    }

    @Test
    void listReturnsStableOrderByCreatedAt() {
        var third = WorkflowSnapshot.<String>builder().coordinationId("c").state("c")
                .createdAt(Instant.ofEpochMilli(3000)).build();
        var first = WorkflowSnapshot.<String>builder().coordinationId("c").state("a")
                .createdAt(Instant.ofEpochMilli(1000)).build();
        var second = WorkflowSnapshot.<String>builder().coordinationId("c").state("b")
                .createdAt(Instant.ofEpochMilli(2000)).build();
        // Insert out of order
        store.save(third);
        store.save(first);
        store.save(second);

        var ordered = store.list(CheckpointQuery.all());
        assertEquals(List.of("a", "b", "c"),
                ordered.stream().map(s -> (String) s.state()).toList());
    }

    @Test
    void deleteRemovesSnapshot() {
        var snap = WorkflowSnapshot.root("c", "s");
        store.save(snap);
        assertTrue(store.delete(snap.id()));
        assertFalse(store.delete(snap.id()));
        assertTrue(store.load(snap.id()).isEmpty());
    }

    @Test
    void deleteCoordinationRemovesAllMatching() {
        store.save(WorkflowSnapshot.root("c1", "a"));
        store.save(WorkflowSnapshot.root("c1", "b"));
        store.save(WorkflowSnapshot.root("c2", "c"));

        int removed = store.deleteCoordination("c1");
        assertEquals(2, removed);
        assertEquals(1, store.size());
        assertEquals(0, store.list(CheckpointQuery.forCoordination("c1")).size());
    }

    @Test
    void listenersFireOnSaveLoadForkAndDelete() {
        var events = new ArrayList<CheckpointEvent>();
        store.addListener(events::add);

        var snap = WorkflowSnapshot.root("c", "s");
        store.save(snap);
        store.load(snap.id());
        var forked = store.fork(snap.id(), "s2");
        store.delete(snap.id());
        store.delete(forked.id());

        assertEquals(5, events.size());
        assertTrue(events.get(0) instanceof CheckpointEvent.Saved);
        assertTrue(events.get(1) instanceof CheckpointEvent.Loaded);
        assertTrue(events.get(2) instanceof CheckpointEvent.Forked);
        assertTrue(events.get(3) instanceof CheckpointEvent.Deleted);
        assertTrue(events.get(4) instanceof CheckpointEvent.Deleted);
    }

    @Test
    void listenerExceptionsDoNotAbortOperation() {
        store.addListener(event -> { throw new RuntimeException("boom"); });
        // Must not throw.
        var snap = WorkflowSnapshot.root("c", "s");
        store.save(snap);
        assertTrue(store.load(snap.id()).isPresent());
    }

    @Test
    void removeListenerStopsDelivery() {
        var count = new AtomicInteger();
        CheckpointListener l = event -> count.incrementAndGet();
        store.addListener(l);
        store.save(WorkflowSnapshot.root("c", "s"));
        assertEquals(1, count.get());
        store.removeListener(l);
        store.save(WorkflowSnapshot.root("c", "s2"));
        assertEquals(1, count.get());
    }

    @Test
    void enforcesMaxSnapshotsByEvictingOldest() {
        var small = new InMemoryCheckpointStore(3);
        small.start();
        try {
            for (int i = 0; i < 5; i++) {
                small.save(WorkflowSnapshot.<String>builder()
                        .coordinationId("c")
                        .state("s" + i)
                        .createdAt(Instant.ofEpochMilli(1000L + i))
                        .build());
            }
            assertEquals(3, small.size());
            var remaining = small.list(CheckpointQuery.all()).stream()
                    .map(s -> (String) s.state()).toList();
            // Oldest two (s0, s1) should have been evicted.
            assertEquals(List.of("s2", "s3", "s4"), remaining);
        } finally {
            small.stop();
        }
    }

    @Test
    void rejectsNonPositiveMaxSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryCheckpointStore(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryCheckpointStore(-1));
    }

    @Test
    void concurrentSavesArePersistedWithoutLoss() throws Exception {
        int threadCount = 8;
        int perThread = 100;
        var pool = Executors.newFixedThreadPool(threadCount);
        var ready = new CountDownLatch(threadCount);
        var go = new CountDownLatch(1);
        try {
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        store.save(WorkflowSnapshot.<String>builder()
                                .id(CheckpointId.of("t" + threadId + "-i" + i))
                                .coordinationId("c")
                                .state("v")
                                .build());
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(threadCount * perThread, store.size());
    }
}
