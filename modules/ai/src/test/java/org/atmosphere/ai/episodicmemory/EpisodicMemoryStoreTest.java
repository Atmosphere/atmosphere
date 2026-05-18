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
package org.atmosphere.ai.episodicmemory;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.atmosphere.ai.jfr.EpisodicMemoryAccessEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers store/recall/forget semantics on both bundled
 * {@link EpisodicMemoryStore} implementations plus the JFR events they
 * emit through {@link EpisodicMemoryAccessEvent}.
 */
class EpisodicMemoryStoreTest {

    @Test
    void inMemoryStoreAndRecallByType() {
        var store = EpisodicMemoryStore.inMemory();
        store.store(MemoryEntry.of(EpisodicMemoryType.USER, "ChefFamille is a Java Champion"));
        store.store(MemoryEntry.of(EpisodicMemoryType.FEEDBACK, "Never use --no-verify"));
        store.store(MemoryEntry.of(EpisodicMemoryType.PROJECT, "Spring AI 2.0.0-M6 landed"));

        assertEquals(3, store.size());
        var feedback = store.recall(EpisodicMemoryQuery.ofType(EpisodicMemoryType.FEEDBACK, 10));
        assertEquals(1, feedback.size());
        assertEquals(EpisodicMemoryType.FEEDBACK, feedback.get(0).type());
        assertTrue(feedback.get(0).content().contains("--no-verify"));
    }

    @Test
    void recentReturnsNewestFirstAndRespectsLimit() throws InterruptedException {
        var store = EpisodicMemoryStore.inMemory();
        store.store(new MemoryEntry("a", EpisodicMemoryType.USER, "first",
                Instant.parse("2026-01-01T00:00:00Z"), Map.of()));
        store.store(new MemoryEntry("b", EpisodicMemoryType.USER, "second",
                Instant.parse("2026-02-01T00:00:00Z"), Map.of()));
        store.store(new MemoryEntry("c", EpisodicMemoryType.USER, "third",
                Instant.parse("2026-03-01T00:00:00Z"), Map.of()));

        var recent = store.recall(EpisodicMemoryQuery.recent(2));
        assertEquals(2, recent.size());
        assertEquals("third", recent.get(0).content());
        assertEquals("second", recent.get(1).content());
    }

    @Test
    void containsFilterIsCaseInsensitive() {
        var store = EpisodicMemoryStore.inMemory();
        store.store(MemoryEntry.of(EpisodicMemoryType.PROJECT, "Quarkus parity gap closed"));
        store.store(MemoryEntry.of(EpisodicMemoryType.PROJECT, "Spring Boot 4 migration"));

        var query = new EpisodicMemoryQuery(Optional.empty(), Optional.of("QUARKUS"), 10);
        var matches = store.recall(query);
        assertEquals(1, matches.size());
        assertTrue(matches.get(0).content().toLowerCase().contains("quarkus"));
    }

    @Test
    void storeReplacesEntryWithSameId() {
        var store = EpisodicMemoryStore.inMemory();
        store.store(new MemoryEntry("dup", EpisodicMemoryType.USER, "v1",
                Instant.parse("2026-01-01T00:00:00Z"), Map.of()));
        store.store(new MemoryEntry("dup", EpisodicMemoryType.USER, "v2",
                Instant.parse("2026-01-02T00:00:00Z"), Map.of()));

        assertEquals(1, store.size());
        var found = store.recall(EpisodicMemoryQuery.recent(10));
        assertEquals("v2", found.get(0).content());
    }

    @Test
    void forgetRemovesByIdAndReturnsBoolean() {
        var store = EpisodicMemoryStore.inMemory();
        var entry = MemoryEntry.of(EpisodicMemoryType.REFERENCE, "https://example.com");
        store.store(entry);
        assertTrue(store.forget(entry.id()));
        assertFalse(store.forget(entry.id()));
        assertEquals(0, store.size());
    }

    @Test
    void jsonFileStorePersistsAcrossInstances(@TempDir Path tmp) {
        var path = tmp.resolve("memory.json");
        var first = EpisodicMemoryStore.jsonFile(path);
        first.store(MemoryEntry.of(EpisodicMemoryType.USER, "persistent fact"));
        first.store(MemoryEntry.of(EpisodicMemoryType.FEEDBACK, "another"));
        assertEquals(2, first.size());
        assertTrue(Files.isRegularFile(path), "file should exist after first store");

        var second = EpisodicMemoryStore.jsonFile(path);
        assertEquals(2, second.size());
        var recalled = second.recall(EpisodicMemoryQuery.recent(10));
        assertEquals(2, recalled.size());
        assertNotNull(recalled.get(0).createdAt(), "createdAt should survive round-trip");
    }

    @Test
    void jsonFileStoreHandlesMissingFile(@TempDir Path tmp) {
        var path = tmp.resolve("does-not-exist.json");
        var store = EpisodicMemoryStore.jsonFile(path);
        assertEquals(0, store.size());
        var empty = store.recall(EpisodicMemoryQuery.recent(5));
        assertTrue(empty.isEmpty());
    }

    @Test
    void emitsJfrEventsOnStoreAndRecall() throws Exception {
        var events = recordAndCollect(() -> {
            var store = EpisodicMemoryStore.inMemory();
            store.store(MemoryEntry.of(EpisodicMemoryType.USER, "test"));
            store.recall(EpisodicMemoryQuery.ofType(EpisodicMemoryType.USER, 5));
        });

        assertEquals(2, events.size());
        var storeEvent = events.get(0);
        assertEquals(EpisodicMemoryAccessEvent.OPERATION_STORE, storeEvent.getValue("operation"));
        assertEquals(EpisodicMemoryType.USER.name(), storeEvent.getValue("type"));
        assertEquals(1, ((Number) storeEvent.getValue("count")).intValue());

        var recallEvent = events.get(1);
        assertEquals(EpisodicMemoryAccessEvent.OPERATION_RECALL, recallEvent.getValue("operation"));
        assertEquals(EpisodicMemoryType.USER.name(), recallEvent.getValue("type"));
        assertEquals(1, ((Number) recallEvent.getValue("count")).intValue());
    }

    @Test
    void forgetEmitsJfrEventOnlyWhenEntryExisted() throws Exception {
        var store = EpisodicMemoryStore.inMemory();
        var entry = MemoryEntry.of(EpisodicMemoryType.USER, "x");
        store.store(entry);
        var events = recordAndCollect(() -> {
            store.forget(entry.id());      // hit
            store.forget("nope");          // miss — no event
        });

        var forgetEvents = events.stream()
                .filter(e -> EpisodicMemoryAccessEvent.OPERATION_FORGET.equals(e.getValue("operation")))
                .toList();
        assertEquals(1, forgetEvents.size(),
                "FORGET event must fire only when an entry was actually removed");
    }

    private static List<RecordedEvent> recordAndCollect(Runnable body) throws Exception {
        try (var recording = new Recording()) {
            recording.enable("org.atmosphere.ai.EpisodicMemoryAccess");
            recording.start();
            body.run();
            recording.stop();
            var dump = Files.createTempFile("atmosphere-mem-test-", ".jfr");
            try {
                recording.dump(dump);
                var collected = new ArrayList<RecordedEvent>();
                try (var file = new RecordingFile(dump)) {
                    while (file.hasMoreEvents()) {
                        var event = file.readEvent();
                        if ("org.atmosphere.ai.EpisodicMemoryAccess".equals(event.getEventType().getName())) {
                            collected.add(event);
                        }
                    }
                }
                return collected;
            } finally {
                Files.deleteIfExists(dump);
            }
        }
    }
}
