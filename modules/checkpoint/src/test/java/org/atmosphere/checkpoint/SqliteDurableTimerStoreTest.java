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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the crash-durable {@link SqliteDurableTimerStore}: a timer armed before a
 * JVM restart survives a full close/reopen over the same file and re-arms on a
 * fresh {@link DurableTimerService}, plus atomic-claim {@link #remove} idempotence
 * and the bounded-store rejection.
 */
class SqliteDurableTimerStoreTest {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void armedTimerSurvivesRestartAndReArms() {
        var path = tempDir.resolve("timers.db");
        // Service A arms a not-yet-due timer, then the process "crashes": both the
        // service (poller) and the SQLite store are closed.
        try (var store = new SqliteDurableTimerStore(path)) {
            try (var a = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
                a.schedule(new DurableTimer("wake", NOW.plusSeconds(60), "wake",
                        Map.of("reason", "deadline")));
            }
        }
        // A brand-new store instance over the SAME file, wall-clock advanced past
        // fire-at: a fresh service re-arms the persisted timer and fires it.
        var later = Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC);
        var fired = new AtomicInteger();
        try (var reopened = new SqliteDurableTimerStore(path)) {
            assertEquals(1, reopened.timerCount(), "the armed timer survived the restart");
            assertEquals("deadline", reopened.all().get(0).payload().get("reason"),
                    "the payload map round-trips through SQLite");
            try (var b = new DurableTimerService(reopened, Duration.ofSeconds(1), later)) {
                b.onFire("wake", t -> fired.incrementAndGet());
                assertEquals(1, b.poll(), "the overdue timer fires after restart");
                assertEquals(0, reopened.timerCount(), "a fired timer is claimed and removed");
            }
        }
        assertEquals(1, fired.get());
    }

    @Test
    void removeIsAtomicClaimAndIdempotent() {
        try (var store = new SqliteDurableTimerStore(tempDir.resolve("claim.db"))) {
            store.save(DurableTimer.of("t1", NOW, "ping"));
            assertTrue(store.remove("t1"), "the first remove claims the timer");
            assertFalse(store.remove("t1"), "a second remove of a gone timer claims nothing");
            assertFalse(store.remove("never-existed"), "removing an unknown id claims nothing");
        }
    }

    @Test
    void saveByIdReplacesRatherThanDuplicates() {
        try (var store = new SqliteDurableTimerStore(tempDir.resolve("replace.db"))) {
            store.save(DurableTimer.of("t", NOW.plusSeconds(10), "ping"));
            store.save(DurableTimer.of("t", NOW.plusSeconds(999), "ping"));
            assertEquals(1, store.timerCount(), "same id replaces, never duplicates");
            assertEquals(NOW.plusSeconds(999), store.all().get(0).fireAt());
        }
    }

    @Test
    void rejectsNewTimerPastCapButAllowsReSave() {
        try (var store = new SqliteDurableTimerStore(tempDir.resolve("cap.db"), 2)) {
            store.save(DurableTimer.of("a", NOW, "ping"));
            store.save(DurableTimer.of("b", NOW, "ping"));
            assertThrows(RejectedExecutionException.class,
                    () -> store.save(DurableTimer.of("c", NOW, "ping")),
                    "a genuinely new timer past the cap is rejected, not silently dropped");
            // Re-saving an existing id is an update and must still be allowed at cap.
            store.save(DurableTimer.of("a", NOW.plusSeconds(5), "ping"));
            assertEquals(2, store.timerCount());
        }
    }

    @Test
    void reportsRuntimeTruth() {
        try (var store = new SqliteDurableTimerStore(tempDir.resolve("truth.db"))) {
            assertEquals("sqlite", store.name());
            assertEquals(SqliteDurableTimerStore.DEFAULT_MAX_TIMERS, store.maxTimers());
        }
    }
}
