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
import org.atmosphere.ai.resume.InMemoryEffectJournal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#26): {@link DurableTimerService} advertised itself as
 * the approval-expiry scheduler while nothing armed timers — approvals
 * expired only when someone looked at them. {@link DurableApprovalExpiry}
 * is now the production consumer; these tests pin its contract with an
 * injected clock and deterministic {@code poll()} calls.
 */
class DurableApprovalExpiryTest {

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC);

    private static EffectStatus statusOf(InMemoryEffectJournal journal, String runId, String key) {
        return journal.fold(runId).stream()
                .filter(r -> r.idempotencyKey().equals(key))
                .findFirst().orElseThrow().status();
    }

    @Test
    void firedTimerMarksThePendingApprovalFailed() {
        var journal = new InMemoryEffectJournal(10, 10);
        var store = new InMemoryDurableTimerStore();
        try (var service = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            var expiry = new DurableApprovalExpiry(service, journal);
            journal.appendPending("run-1", EffectKind.APPROVAL, "appr-key", "digest");
            expiry.arm("run-1", "appr-key", "deploy", NOW.plusSeconds(60));

            assertEquals(1, service.poll(), "the overdue approval timer must fire");
            assertEquals(EffectStatus.FAILED, statusOf(journal, "run-1", "appr-key"),
                    "an undecided approval past its deadline must be auto-failed");
        }
    }

    @Test
    void committedDecisionIsNeverDemotedByAFiringTimer() {
        var journal = new InMemoryEffectJournal(10, 10);
        var store = new InMemoryDurableTimerStore();
        try (var service = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            var expiry = new DurableApprovalExpiry(service, journal);
            journal.appendPending("run-1", EffectKind.APPROVAL, "appr-key", "digest");
            expiry.arm("run-1", "appr-key", "deploy", NOW.plusSeconds(60));
            journal.commit("run-1", "appr-key", "{\"outcome\":\"APPROVED\"}");

            service.poll();

            assertEquals(EffectStatus.COMMITTED, statusOf(journal, "run-1", "appr-key"),
                    "the human decision must win the timer race");
        }
    }

    @Test
    void cancelDisarmsTheBackstop() {
        var journal = new InMemoryEffectJournal(10, 10);
        var store = new InMemoryDurableTimerStore();
        try (var service = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            var expiry = new DurableApprovalExpiry(service, journal);
            journal.appendPending("run-1", EffectKind.APPROVAL, "appr-key", "digest");
            expiry.arm("run-1", "appr-key", "deploy", NOW.plusSeconds(60));
            expiry.cancel("run-1", "appr-key");

            assertEquals(0, service.poll(), "a cancelled timer must not fire");
            assertEquals(EffectStatus.PENDING, statusOf(journal, "run-1", "appr-key"));
        }
    }

    @Test
    void timerArmedBeforeARestartFiresOnTheNewService() {
        var journal = new InMemoryEffectJournal(10, 10);
        var store = new InMemoryDurableTimerStore();
        journal.appendPending("run-1", EffectKind.APPROVAL, "appr-key", "digest");
        try (var first = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            new DurableApprovalExpiry(first, journal)
                    .arm("run-1", "appr-key", "deploy", NOW.plusSeconds(60));
            // process "dies" before the deadline: no poll on this service
        }
        try (var second = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            new DurableApprovalExpiry(second, journal);
            assertEquals(1, second.poll(),
                    "a timer armed before the restart must fire from the shared store");
            assertEquals(EffectStatus.FAILED, statusOf(journal, "run-1", "appr-key"));
        }
    }

    @Test
    void inMemoryJournalMarkFailedRefusesToDemoteACommittedEffect() {
        var journal = new InMemoryEffectJournal(10, 10);
        journal.appendPending("run-1", EffectKind.APPROVAL, "appr-key", "digest");
        journal.commit("run-1", "appr-key", "{\"outcome\":\"APPROVED\"}");

        journal.markFailed("run-1", "appr-key", "late expiry");

        assertEquals(EffectStatus.COMMITTED, statusOf(journal, "run-1", "appr-key"));
        assertTrue(journal.lookupCommitted("run-1", "appr-key").isPresent());
    }
}
