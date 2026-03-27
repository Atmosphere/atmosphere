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

import org.atmosphere.coordinator.evaluation.Evaluation;
import org.atmosphere.coordinator.evaluation.ResultEvaluator;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JournalingAgentFleetTest {

    private InMemoryCoordinationJournal journal;
    private JournalingAgentFleet fleet;
    private AgentTransport weatherTransport;
    private AgentTransport newsTransport;

    @BeforeEach
    void setUp() {
        journal = new InMemoryCoordinationJournal();
        journal.start();

        weatherTransport = mock(AgentTransport.class);
        newsTransport = mock(AgentTransport.class);

        when(weatherTransport.isAvailable()).thenReturn(true);
        when(newsTransport.isAvailable()).thenReturn(true);
        when(weatherTransport.send(any(), any(), any())).thenReturn(
                new AgentResult("weather", "forecast", "Sunny 72F", Map.of(), Duration.ofMillis(100), true));
        when(newsTransport.send(any(), any(), any())).thenReturn(
                new AgentResult("news", "headlines", "No news", Map.of(), Duration.ofMillis(50), true));

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("weather", new DefaultAgentProxy("weather", "1.0.0", 1, true, weatherTransport));
        proxies.put("news", new DefaultAgentProxy("news", "1.0.0", 1, true, newsTransport));

        var delegate = new DefaultAgentFleet(proxies);
        fleet = new JournalingAgentFleet(delegate, journal, "test-coordinator");
    }

    @Test
    void parallelRecordsStartDispatchCompleteEvents() {
        var results = fleet.parallel(
                new AgentCall("weather", "forecast", Map.of("city", "Madrid")),
                new AgentCall("news", "headlines", Map.of())
        );

        assertEquals(2, results.size());
        assertTrue(results.get("weather").success());

        // Find a coordination — we don't know the UUID but there should be events
        var allEvents = journal.query(CoordinationQuery.all());
        assertFalse(allEvents.isEmpty());

        // Should have: Started + 2 Dispatched + 2 Completed + Completed
        long startCount = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.CoordinationStarted).count();
        long dispatchCount = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.AgentDispatched).count();
        long completeCount = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.AgentCompleted).count();
        long coordCompleteCount = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.CoordinationCompleted).count();

        assertEquals(1, startCount);
        assertEquals(2, dispatchCount);
        assertEquals(2, completeCount);
        assertEquals(1, coordCompleteCount);
    }

    @Test
    void pipelineRecordsEventsSequentially() {
        fleet.pipeline(
                new AgentCall("weather", "forecast", Map.of("city", "Madrid")),
                new AgentCall("news", "headlines", Map.of())
        );

        var allEvents = journal.query(CoordinationQuery.all());
        assertFalse(allEvents.isEmpty());

        long dispatchCount = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.AgentDispatched).count();
        assertEquals(2, dispatchCount);
    }

    @Test
    void pipelineAbortsOnFailureAndRecordsFailed() {
        when(weatherTransport.send(any(), any(), any())).thenReturn(
                AgentResult.failure("weather", "forecast", "timeout", Duration.ZERO));

        var result = fleet.pipeline(
                new AgentCall("weather", "forecast", Map.of()),
                new AgentCall("news", "headlines", Map.of())
        );

        assertFalse(result.success());

        var allEvents = journal.query(CoordinationQuery.all());
        long failedCount = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.AgentFailed).count();
        assertEquals(1, failedCount);

        // News should not have been dispatched
        long newsDispatched = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.AgentDispatched ad
                        && "news".equals(ad.agentName())).count();
        assertEquals(0, newsDispatched);
    }

    @Test
    void directAgentCallRecordsEvents() {
        var proxy = fleet.agent("weather");
        var result = proxy.call("forecast", Map.of("city", "Paris"));

        assertTrue(result.success());

        var allEvents = journal.query(CoordinationQuery.all());
        long dispatchCount = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.AgentDispatched).count();
        long completeCount = allEvents.stream()
                .filter(e -> e instanceof CoordinationEvent.AgentCompleted).count();
        assertEquals(1, dispatchCount);
        assertEquals(1, completeCount);
    }

    @Test
    void journalMethodReturnsJournal() {
        assertSame(journal, fleet.journal());
    }

    @Test
    void agentsDelegates() {
        assertEquals(2, fleet.agents().size());
    }

    @Test
    void availableDelegates() {
        assertEquals(2, fleet.available().size());
    }

    @Test
    void agentAndParallelShareCoordinationId() {
        // Direct agent call followed by parallel — should share one coordination ID
        var proxy = fleet.agent("weather");
        proxy.call("forecast", Map.of("city", "Paris"));

        fleet.parallel(
                new AgentCall("weather", "forecast", Map.of("city", "Madrid")),
                new AgentCall("news", "headlines", Map.of())
        );

        var allEvents = journal.query(CoordinationQuery.all());
        var coordinationIds = allEvents.stream()
                .map(CoordinationEvent::coordinationId)
                .collect(Collectors.toSet());

        assertEquals(1, coordinationIds.size(),
                "All operations on the same thread should share one coordination ID");
    }

    @Test
    void parallelAndPipelineGetSeparateCoordinationIds() {
        fleet.parallel(
                new AgentCall("weather", "forecast", Map.of("city", "Madrid")),
                new AgentCall("news", "headlines", Map.of())
        );

        fleet.pipeline(
                new AgentCall("weather", "forecast", Map.of()),
                new AgentCall("news", "headlines", Map.of())
        );

        var allEvents = journal.query(CoordinationQuery.all());
        var coordinationIds = allEvents.stream()
                .map(CoordinationEvent::coordinationId)
                .collect(Collectors.toSet());

        assertEquals(2, coordinationIds.size(),
                "parallel() and pipeline() should each get their own coordination ID "
                        + "since the ThreadLocal is cleared after each coordination");
    }

    @Test
    void differentThreadsGetDifferentCoordinationIds() throws Exception {
        var ids = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var latch = new CountDownLatch(2);

        Runnable task = () -> {
            fleet.parallel(
                    new AgentCall("weather", "forecast", Map.of())
            );
            var events = journal.query(CoordinationQuery.all());
            for (var e : events) {
                ids.add(e.coordinationId());
            }
            latch.countDown();
        };

        Thread.startVirtualThread(task);
        Thread.startVirtualThread(task);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertTrue(ids.size() >= 2,
                "Different threads should produce different coordination IDs");
    }

    @Test
    void autoEvaluateIsNonBlocking() throws Exception {
        // Create a fleet with a slow evaluator
        var latch = new CountDownLatch(1);
        ResultEvaluator slowEvaluator = new ResultEvaluator() {
            @Override
            public String name() { return "slow"; }

            @Override
            public Evaluation evaluate(AgentResult result, AgentCall call) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
                return Evaluation.pass(1.0, "ok");
            }
        };

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("weather", new DefaultAgentProxy("weather", "1.0.0", 1,
                true, weatherTransport));
        var delegateWithEval = new DefaultAgentFleet(proxies, List.of(slowEvaluator));
        var journalFleet = new JournalingAgentFleet(delegateWithEval, journal,
                "test-coordinator");

        // parallel() should return quickly — evaluation runs async
        long start = System.nanoTime();
        journalFleet.parallel(new AgentCall("weather", "forecast", Map.of()));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsed < 150,
                "parallel() should not block on evaluation; took " + elapsed + "ms");

        // Evaluation should eventually complete in the background
        assertTrue(latch.await(3, TimeUnit.SECONDS),
                "Background evaluation should complete");
    }
}
