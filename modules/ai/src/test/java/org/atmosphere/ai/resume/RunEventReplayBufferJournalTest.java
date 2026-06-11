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

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunEventReplayBufferJournalTest {

    @Test
    void captureMirrorsToAttachedJournal() {
        var journal = new InMemoryRunJournal();
        journal.recordRun(new RunJournal.RunRecord("r1", "agent", "alice", "s1", java.time.Instant.now()));
        var buffer = new RunEventReplayBuffer();
        buffer.attachJournal(journal, "r1");

        buffer.capture("streaming-text", "a");
        buffer.capture("streaming-text", "b");

        assertEquals(2, journal.loadEvents("r1").size(), "captures mirrored to the journal");
    }

    @Test
    void attachJournalIsIdempotentFirstWins() {
        var first = new InMemoryRunJournal();
        var second = new InMemoryRunJournal();
        first.recordRun(new RunJournal.RunRecord("r1", "agent", "alice", "s1", java.time.Instant.now()));
        second.recordRun(new RunJournal.RunRecord("r1", "agent", "alice", "s1", java.time.Instant.now()));

        var buffer = new RunEventReplayBuffer();
        buffer.attachJournal(first, "r1");
        // A second attach must be ignored — the buffer cannot be rebound to a
        // different journal mid-run.
        buffer.attachJournal(second, "r1");
        buffer.capture("streaming-text", "x");

        assertEquals(1, first.loadEvents("r1").size(), "events go to the first-attached journal");
        assertEquals(0, second.loadEvents("r1").size(), "second journal never bound");
    }

    @Test
    void restorePreservesSequencesAndAdvancesCounter() {
        var buffer = new RunEventReplayBuffer();
        buffer.restore(java.util.List.of(
                new RunEvent(5, "streaming-text", "five", java.time.Instant.now()),
                new RunEvent(6, "streaming-text", "six", java.time.Instant.now())));

        var snapshot = buffer.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals(5, snapshot.get(0).sequence(), "restored sequence numbers preserved");

        // A capture after restore continues past the highest restored sequence
        // rather than colliding with rehydrated history.
        var next = buffer.capture("streaming-text", "seven");
        assertEquals(7, next.sequence());
    }
}
