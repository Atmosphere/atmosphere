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

import org.atmosphere.ai.ExecutionHandle;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-durable stream resume: a run registered + captured under a journal
 * survives into a fresh {@link RunRegistry} built over the same journal,
 * proving the rehydration wiring a real (out-of-process) journal backend
 * relies on.
 */
class RunRegistryDurableTest {

    @Test
    void registerPersistsMetadataAndCapturedEventsToJournal() {
        var journal = new InMemoryRunJournal();
        var registry = new RunRegistry(Clock.systemUTC(), RunRegistry.DEFAULT_TTL, journal);

        var handle = registry.register("agent", "alice", "session-1", new ExecutionHandle.Settable(null));
        handle.replayBuffer().capture("streaming-text", "hello");
        handle.replayBuffer().capture("complete", "");

        assertEquals(1, journal.loadAll().size());
        var events = journal.loadEvents(handle.runId());
        assertEquals(2, events.size(), "both captured events mirrored to the journal");
        assertEquals("hello", events.get(0).payload());
    }

    @Test
    void completedRunIsDeletedFromJournal() {
        var journal = new InMemoryRunJournal();
        var registry = new RunRegistry(Clock.systemUTC(), RunRegistry.DEFAULT_TTL, journal);

        var exec = new ExecutionHandle.Settable(null);
        var handle = registry.register("agent", "alice", "session-1", exec);
        handle.replayBuffer().capture("streaming-text", "hi");
        // Terminal path: completing the run must purge the durable entry so a
        // finished run is not rehydrated after restart.
        exec.complete();

        assertTrue(journal.loadAll().isEmpty(), "completed run removed from journal");
        assertTrue(journal.loadEvents(handle.runId()).isEmpty());
    }

    @Test
    void freshRegistryRehydratesRunsAndEventsFromJournal() {
        var journal = new InMemoryRunJournal();
        var registry = new RunRegistry(Clock.systemUTC(), RunRegistry.DEFAULT_TTL, journal);

        var handle = registry.register("agent", "alice", "session-1", new ExecutionHandle.Settable(null));
        handle.replayBuffer().capture("streaming-text", "part-one");
        handle.replayBuffer().capture("streaming-text", "part-two");
        var runId = handle.runId();

        // Simulate a process restart: a brand-new registry over the SAME
        // (persisted) journal — the previous in-memory registry is gone.
        var revived = new RunRegistry(Clock.systemUTC(), RunRegistry.DEFAULT_TTL, journal);
        var rehydrated = revived.rehydrate();
        assertEquals(1, rehydrated);

        var found = revived.lookup(runId);
        assertTrue(found.isPresent(), "rehydrated run is looked up by id post-restart");
        var events = found.get().replayableEvents();
        assertEquals(2, events.size());
        assertEquals("part-one", events.get(0).payload());
        assertEquals(0, events.get(0).sequence(), "original sequence numbers preserved");
        assertEquals(1, events.get(1).sequence());
        // Authorization key carried through (Invariant #6 — caller-owns-run).
        assertEquals("alice", found.get().userId());
    }

    @Test
    void rehydratedRunIsServedByLookupThenSweptByTtl() {
        var journal = new InMemoryRunJournal();
        var seed = new RunRegistry(Clock.systemUTC(), RunRegistry.DEFAULT_TTL, journal);
        var handle = seed.register("agent", "alice", "session-1", new ExecutionHandle.Settable(null));
        handle.replayBuffer().capture("streaming-text", "x");
        var runId = handle.runId();

        // Restart with a clock advanced past the TTL so the rehydrated run
        // (createdAt restored from the journal) is sweep-eligible.
        var future = Clock.fixed(Instant.now().plus(Duration.ofHours(2)), ZoneOffset.UTC);
        var revived = new RunRegistry(future, RunRegistry.DEFAULT_TTL, journal);
        assertEquals(1, revived.rehydrate());
        assertTrue(revived.lookup(runId).isPresent(), "served for replay immediately after rehydrate");

        var swept = revived.sweepExpired();
        assertEquals(1, swept, "stale rehydrated run swept after replay window");
        assertFalse(revived.lookup(runId).isPresent());
        assertTrue(journal.loadAll().isEmpty(), "sweep purges the journal entry too");
    }

    @Test
    void clearLeavesJournalIntactForRehydration() {
        var journal = new InMemoryRunJournal();
        var registry = new RunRegistry(Clock.systemUTC(), RunRegistry.DEFAULT_TTL, journal);
        registry.register("agent", "alice", "session-1", new ExecutionHandle.Settable(null));

        registry.clear();
        assertEquals(0, registry.size(), "in-memory runs dropped");
        assertEquals(1, journal.loadAll().size(),
                "durable journal survives clear() — that is the point of durability");
    }

    @Test
    void noopJournalRegistryRehydratesNothing() {
        var registry = new RunRegistry();
        assertSame(RunJournal.NOOP, registry.journal());
        assertEquals(0, registry.rehydrate(), "a NOOP-journal registry has nothing to rehydrate");
    }

    @Test
    void journalFailureDuringCaptureDoesNotBreakTheStream() {
        // A journal whose appendEvent throws must not propagate into the live
        // capture path (Invariant #3 — best-effort persistence).
        RunJournal flaky = new RunJournal() {
            @Override
            public void recordRun(RunRecord run) {
                // ok
            }

            @Override
            public void appendEvent(String runId, RunEvent event) {
                throw new IllegalStateException("backend down");
            }

            @Override
            public void removeRun(String runId) {
                // ok
            }

            @Override
            public List<RunRecord> loadAll() {
                return List.of();
            }

            @Override
            public List<RunEvent> loadEvents(String runId) {
                return List.of();
            }

            @Override
            public boolean durable() {
                return true;
            }
        };
        var registry = new RunRegistry(Clock.systemUTC(), RunRegistry.DEFAULT_TTL, flaky);
        var handle = registry.register("agent", "alice", "session-1", new ExecutionHandle.Settable(null));
        // Must not throw despite the journal blowing up on every event.
        var event = handle.replayBuffer().capture("streaming-text", "still-live");
        assertEquals("still-live", event.payload());
        assertEquals(1, handle.replayBuffer().size(), "in-memory replay still works when the journal fails");
    }
}
