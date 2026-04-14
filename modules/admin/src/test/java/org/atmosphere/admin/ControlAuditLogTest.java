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
package org.atmosphere.admin;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ControlAuditLogTest {

    @Test
    public void testRecordAndRetrieve() {
        var log = new ControlAuditLog(100);
        log.record("admin", "broadcast", "/chat", true, "sent");

        var entries = log.entries();
        assertEquals(1, entries.size());

        var entry = entries.getFirst();
        assertEquals("admin", entry.principal());
        assertEquals("broadcast", entry.action());
        assertEquals("/chat", entry.target());
        assertTrue(entry.success());
        assertEquals("sent", entry.message());
        assertNotNull(entry.timestamp());
    }

    @Test
    public void testNullPrincipalBecomesAnonymous() {
        var log = new ControlAuditLog(10);
        log.record(null, "disconnect", "uuid-1", true, null);

        var entry = log.entries().getFirst();
        assertEquals("anonymous", entry.principal());
    }

    @Test
    public void testRingBufferEvictsOldest() {
        var log = new ControlAuditLog(3);
        log.record("a", "action1", "t1", true, null);
        log.record("a", "action2", "t2", true, null);
        log.record("a", "action3", "t3", true, null);
        log.record("a", "action4", "t4", true, null);

        var entries = log.entries();
        assertEquals(3, entries.size());
        // Oldest (action1) should have been evicted
        assertEquals("action2", entries.get(0).action());
        assertEquals("action3", entries.get(1).action());
        assertEquals("action4", entries.get(2).action());
    }

    @Test
    public void testEntriesWithLimit() {
        var log = new ControlAuditLog(100);
        log.record("a", "action1", "t1", true, null);
        log.record("a", "action2", "t2", false, null);
        log.record("a", "action3", "t3", true, null);

        var limited = log.entries(2);
        assertEquals(2, limited.size());
        // Should return the most recent 2
        assertEquals("action2", limited.get(0).action());
        assertEquals("action3", limited.get(1).action());
    }

    @Test
    public void testEntriesWithLimitLargerThanSize() {
        var log = new ControlAuditLog(100);
        log.record("a", "action1", "t1", true, null);

        var limited = log.entries(10);
        assertEquals(1, limited.size());
    }

    @Test
    public void testEmptyLog() {
        var log = new ControlAuditLog(10);
        assertTrue(log.entries().isEmpty());
        assertTrue(log.entries(5).isEmpty());
    }

    @Test
    public void testFailedAction() {
        var log = new ControlAuditLog(10);
        log.record("admin", "destroy", "/missing", false, null);

        var entry = log.entries().getFirst();
        assertFalse(entry.success());
    }

    @Test
    public void testEntriesReturnsImmutableCopy() {
        var log = new ControlAuditLog(10);
        log.record("a", "action1", "t1", true, null);

        var entries = log.entries();
        // Adding more records should not change the previously returned list
        log.record("a", "action2", "t2", true, null);
        assertEquals(1, entries.size());
    }

    @Test
    public void testConcurrentRecording() throws InterruptedException {
        var log = new ControlAuditLog(1000);
        int threadCount = 8;
        int recordsPerThread = 100;
        var latch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < recordsPerThread; i++) {
                            log.record("thread-" + threadId, "action", "t" + i, true, null);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } finally {
            executor.shutdown();
        }

        // All records should be present (capacity 1000 > 800 total)
        assertEquals(threadCount * recordsPerThread, log.entries().size());
    }

    @Test
    public void testConcurrentRecordingWithEviction() throws InterruptedException {
        int maxEntries = 50;
        var log = new ControlAuditLog(maxEntries);
        int threadCount = 4;
        int recordsPerThread = 100;
        var latch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < recordsPerThread; i++) {
                            log.record("thread-" + threadId, "action", "t" + i, true, null);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } finally {
            executor.shutdown();
        }

        // Size should not exceed maxEntries (with some tolerance for concurrency)
        assertTrue(log.entries().size() <= maxEntries + threadCount,
                "Size " + log.entries().size() + " should be <= " + (maxEntries + threadCount));
    }

    @Test
    public void testMaxEntriesOfOne() {
        var log = new ControlAuditLog(1);
        log.record("a", "first", "t1", true, null);
        log.record("a", "second", "t2", true, null);

        var entries = log.entries();
        assertEquals(1, entries.size());
        assertEquals("second", entries.getFirst().action());
    }

    @Test
    public void testTimestampOrdering() {
        var log = new ControlAuditLog(10);
        log.record("a", "first", "t1", true, null);
        log.record("a", "second", "t2", true, null);

        var entries = log.entries();
        assertTrue(entries.get(0).timestamp().compareTo(entries.get(1).timestamp()) <= 0,
                "Entries should be ordered by timestamp (oldest first)");
    }
}
