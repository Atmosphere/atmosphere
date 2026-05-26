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
package org.atmosphere.coordinator.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCoordinationJournalTest {

    @Test
    void recordedEventsPersistAcrossRestart(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");

        var first = new FileCoordinationJournal(file);
        first.start();
        try {
            var startedId = first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationStarted("c1", "test", Instant.now())));
            first.recordEnveloped(EventEnvelope.childOf(startedId,
                    new CoordinationEvent.AgentDispatched("c1", "alpha", "do",
                            Map.of("k", "v"), Instant.now())));
        } finally {
            first.stop();
        }

        var second = new FileCoordinationJournal(file);
        second.start();
        try {
            var envelopes = second.retrieveEnveloped("c1");
            assertEquals(2, envelopes.size(), "both events should be replayed from disk");
            assertTrue(envelopes.get(0).event() instanceof CoordinationEvent.CoordinationStarted);
            assertTrue(envelopes.get(1).event() instanceof CoordinationEvent.AgentDispatched);
            assertEquals(envelopes.get(0).eventId(), envelopes.get(1).parentEventId(),
                    "parent/child lineage must survive ser/deser");
        } finally {
            second.stop();
        }
    }

    @Test
    void malformedLineIsSkippedOnReplay(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");

        var first = new FileCoordinationJournal(file);
        first.start();
        try {
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationStarted("c2", "test", Instant.now())));
        } finally {
            first.stop();
        }

        // Simulate JVM-kill-during-append by appending a partial JSON line
        Files.writeString(file, "{\"eventId\":\"x\",\"event\":{\"@type\":\"Coordin",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        var second = new FileCoordinationJournal(file);
        second.start();
        try {
            // The clean line must still replay; the truncated line is logged and skipped
            assertEquals(1, second.retrieveEnveloped("c2").size(),
                    "good line survives, truncated line is skipped (crash-safety contract)");
        } finally {
            second.stop();
        }
    }

    @Test
    void inspectorRejectionSkipsPersistAndIndex(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var journal = new FileCoordinationJournal(file);
        journal.start();
        try {
            journal.inspector(event -> !(event instanceof CoordinationEvent.AgentDispatched));
            journal.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationStarted("c3", "test", Instant.now())));
            journal.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentDispatched("c3", "alpha", "do",
                            Map.of(), Instant.now())));
        } finally {
            journal.stop();
        }
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), "rejected AgentDispatched must not be written");
        assertTrue(lines.get(0).contains("\"@type\":\"CoordinationStarted\""));
    }

    @Test
    void concurrentWritesDoNotLoseEvents(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var journal = new FileCoordinationJournal(file);
        journal.start();
        try {
            int threads = 8;
            int perThread = 50;
            var ready = new CountDownLatch(threads);
            var go = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            var written = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                Thread.ofVirtual().start(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = 0; i < perThread; i++) {
                            journal.recordEnveloped(EventEnvelope.root(
                                    new CoordinationEvent.AgentDispatched(
                                            "c-" + threadIdx, "alpha", "do",
                                            Map.of("i", i), Instant.now())));
                            written.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(threads * perThread, written.get());
        } finally {
            journal.stop();
        }

        var lineCount = Files.readAllLines(file, StandardCharsets.UTF_8).size();
        assertEquals(8 * 50, lineCount,
                "every concurrent write must produce exactly one NDJSON line");
    }

    @Test
    void allEventVariantsRoundTrip(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var now = Instant.now();

        var first = new FileCoordinationJournal(file);
        first.start();
        try {
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationStarted("cAll", "co", now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentDispatched("cAll", "alpha", "do",
                            Map.of("k", "v"), now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentCompleted("cAll", "alpha", "do",
                            "ok", Duration.ofMillis(10), now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentFailed("cAll", "alpha", "do",
                            "err", Duration.ofMillis(10), now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentEvaluated("cAll", "alpha", "rubric",
                            0.8, true, "good", now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentHandoff("cAll", "alpha", "beta",
                            "domain expertise", now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.RouteEvaluated("cAll", "alpha", 0, "beta",
                            true, now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentActivityChanged("cAll", "alpha",
                            "thinking", "step 1", now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CircuitStateChanged("cAll", "alpha",
                            "CLOSED", "OPEN", now)));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationCompleted("cAll",
                            Duration.ofMillis(100), 1, now)));
        } finally {
            first.stop();
        }

        var second = new FileCoordinationJournal(file);
        second.start();
        try {
            var events = second.retrieve("cAll");
            assertEquals(10, events.size(), "all 10 non-commitment event variants must round-trip");
        } finally {
            second.stop();
        }
    }

    @Test
    void recordViaLegacyApiWrapsAsRootEnvelope(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var journal = new FileCoordinationJournal(file);
        journal.start();
        try {
            journal.record(new CoordinationEvent.CoordinationStarted("cLegacy", "test", Instant.now()));
        } finally {
            journal.stop();
        }

        var replayed = new FileCoordinationJournal(file);
        replayed.start();
        try {
            var envelopes = replayed.retrieveEnveloped("cLegacy");
            assertEquals(1, envelopes.size());
            assertNull(envelopes.get(0).parentEventId(),
                    "legacy record() must persist as a root envelope (no parent)");
            assertNotNull(envelopes.get(0).eventId());
        } finally {
            replayed.stop();
        }
    }

    @Test
    void startIsIdempotent(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var journal = new FileCoordinationJournal(file);
        journal.start();
        journal.start();
        try {
            journal.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationStarted("cIdem", "test", Instant.now())));
        } finally {
            journal.stop();
        }
        assertEquals(1, Files.readAllLines(file, StandardCharsets.UTF_8).size());
    }

    @Test
    void retrieveOnEmptyCoordinationReturnsEmpty(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var journal = new FileCoordinationJournal(file);
        journal.start();
        try {
            assertTrue(journal.retrieve("no-such").isEmpty());
            assertTrue(journal.retrieveEnveloped("no-such").isEmpty());
        } finally {
            journal.stop();
        }
    }

    @Test
    void persistedEnvelopesPreserveEventIdsExactly(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var first = new FileCoordinationJournal(file);
        first.start();
        var explicitId = UUID.randomUUID().toString();
        try {
            first.recordEnveloped(new EventEnvelope(explicitId, null,
                    new CoordinationEvent.CoordinationStarted("cId", "test", Instant.now())));
        } finally {
            first.stop();
        }

        var second = new FileCoordinationJournal(file);
        second.start();
        try {
            var envelopes = second.retrieveEnveloped("cId");
            assertEquals(1, envelopes.size());
            assertEquals(explicitId, envelopes.get(0).eventId(),
                    "explicit eventId must survive ser/deser bit-for-bit");
        } finally {
            second.stop();
        }
    }

    @Test
    void queryByCoordinationIdWorksAfterReplay(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var first = new FileCoordinationJournal(file);
        first.start();
        try {
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationStarted("cQ1", "test", Instant.now())));
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationStarted("cQ2", "test", Instant.now())));
        } finally {
            first.stop();
        }

        var second = new FileCoordinationJournal(file);
        second.start();
        try {
            List<CoordinationEvent> q1 = second.query(
                    CoordinationQuery.forCoordination("cQ1"));
            assertEquals(1, q1.size());
            assertTrue(q1.get(0) instanceof CoordinationEvent.CoordinationStarted started
                    && "cQ1".equals(started.coordinationId()));
        } finally {
            second.stop();
        }
    }

    @Test
    void inspectorAppliedAfterRestartOnNewlyRecordedEvents(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("journal.ndjson");
        var first = new FileCoordinationJournal(file);
        first.start();
        try {
            first.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.CoordinationStarted("cI", "test", Instant.now())));
        } finally {
            first.stop();
        }

        var second = new FileCoordinationJournal(file);
        second.start();
        try {
            // Reject everything from here on
            second.inspector(event -> false);
            second.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentDispatched("cI", "alpha", "do",
                            Map.of(), Instant.now())));
        } finally {
            second.stop();
        }

        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), "post-restart inspector must veto append");
        assertFalse(lines.get(0).contains("AgentDispatched"));
    }
}
