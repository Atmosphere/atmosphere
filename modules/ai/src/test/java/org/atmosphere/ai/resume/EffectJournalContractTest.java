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
 * Pins the {@link EffectJournal} contract against the in-memory reference impl:
 * two-phase append/commit, fold ordering by {@code seq}, {@code lookupCommitted}
 * semantics, bounded per-run effects (overflow fails, never drops), NOOP
 * behaviour, and the single-writer lease.
 */
class EffectJournalContractTest {

    private final InMemoryEffectJournal journal = new InMemoryEffectJournal();

    @Test
    void appendThenCommitMakesItAReplayHit() {
        journal.appendPending("r1", EffectKind.TOOL_CALL, "k1", "digest");
        assertTrue(journal.lookupCommitted("r1", "k1").isEmpty(),
                "a PENDING effect is not a replay hit");

        journal.commit("r1", "k1", "result-payload");
        var hit = journal.lookupCommitted("r1", "k1");
        assertTrue(hit.isPresent(), "a COMMITTED effect is a replay hit");
        assertEquals("result-payload", hit.get().resultPayload());
        assertEquals(EffectStatus.COMMITTED, hit.get().status());
    }

    @Test
    void foldOrdersBySeqMonotonically() {
        long s0 = journal.appendPending("r1", EffectKind.RUN_INPUT, "seed", "d");
        long s1 = journal.appendPending("r1", EffectKind.LLM_ROUND, "round0", "d");
        long s2 = journal.appendPending("r1", EffectKind.TOOL_CALL, "tool0", "d");

        var folded = journal.fold("r1");
        assertEquals(3, folded.size());
        assertEquals(s0, folded.get(0).seq());
        assertEquals(s1, folded.get(1).seq());
        assertEquals(s2, folded.get(2).seq());
        assertTrue(s0 < s1 && s1 < s2, "seq is monotonically increasing per run");
        assertEquals(EffectKind.RUN_INPUT, folded.get(0).kind());
    }

    @Test
    void appendIsIdempotentReturningSameSeq() {
        long first = journal.appendPending("r1", EffectKind.TOOL_CALL, "k1", "d");
        long again = journal.appendPending("r1", EffectKind.TOOL_CALL, "k1", "d");
        assertEquals(first, again, "re-appending the same key returns the existing seq");
        assertEquals(1, journal.fold("r1").size(), "no duplicate row for the same key");
    }

    @Test
    void commitWithoutAppendThrows() {
        assertThrows(IllegalStateException.class,
                () -> journal.commit("r1", "never-appended", "x"));
    }

    @Test
    void markFailedIsNotAReplayHitAndStaysLenient() {
        journal.appendPending("r1", EffectKind.TOOL_CALL, "k1", "d");
        journal.markFailed("r1", "k1", "boom");
        assertTrue(journal.lookupCommitted("r1", "k1").isEmpty(),
                "a FAILED effect is not a replay hit (re-runs on resume)");
        // Lenient: marking an unknown run/key failed must not mask the original error.
        journal.markFailed("ghost", "k", "ignored");
        journal.markFailed("r1", "unknown-key", "ignored");
    }

    @Test
    void maxEffectsPerRunOverflowFailsRatherThanDrops() {
        var capped = new InMemoryEffectJournal(100, 3);
        capped.appendPending("r1", EffectKind.TOOL_CALL, "k0", "d");
        capped.appendPending("r1", EffectKind.TOOL_CALL, "k1", "d");
        capped.appendPending("r1", EffectKind.TOOL_CALL, "k2", "d");
        assertThrows(RejectedExecutionException.class,
                () -> capped.appendPending("r1", EffectKind.TOOL_CALL, "k3", "d"),
                "exceeding the per-run cap fails the run, it does not silently drop");
        assertEquals(3, capped.fold("r1").size(),
                "the already-recorded effects are retained, not dropped to make room");
    }

    @Test
    void noopRecordsNothing() {
        var noop = EffectJournal.NOOP;
        assertEquals(0L, noop.appendPending("r1", EffectKind.TOOL_CALL, "k", "d"));
        noop.commit("r1", "k", "x");
        assertTrue(noop.lookupCommitted("r1", "k").isEmpty());
        assertTrue(noop.fold("r1").isEmpty());
        assertFalse(noop.durable());
        assertEquals("noop", noop.name());
        assertTrue(noop.claimLease("r1", "owner", Duration.ofMinutes(1)),
                "NOOP grants the lease so a non-durable drive always proceeds");
    }

    @Test
    void leaseIsSingleWriter() {
        assertTrue(journal.claimLease("r1", "ownerA", Duration.ofMinutes(5)));
        assertFalse(journal.claimLease("r1", "ownerB", Duration.ofMinutes(5)),
                "a second owner cannot claim a live lease");
        assertTrue(journal.claimLease("r1", "ownerA", Duration.ofMinutes(5)),
                "the holder may re-claim (renew) its own lease");

        journal.releaseLease("r1", "ownerB"); // wrong owner: no-op
        assertFalse(journal.claimLease("r1", "ownerB", Duration.ofMinutes(5)),
                "release by a non-owner must not free the lease");
        journal.releaseLease("r1", "ownerA");
        assertTrue(journal.claimLease("r1", "ownerB", Duration.ofMinutes(5)),
                "after the owner releases, another owner may claim");
    }

    @Test
    void expiredLeaseIsReclaimable() {
        var clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        var leased = new InMemoryEffectJournal(100, 100, clock);
        assertTrue(leased.claimLease("r1", "ownerA", Duration.ofMinutes(1)));
        assertFalse(leased.claimLease("r1", "ownerB", Duration.ofMinutes(1)));

        clock.advance(Duration.ofMinutes(2)); // ownerA's lease has expired
        assertTrue(leased.claimLease("r1", "ownerB", Duration.ofMinutes(1)),
                "an expired lease is reclaimable by a new owner");
    }

    @Test
    void retentionEvictsOldestTerminalButNeverNonTerminal() {
        var clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        var bounded = new InMemoryEffectJournal(2, 100, clock);

        bounded.appendPending("a", EffectKind.RUN_INPUT, "ka", "d");
        clock.advance(Duration.ofSeconds(1));
        bounded.appendPending("b", EffectKind.RUN_INPUT, "kb", "d");
        // Over cap but both in-flight: a non-terminal run is never evicted.
        clock.advance(Duration.ofSeconds(1));
        bounded.appendPending("c", EffectKind.RUN_INPUT, "kc", "d");
        assertEquals(3, bounded.runCount(),
                "no terminal run exists, so nothing is evicted even over the cap");

        // Mark the oldest terminal; the next over-cap check evicts only it.
        bounded.markTerminal("a", EffectStatus.COMMITTED);
        assertEquals(2, bounded.runCount());
        assertTrue(bounded.fold("a").isEmpty(), "the oldest terminal run was evicted");
        assertFalse(bounded.fold("b").isEmpty(), "non-terminal run 'b' survives");
        assertFalse(bounded.fold("c").isEmpty(), "non-terminal run 'c' survives");
    }

    @Test
    void reportsRuntimeTruth() {
        assertFalse(journal.durable(), "in-memory journal must report not-durable");
        assertEquals("in-memory", journal.name());
        assertEquals(InMemoryEffectJournal.DEFAULT_MAX_EFFECTS_PER_RUN, journal.maxEffectsPerRun());
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
