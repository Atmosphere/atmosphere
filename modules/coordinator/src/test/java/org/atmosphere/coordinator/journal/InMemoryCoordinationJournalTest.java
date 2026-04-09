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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCoordinationJournalTest {

    private InMemoryCoordinationJournal journal;

    @BeforeEach
    void setUp() {
        journal = new InMemoryCoordinationJournal();
        journal.start();
    }

    @Test
    void recordAndRetrieve() {
        var event = new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now());
        journal.record(event);

        var events = journal.retrieve("c1");
        assertEquals(1, events.size());
        assertEquals(event, events.getFirst());
    }

    @Test
    void retrieveReturnsEmptyForUnknownId() {
        assertTrue(journal.retrieve("unknown").isEmpty());
    }

    @Test
    void queryByCoordinationId() {
        journal.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        journal.record(new CoordinationEvent.CoordinationStarted("c2", "ceo", Instant.now()));

        var results = journal.query(CoordinationQuery.forCoordination("c1"));
        assertEquals(1, results.size());
        assertEquals("c1", results.getFirst().coordinationId());
    }

    @Test
    void queryByAgentName() {
        journal.record(new CoordinationEvent.AgentCompleted(
                "c1", "weather", "forecast", "Sunny", Duration.ZERO, Instant.now()));
        journal.record(new CoordinationEvent.AgentCompleted(
                "c1", "news", "headlines", "No news", Duration.ZERO, Instant.now()));

        var results = journal.query(CoordinationQuery.forAgent("weather"));
        assertEquals(1, results.size());
    }

    @Test
    void queryByTimeRange() {
        var before = Instant.now().minusSeconds(10);
        var event1 = new CoordinationEvent.CoordinationStarted("c1", "ceo", before);
        var event2 = new CoordinationEvent.CoordinationStarted("c2", "ceo", Instant.now());
        journal.record(event1);
        journal.record(event2);

        var results = journal.query(new CoordinationQuery(
                null, null, Instant.now().minusSeconds(5), null, 0));
        assertEquals(1, results.size());
        assertEquals("c2", results.getFirst().coordinationId());
    }

    @Test
    void queryWithLimit() {
        for (int i = 0; i < 10; i++) {
            journal.record(new CoordinationEvent.CoordinationStarted(
                    "c" + i, "ceo", Instant.now()));
        }

        var results = journal.query(new CoordinationQuery(null, null, null, null, 3));
        assertEquals(3, results.size());
    }

    @Test
    void queryAllReturnsEverything() {
        journal.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        journal.record(new CoordinationEvent.CoordinationStarted("c2", "ceo", Instant.now()));

        var results = journal.query(CoordinationQuery.all());
        assertEquals(2, results.size());
    }

    @Test
    void inspectorFiltersEvents() {
        // Only record AgentCompleted events
        journal.inspector(event -> event instanceof CoordinationEvent.AgentCompleted);

        journal.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        journal.record(new CoordinationEvent.AgentCompleted(
                "c1", "weather", "forecast", "Sunny", Duration.ZERO, Instant.now()));

        var events = journal.retrieve("c1");
        assertEquals(1, events.size());
        assertTrue(events.getFirst() instanceof CoordinationEvent.AgentCompleted);
    }

    @Test
    void stopClearsStore() {
        journal.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        journal.stop();
        assertTrue(journal.retrieve("c1").isEmpty());
    }

    @Test
    void noopDiscards() {
        var noop = CoordinationJournal.NOOP;
        noop.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        assertTrue(noop.retrieve("c1").isEmpty());
        assertTrue(noop.query(CoordinationQuery.all()).isEmpty());
    }

    @Test
    void agentFailedMatchesAgentQuery() {
        journal.record(new CoordinationEvent.AgentFailed(
                "c1", "weather", "forecast", "timeout", Duration.ZERO, Instant.now()));

        var results = journal.query(CoordinationQuery.forAgent("weather"));
        assertEquals(1, results.size());
    }

    @Test
    void agentEvaluatedMatchesAgentQuery() {
        journal.record(new CoordinationEvent.AgentEvaluated(
                "c1", "weather", "quality", 0.9, true, "solid", Instant.now()));

        var results = journal.query(CoordinationQuery.forAgent("weather"));
        assertEquals(1, results.size());
    }

    @Test
    void evictsOldestWhenMaxCoordinationsExceeded() {
        var bounded = new InMemoryCoordinationJournal(3);
        bounded.start();

        bounded.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.CoordinationStarted("c2", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.CoordinationStarted("c3", "ceo", Instant.now()));

        // All 3 present
        assertEquals(1, bounded.retrieve("c1").size());
        assertEquals(1, bounded.retrieve("c2").size());
        assertEquals(1, bounded.retrieve("c3").size());

        // Adding a 4th should evict c1
        bounded.record(new CoordinationEvent.CoordinationStarted("c4", "ceo", Instant.now()));

        assertTrue(bounded.retrieve("c1").isEmpty(), "c1 should have been evicted");
        assertEquals(1, bounded.retrieve("c2").size());
        assertEquals(1, bounded.retrieve("c3").size());
        assertEquals(1, bounded.retrieve("c4").size());
    }

    @Test
    void evictsMultipleWhenBurstExceedsLimit() {
        var bounded = new InMemoryCoordinationJournal(2);
        bounded.start();

        bounded.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.CoordinationStarted("c2", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.CoordinationStarted("c3", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.CoordinationStarted("c4", "ceo", Instant.now()));

        // c1 and c2 should be evicted, c3 and c4 remain
        assertTrue(bounded.retrieve("c1").isEmpty());
        assertTrue(bounded.retrieve("c2").isEmpty());
        assertEquals(1, bounded.retrieve("c3").size());
        assertEquals(1, bounded.retrieve("c4").size());
    }

    @Test
    void multipleEventsForSameCoordinationCountAsOne() {
        var bounded = new InMemoryCoordinationJournal(3);
        bounded.start();

        // Multiple events under the same coordination ID = 1 slot
        bounded.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.AgentDispatched(
                "c1", "weather", "forecast", java.util.Map.of(), Instant.now()));
        bounded.record(new CoordinationEvent.AgentCompleted(
                "c1", "weather", "forecast", "Sunny", Duration.ZERO, Instant.now()));

        bounded.record(new CoordinationEvent.CoordinationStarted("c2", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.CoordinationStarted("c3", "ceo", Instant.now()));

        // 3 coordinations, exactly at limit — no eviction
        assertEquals(3, bounded.retrieve("c1").size());
        assertEquals(1, bounded.retrieve("c2").size());
        assertEquals(1, bounded.retrieve("c3").size());

        // Adding c4 should evict c1 (the oldest coordination)
        bounded.record(new CoordinationEvent.CoordinationStarted("c4", "ceo", Instant.now()));
        assertTrue(bounded.retrieve("c1").isEmpty());
    }

    @Test
    void defaultMaxCoordinationsIsLarge() {
        // Default constructor should accept many coordinations without eviction
        for (int i = 0; i < 100; i++) {
            journal.record(new CoordinationEvent.CoordinationStarted(
                    "c" + i, "ceo", Instant.now()));
        }
        // All 100 should still be present
        for (int i = 0; i < 100; i++) {
            assertEquals(1, journal.retrieve("c" + i).size());
        }
    }

    @Test
    void stopClearsEvictionTracking() {
        var bounded = new InMemoryCoordinationJournal(2);
        bounded.start();

        bounded.record(new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.CoordinationStarted("c2", "ceo", Instant.now()));
        bounded.stop();

        // After stop+restart, adding 2 new ones should work without hitting stale order
        bounded.start();
        bounded.record(new CoordinationEvent.CoordinationStarted("c3", "ceo", Instant.now()));
        bounded.record(new CoordinationEvent.CoordinationStarted("c4", "ceo", Instant.now()));

        assertEquals(1, bounded.retrieve("c3").size());
        assertEquals(1, bounded.retrieve("c4").size());
    }
}
