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
package org.atmosphere.ai.governance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditSink} SPI: fan-out from {@link GovernanceDecisionLog#record}
 * hits every registered sink, failures are isolated, and
 * {@link AsyncAuditSink} keeps the admission path off the delegate thread
 * while honouring the Backpressure invariant (drop on queue full).
 */
class AuditSinkTest {

    @AfterEach
    void tearDown() {
        GovernanceDecisionLog.reset();
    }

    @Test
    void recordFansOutToAllSinks() {
        var log = GovernanceDecisionLog.install(10);
        var a = new CapturingSink("a");
        var b = new CapturingSink("b");
        log.addSink(a).addSink(b);

        log.record(sampleEntry("admit"));
        log.record(sampleEntry("deny"));

        assertEquals(2, a.entries.size(), "sink A received both entries: " + a.entries);
        assertEquals(2, b.entries.size(), "sink B received both entries: " + b.entries);
    }

    @Test
    void sinkFailureIsIsolatedFromRingBufferAndOtherSinks() {
        var log = GovernanceDecisionLog.install(10);
        var healthy = new CapturingSink("healthy");
        log.addSink(e -> {
            throw new RuntimeException("boom");
        }).addSink(healthy);

        log.record(sampleEntry("admit"));
        log.record(sampleEntry("deny"));

        assertEquals(2, log.recent(5).size(),
                "ring-buffer writes must succeed regardless of sink failures");
        assertEquals(2, healthy.entries.size(),
                "downstream healthy sink must still receive every entry");
    }

    @Test
    void removeSinkStopsFanOut() {
        var log = GovernanceDecisionLog.install(10);
        var sink = new CapturingSink("a");
        log.addSink(sink);
        log.record(sampleEntry("admit"));
        assertTrue(log.removeSink(sink));
        log.record(sampleEntry("deny"));
        assertEquals(1, sink.entries.size());
    }

    @Test
    void resetClosesRegisteredSinks() {
        var log = GovernanceDecisionLog.install(5);
        var closed = new AtomicInteger();
        log.addSink(new AuditSink() {
            @Override public void write(AuditEntry e) { }
            @Override public void close() { closed.incrementAndGet(); }
        });
        GovernanceDecisionLog.reset();
        assertEquals(1, closed.get(),
                "reset() must close all sinks so test fixtures don't leak threads");
    }

    @Test
    void asyncSinkDrainsToDelegateOffAdmissionThread() throws Exception {
        var delegateThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var latch = new CountDownLatch(3);
        AuditSink delegate = entry -> {
            delegateThread.set(Thread.currentThread());
            latch.countDown();
        };
        var async = new AsyncAuditSink(delegate, 100);
        try {
            var admissionThread = Thread.currentThread();
            async.write(sampleEntry("admit"));
            async.write(sampleEntry("deny"));
            async.write(sampleEntry("transform"));
            assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "delegate must receive all 3 entries within the deadline");
            assertFalse(admissionThread.equals(delegateThread.get()),
                    "delegate must not run on the admission thread — that's the whole point");
        } finally {
            async.close();
        }
    }

    @Test
    void asyncSinkDropsOnQueueFull() throws Exception {
        // Tight queue + a delegate that parks forever forces overflow within
        // a handful of writes. We verify drops are counted AND the async
        // sink never blocks the caller.
        var delegateEntered = new CountDownLatch(1);
        var releaseDelegate = new CountDownLatch(1);
        AuditSink blocker = entry -> {
            delegateEntered.countDown();
            try {
                releaseDelegate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var async = new AsyncAuditSink(blocker, 2);
        try {
            // First write is drained by the worker; blocks delegate forever.
            async.write(sampleEntry("one"));
            assertTrue(delegateEntered.await(5, TimeUnit.SECONDS));
            // Now fill the queue + 10 more that MUST be dropped.
            for (int i = 0; i < 12; i++) {
                async.write(sampleEntry("drop-" + i));
            }
            assertTrue(async.droppedCount() > 0,
                    "writes past queue capacity must be counted as dropped");
        } finally {
            releaseDelegate.countDown();
            async.close();
        }
    }

    private static AuditEntry sampleEntry(String decision) {
        return new AuditEntry(
                Instant.now(),
                "sink-test",
                "code:test",
                "1.0",
                decision,
                "",
                Map.of("phase", "pre_admission"),
                0.5);
    }

    private static final class CapturingSink implements AuditSink {
        final String name;
        final ConcurrentLinkedQueue<AuditEntry> entries = new ConcurrentLinkedQueue<>();
        CapturingSink(String name) { this.name = name; }
        @Override public void write(AuditEntry entry) { entries.add(entry); }
        @Override public String name() { return name; }
    }
}
