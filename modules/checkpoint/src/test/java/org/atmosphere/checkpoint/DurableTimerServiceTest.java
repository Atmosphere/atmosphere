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

import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.PendingApproval;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wall-clock durable timers (P1.7): fire, re-arm-on-restart, exactly-once, auto-reject. */
class DurableTimerServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void firesDueTimerExactlyOnce() {
        var store = new InMemoryDurableTimerStore();
        var fired = new AtomicInteger();
        try (var service = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            service.onFire("ping", t -> fired.incrementAndGet());
            service.schedule(DurableTimer.of("t1", NOW.minusSeconds(1), "ping"));

            assertEquals(1, service.poll(), "the due timer fires once");
            assertEquals(0, service.poll(), "a fired timer is removed and never fires again");
            assertEquals(1, fired.get());
        }
    }

    @Test
    void doesNotFireBeforeDue() {
        var store = new InMemoryDurableTimerStore();
        var fired = new AtomicInteger();
        try (var service = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            service.onFire("ping", t -> fired.incrementAndGet());
            service.schedule(DurableTimer.of("future", NOW.plusSeconds(3600), "ping"));
            assertEquals(0, service.poll());
            assertEquals(0, fired.get());
        }
    }

    @Test
    void reArmsAnOverdueTimerAfterRestart() {
        // Service A arms a timer, then "crashes" (closed) before it was due.
        var store = new InMemoryDurableTimerStore();
        try (var a = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            a.schedule(DurableTimer.of("wake", NOW.plusSeconds(60), "wake"));
        }
        // Time has advanced past fire-at; a fresh service over the same store
        // re-arms and fires the now-overdue timer (restart survival).
        var later = Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC);
        var fired = new AtomicInteger();
        try (var b = new DurableTimerService(store, Duration.ofSeconds(1), later)) {
            b.onFire("wake", t -> fired.incrementAndGet());
            assertEquals(1, b.poll(), "the overdue timer fires after restart");
            assertEquals(1, fired.get());
        }
    }

    @Test
    void claimMakesFiringExactlyOnceAcrossTwoServices() {
        var store = new InMemoryDurableTimerStore();
        var fired = new AtomicInteger();
        store.save(DurableTimer.of("shared", NOW.minusSeconds(1), "ping"));
        try (var a = new DurableTimerService(store, Duration.ofSeconds(1), FIXED);
             var b = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            a.onFire("ping", t -> fired.incrementAndGet());
            b.onFire("ping", t -> fired.incrementAndGet());
            var total = a.poll() + b.poll();
            assertEquals(1, total, "exactly one service claims and fires the shared timer");
            assertEquals(1, fired.get());
        }
    }

    @Test
    void callbackFailureIsContainedAndTimerStillConsumed() {
        var store = new InMemoryDurableTimerStore();
        try (var service = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            service.onFire("boom", t -> {
                throw new RuntimeException("callback blew up");
            });
            service.schedule(DurableTimer.of("t", NOW, "boom"));
            assertEquals(1, service.poll(), "poll must not propagate a callback failure");
            assertEquals(0, service.poll(), "the timer is still consumed (fire-once)");
        }
    }

    @Test
    void autoRejectsAnApprovalWhenTheTimerFires() throws Exception {
        var registry = new ApprovalRegistry();
        var future = registry.register(new PendingApproval(
                "apr_late", "delete_db", Map.of(), "Approve?", "conv-1",
                NOW.plusSeconds(259200))); // 72h

        var store = new InMemoryDurableTimerStore();
        try (var service = new DurableTimerService(store, Duration.ofSeconds(1), FIXED)) {
            service.onFire("approval-auto-reject", t ->
                    registry.resolve(ApprovalRegistry.APPROVAL_PREFIX + t.payload().get("approvalId") + "/deny"));
            service.schedule(new DurableTimer("auto-reject-apr_late", NOW.minusSeconds(1),
                    "approval-auto-reject", Map.of("approvalId", "apr_late")));

            service.poll();
            assertTrue(future.isDone(), "the timer must resolve the pending approval");
            assertFalse(future.get(), "an auto-rejected approval resolves as denied");
        }
    }
}
