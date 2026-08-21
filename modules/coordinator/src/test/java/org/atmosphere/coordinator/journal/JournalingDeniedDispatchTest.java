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

import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.fleet.FleetInterceptor;
import org.atmosphere.coordinator.fleet.InterceptingAgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression (registre#8): {@code Decision.Deny}'s javadoc promises the
 * coordinator's journaled event stream still records the denied dispatch
 * for audit, but the processor used to wrap the interceptor OUTSIDE the
 * journaling fleet — a denial short-circuited before any journal write.
 * With the framework's {@code journal(intercepting(base))} composition, a
 * denied dispatch must land in the journal as a failed dispatch.
 */
class JournalingDeniedDispatchTest {

    private InMemoryCoordinationJournal journal;
    private JournalingAgentFleet fleet;
    private AgentTransport transport;

    @BeforeEach
    void setUp() {
        journal = new InMemoryCoordinationJournal();
        journal.start();

        transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        when(transport.send(any(), any(), any())).thenReturn(
                new AgentResult("research", "search", "found", Map.of(),
                        Duration.ofMillis(10), true));

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("research", new DefaultAgentProxy("research", "1.0.0", 1, true, transport));

        FleetInterceptor denyAll = new FleetInterceptor() {
            @Override
            public Decision before(org.atmosphere.coordinator.fleet.AgentCall call) {
                return Decision.deny("policy blocked " + call.skill());
            }
        };
        // The framework's default composition: journal(intercepting(base)).
        var governed = new InterceptingAgentFleet(
                new DefaultAgentFleet(proxies), List.of(denyAll));
        fleet = new JournalingAgentFleet(governed, journal, "test-coordinator");
    }

    @AfterEach
    void tearDown() {
        journal.stop();
    }

    @Test
    void deniedDispatchIsRecordedInTheJournal() {
        var result = fleet.agent("research").call("search", Map.of("q", "secrets"));

        assertFalse(result.success(), "the deny must fail the call");
        verify(transport, never()).send(any(), any(), any());

        var events = journal.query(CoordinationQuery.all());
        assertFalse(events.isEmpty(),
                "a denied dispatch must not vanish from the audit trail");
        assertTrue(events.stream().anyMatch(
                        e -> e instanceof CoordinationEvent.AgentDispatched),
                "the attempted dispatch must be journaled: " + events);
        assertTrue(events.stream().anyMatch(e ->
                        e instanceof CoordinationEvent.AgentFailed failed
                                && failed.error() != null
                                && failed.error().contains("policy blocked search")),
                "the denial outcome and reason must be journaled: " + events);
    }
}
