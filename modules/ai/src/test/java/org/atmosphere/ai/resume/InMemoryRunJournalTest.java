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
package org.atmosphere.ai.resume;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRunJournalTest {

    private static RunJournal.RunRecord record(String id, Instant createdAt) {
        return new RunJournal.RunRecord(id, "agent", "alice", "session-" + id, createdAt);
    }

    private static RunEvent event(long seq, String payload) {
        return new RunEvent(seq, "streaming-text", payload, Instant.now());
    }

    @Test
    void recordAndLoadRoundTrips() {
        var journal = new InMemoryRunJournal();
        journal.recordRun(record("r1", Instant.now()));
        journal.appendEvent("r1", event(0, "hello"));
        journal.appendEvent("r1", event(1, "world"));

        assertEquals(1, journal.loadAll().size());
        var events = journal.loadEvents("r1");
        assertEquals(2, events.size());
        assertEquals(0, events.get(0).sequence());
        assertEquals("world", events.get(1).payload());
    }

    @Test
    void removeRunDropsMetadataAndEvents() {
        var journal = new InMemoryRunJournal();
        journal.recordRun(record("r1", Instant.now()));
        journal.appendEvent("r1", event(0, "x"));
        journal.removeRun("r1");

        assertTrue(journal.loadAll().isEmpty());
        assertTrue(journal.loadEvents("r1").isEmpty());
    }

    @Test
    void eventsForUnrecordedRunAreDropped() {
        var journal = new InMemoryRunJournal();
        // No recordRun first — an event for a swept/unknown run must not
        // resurrect a half-run with no metadata.
        journal.appendEvent("ghost", event(0, "x"));
        assertTrue(journal.loadEvents("ghost").isEmpty());
    }

    @Test
    void perRunEventsAreBoundedOldestEvicted() {
        var journal = new InMemoryRunJournal(100, 3);
        journal.recordRun(record("r1", Instant.now()));
        for (var i = 0; i < 10; i++) {
            journal.appendEvent("r1", event(i, "e" + i));
        }
        var events = journal.loadEvents("r1");
        assertEquals(3, events.size(), "per-run events bounded at 3");
        assertEquals(7, events.get(0).sequence(), "oldest evicted, newest retained");
        assertEquals(9, events.get(2).sequence());
    }

    @Test
    void runCountIsBoundedOldestRunEvicted() {
        var journal = new InMemoryRunJournal(2, 16);
        journal.recordRun(record("old", Instant.now().minusSeconds(100)));
        journal.recordRun(record("mid", Instant.now().minusSeconds(50)));
        journal.recordRun(record("new", Instant.now()));

        assertEquals(2, journal.runCount(), "run count bounded at 2");
        // The oldest (by createdAt) is evicted.
        assertTrue(journal.loadAll().stream().noneMatch(r -> r.runId().equals("old")));
    }

    @Test
    void inMemoryJournalIsNotDurable() {
        assertFalse(new InMemoryRunJournal().durable(),
                "in-memory journal must report not-durable so callers do not advertise crash-durability");
    }
}
