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

import org.atmosphere.ai.resume.EffectKind;
import org.atmosphere.ai.resume.EffectStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the crash-durable {@link SqliteEffectJournal}: durable round-trip across a
 * reopen, fold ordering by {@code seq}, two-phase commit semantics, bounded
 * per-run effects, terminal-only retention, and the atomic single-writer lease.
 */
class SqliteEffectJournalTest {

    @TempDir
    Path tempDir;

    @Test
    void durableRoundTripAcrossReopen() {
        var path = tempDir.resolve("runs.db");
        try (var journal = new SqliteEffectJournal(path)) {
            journal.appendPending("r1", EffectKind.RUN_INPUT, "seed", "d");
            journal.commit("r1", "seed", "seed-payload");
            journal.appendPending("r1", EffectKind.LLM_ROUND, "round0", "d");
            journal.commit("r1", "round0", "round-payload");
            // Deliberately NOT marked terminal: an in-flight run must survive a restart.
        }
        try (var reopened = new SqliteEffectJournal(path)) {
            assertTrue(reopened.durable());
            var folded = reopened.fold("r1");
            assertEquals(2, folded.size(), "committed effects survive a reopen");
            assertEquals(0L, folded.get(0).seq());
            assertEquals(1L, folded.get(1).seq());
            assertEquals("seed-payload",
                    reopened.lookupCommitted("r1", "seed").orElseThrow().resultPayload());
        }
    }

    @Test
    void foldOrdersBySeqAndTwoPhaseGatesReplayHit() {
        try (var journal = new SqliteEffectJournal(tempDir.resolve("a.db"))) {
            journal.appendPending("r1", EffectKind.RUN_INPUT, "k0", "d");
            journal.appendPending("r1", EffectKind.LLM_ROUND, "k1", "d");
            assertTrue(journal.lookupCommitted("r1", "k1").isEmpty(),
                    "a PENDING effect is not a replay hit");
            journal.commit("r1", "k1", "payload");

            var folded = journal.fold("r1");
            assertEquals(2, folded.size());
            assertEquals(0L, folded.get(0).seq());
            assertEquals(1L, folded.get(1).seq());
            assertEquals(EffectStatus.COMMITTED,
                    journal.lookupCommitted("r1", "k1").orElseThrow().status());
        }
    }

    @Test
    void appendIsIdempotentReturningSameSeq() {
        try (var journal = new SqliteEffectJournal(tempDir.resolve("b.db"))) {
            long first = journal.appendPending("r1", EffectKind.TOOL_CALL, "k", "d");
            long again = journal.appendPending("r1", EffectKind.TOOL_CALL, "k", "d");
            assertEquals(first, again);
            assertEquals(1, journal.fold("r1").size(), "no duplicate row for the same key");
        }
    }

    @Test
    void commitWithoutAppendThrows() {
        try (var journal = new SqliteEffectJournal(tempDir.resolve("c.db"))) {
            assertThrows(IllegalStateException.class,
                    () -> journal.commit("r1", "missing", "x"));
        }
    }

    @Test
    void maxEffectsPerRunOverflowFailsRatherThanDrops() {
        try (var journal = new SqliteEffectJournal(tempDir.resolve("d.db"), 100, 3)) {
            journal.appendPending("r1", EffectKind.TOOL_CALL, "k0", "d");
            journal.appendPending("r1", EffectKind.TOOL_CALL, "k1", "d");
            journal.appendPending("r1", EffectKind.TOOL_CALL, "k2", "d");
            assertThrows(RejectedExecutionException.class,
                    () -> journal.appendPending("r1", EffectKind.TOOL_CALL, "k3", "d"));
            assertEquals(3, journal.fold("r1").size(),
                    "recorded effects are retained, not dropped to make room");
        }
    }

    @Test
    void retentionEvictsOldestTerminalButNeverNonTerminal() {
        var clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (var journal = new SqliteEffectJournal(tempDir.resolve("e.db"), 2, 100, clock)) {
            journal.appendPending("a", EffectKind.RUN_INPUT, "ka", "d");
            clock.advance(Duration.ofSeconds(1));
            journal.appendPending("b", EffectKind.RUN_INPUT, "kb", "d");
            clock.advance(Duration.ofSeconds(1));
            journal.markTerminal("a", EffectStatus.COMMITTED);
            clock.advance(Duration.ofSeconds(1));
            journal.appendPending("c", EffectKind.RUN_INPUT, "kc", "d");

            assertEquals(2, journal.runCount(), "run count bounded at the cap");
            assertTrue(journal.fold("a").isEmpty(), "the oldest terminal run was evicted");
            assertFalse(journal.fold("b").isEmpty(), "non-terminal run 'b' survives over the cap");
            assertFalse(journal.fold("c").isEmpty(), "non-terminal run 'c' survives over the cap");
        }
    }

    @Test
    void leaseIsSingleWriterAndExpires() {
        var clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (var journal = new SqliteEffectJournal(tempDir.resolve("f.db"), 100, 100, clock)) {
            assertTrue(journal.claimLease("r1", "ownerA", Duration.ofMinutes(1)));
            assertFalse(journal.claimLease("r1", "ownerB", Duration.ofMinutes(1)),
                    "a second owner cannot claim a live lease");
            assertTrue(journal.claimLease("r1", "ownerA", Duration.ofMinutes(1)),
                    "the holder may renew its own lease");

            journal.releaseLease("r1", "ownerB"); // wrong owner: no-op
            assertFalse(journal.claimLease("r1", "ownerB", Duration.ofMinutes(1)),
                    "release by a non-owner must not free the lease");

            clock.advance(Duration.ofMinutes(2)); // ownerA's lease expires
            assertTrue(journal.claimLease("r1", "ownerB", Duration.ofMinutes(1)),
                    "an expired lease is reclaimable by a new owner");
        }
    }

    @Test
    void reportsRuntimeTruth() {
        try (var journal = new SqliteEffectJournal(tempDir.resolve("g.db"))) {
            assertTrue(journal.durable(), "SQLite journal is crash-durable");
            assertEquals("sqlite", journal.name());
            assertEquals(SqliteEffectJournal.DEFAULT_MAX_EFFECTS_PER_RUN, journal.maxEffectsPerRun());
        }
    }

    /** A hand-advanced clock so lease expiry and retention ordering are deterministic. */
    private static final class TestClock extends Clock {
        private Instant now;

        private TestClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
