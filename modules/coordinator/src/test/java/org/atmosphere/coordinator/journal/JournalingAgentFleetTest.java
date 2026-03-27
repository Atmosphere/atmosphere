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
import java.util.Map;

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
}
