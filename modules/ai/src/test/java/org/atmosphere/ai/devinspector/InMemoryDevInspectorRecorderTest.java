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
package org.atmosphere.ai.devinspector;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDevInspectorRecorderTest {

    private static DevInspectorEntry entry(String id) {
        return new DevInspectorEntry(Instant.now(), id, "m", "prompt-" + id, "resp-" + id,
                List.of(), 1, 1, "OK", "");
    }

    @Test
    void recentReturnsMostRecentFirst() {
        var rec = new InMemoryDevInspectorRecorder();
        rec.record(entry("a"));
        rec.record(entry("b"));
        rec.record(entry("c"));
        var recent = rec.recent(10);
        assertEquals(3, recent.size());
        assertEquals("c", recent.get(0).sessionId(), "newest first");
        assertEquals("a", recent.get(2).sessionId());
    }

    @Test
    void boundedRingBufferEvictsOldest() {
        var rec = new InMemoryDevInspectorRecorder(2);
        rec.record(entry("a"));
        rec.record(entry("b"));
        rec.record(entry("c"));
        assertEquals(2, rec.size());
        assertEquals(List.of("c", "b"),
                rec.recent(10).stream().map(DevInspectorEntry::sessionId).toList());
    }

    @Test
    void recentRespectsLimit() {
        var rec = new InMemoryDevInspectorRecorder();
        for (var i = 0; i < 5; i++) {
            rec.record(entry("e" + i));
        }
        assertEquals(2, rec.recent(2).size());
    }

    @Test
    void previewsAreCappedOnTheEntry() {
        var huge = "x".repeat(DevInspectorEntry.PREVIEW_CAP + 500);
        var e = new DevInspectorEntry(Instant.now(), "s", "m", huge, huge, List.of(), 0, 0, "OK", "");
        assertTrue(e.promptPreview().length() <= DevInspectorEntry.PREVIEW_CAP + 20);
        assertTrue(e.promptPreview().endsWith("…[truncated]"));
    }

    @Test
    void concurrentRecordNeverExceedsCapacity() throws Exception {
        var rec = new InMemoryDevInspectorRecorder(50);
        var threads = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (var t = 0; t < threads; t++) {
            final var tid = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (var i = 0; i < 200; i++) {
                        rec.record(entry(tid + "-" + i));
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(rec.size() <= 50, "the bound must hold under concurrency: " + rec.size());
    }
}
