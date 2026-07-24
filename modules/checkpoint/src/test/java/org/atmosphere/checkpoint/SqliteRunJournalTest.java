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

import org.atmosphere.ai.resume.RunEvent;
import org.atmosphere.ai.resume.RunJournal.RunRecord;
import org.atmosphere.ai.resume.RunRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the crash-durable {@link SqliteRunJournal}: run metadata and captured
 * events survive a full close/reopen over the same file, and a fresh
 * {@link RunRegistry} built over the reopened journal rehydrates the run and its
 * replay events — the production resume path.
 */
class SqliteRunJournalTest {

    private static final Instant CREATED = Instant.parse("2026-06-11T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void runAndEventsRehydrateOnAFreshInstanceOverTheSameFile() {
        var path = tempDir.resolve("runs.db");
        try (var journal = new SqliteRunJournal(path)) {
            journal.recordRun(new RunRecord("run-1", "agent-a", "alice", "sess-1", CREATED));
            journal.appendEvent("run-1", new RunEvent(0, "token", "he", CREATED));
            journal.appendEvent("run-1", new RunEvent(1, "token", "llo", CREATED.plusMillis(5)));
            // Deliberately NOT removed: an in-flight run must survive the restart.
        }

        try (var reopened = new SqliteRunJournal(path)) {
            assertTrue(reopened.durable(), "SQLite run journal is crash-durable");
            assertEquals(1, reopened.runCount());

            // The production rehydration path: a brand-new registry over the
            // reopened journal must rebuild the run and its replay buffer.
            var registry = new RunRegistry(Clock.systemUTC(), RunRegistry.DEFAULT_TTL, reopened);
            assertEquals(1, registry.rehydrate(), "the persisted run rehydrates after restart");

            var handle = registry.lookup("run-1").orElseThrow();
            assertEquals("alice", handle.userId(), "the authorization owner survives the restart");
            var events = handle.replayableEvents();
            assertEquals(2, events.size(), "captured events survive a reopen");
            assertEquals(0L, events.get(0).sequence());
            assertEquals(1L, events.get(1).sequence());
            assertEquals("llo", events.get(1).payload());
        }
    }

    @Test
    void loadAllAndLoadEventsSurviveReopen() {
        var path = tempDir.resolve("direct.db");
        try (var journal = new SqliteRunJournal(path)) {
            journal.recordRun(new RunRecord("r", "agent", "bob", "s", CREATED));
            journal.appendEvent("r", new RunEvent(7, "complete", "{}", CREATED));
        }
        try (var reopened = new SqliteRunJournal(path)) {
            assertEquals(1, reopened.loadAll().size());
            var events = reopened.loadEvents("r");
            assertEquals(1, events.size());
            assertEquals(7L, events.get(0).sequence(), "original sequence numbers are preserved");
        }
    }

    @Test
    void removeRunDropsMetadataAndEvents() {
        try (var journal = new SqliteRunJournal(tempDir.resolve("remove.db"))) {
            journal.recordRun(new RunRecord("r", "agent", "carol", "s", CREATED));
            journal.appendEvent("r", new RunEvent(0, "token", "x", CREATED));
            journal.removeRun("r");
            assertEquals(0, journal.runCount());
            assertTrue(journal.loadEvents("r").isEmpty(), "events are removed with the run");
        }
    }

    @Test
    void eventForUnknownRunIsDropped() {
        try (var journal = new SqliteRunJournal(tempDir.resolve("orphan.db"))) {
            journal.appendEvent("ghost", new RunEvent(0, "token", "x", CREATED));
            assertTrue(journal.loadEvents("ghost").isEmpty(),
                    "an event for an unrecorded run is not journaled");
        }
    }

    @Test
    void perRunEventsAreBoundedEvictingOldestFirst() {
        try (var journal = new SqliteRunJournal(tempDir.resolve("bound.db"), 100, 3)) {
            journal.recordRun(new RunRecord("r", "agent", "dave", "s", CREATED));
            for (long seq = 0; seq < 5; seq++) {
                journal.appendEvent("r", new RunEvent(seq, "token", "e" + seq, CREATED));
            }
            var events = journal.loadEvents("r");
            assertEquals(3, events.size(), "per-run events bounded at the cap");
            assertEquals(2L, events.get(0).sequence(), "the two oldest events were evicted");
            assertEquals(4L, events.get(2).sequence());
        }
    }

    @Test
    void runRetentionEvictsOldestByCreatedAt() {
        try (var journal = new SqliteRunJournal(tempDir.resolve("retain.db"), 2, 100)) {
            journal.recordRun(new RunRecord("old", "agent", "u", "s", CREATED));
            journal.recordRun(new RunRecord("mid", "agent", "u", "s", CREATED.plusSeconds(1)));
            journal.recordRun(new RunRecord("new", "agent", "u", "s", CREATED.plusSeconds(2)));
            assertEquals(2, journal.runCount(), "run count bounded at the cap");
            assertFalse(journal.loadAll().stream().anyMatch(r -> r.runId().equals("old")),
                    "the oldest run was evicted");
        }
    }
}
