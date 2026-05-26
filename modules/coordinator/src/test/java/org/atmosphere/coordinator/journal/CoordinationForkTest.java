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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoordinationForkTest {

    private InMemoryCoordinationJournal journal;
    private JournalingAgentFleet fleet;

    @BeforeEach
    void setUp() {
        journal = new InMemoryCoordinationJournal();
        journal.start();
        AgentTransport alpha = mock(AgentTransport.class);
        AgentTransport beta = mock(AgentTransport.class);
        when(alpha.isAvailable()).thenReturn(true);
        when(beta.isAvailable()).thenReturn(true);
        when(alpha.send(any(), any(), any())).thenReturn(
                new AgentResult("alpha", "do", "alpha-ok", Map.of(), Duration.ofMillis(5), true));
        when(beta.send(any(), any(), any())).thenReturn(
                new AgentResult("beta", "do", "beta-ok", Map.of(), Duration.ofMillis(5), true));
        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("alpha", new DefaultAgentProxy("alpha", "1.0.0", 1, true, alpha));
        proxies.put("beta", new DefaultAgentProxy("beta", "1.0.0", 1, true, beta));
        fleet = new JournalingAgentFleet(new DefaultAgentFleet(proxies), journal, "fork-test");
    }

    @Test
    void forkExecutesUnderNewCoordinationIdAndRecordsForkLink() {
        // 1. Run the original coordination
        fleet.parallel(new AgentCall("alpha", "do", Map.of()));
        var parentEnvelopes = journal.retrieveEnveloped("fork-test");
        var dispatched = parentEnvelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched)
                .findFirst()
                .orElseThrow();

        // 2. Fork at the dispatched event with an alternate call
        var fork = new CoordinationFork(journal);
        var result = fork
                .from("fork-test", dispatched.eventId())
                .reason("try beta instead of alpha")
                .with(new AgentCall("beta", "do", Map.of()))
                .execute(fleet);

        // 3. Forked coord exists and has the alternate's execution events
        assertNotNull(result.newCoordinationId());
        assertNotEquals("fork-test", result.newCoordinationId());
        assertEquals("beta-ok", result.result().text());

        var forkedEnvelopes = journal.retrieveEnveloped(result.newCoordinationId());
        var forkCreated = forkedEnvelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.ForkCreated)
                .findFirst()
                .orElseThrow();
        var fc = (CoordinationEvent.ForkCreated) forkCreated.event();
        assertEquals("fork-test", fc.parentCoordinationId());
        assertEquals(dispatched.eventId(), fc.parentEventId());
        assertEquals("try beta instead of alpha", fc.reason());

        // The alternate dispatch and completion landed in the forked coord
        long altDispatches = forkedEnvelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched ad
                        && "beta".equals(ad.agentName()))
                .count();
        assertEquals(1, altDispatches,
                "the alternate beta dispatch must record under the forked coordination id");

        // Original parent coord is unchanged (no new events on it from the fork)
        assertEquals(parentEnvelopes.size(),
                journal.retrieveEnveloped("fork-test").size(),
                "fork must NOT mutate the parent coordination's event stream");
    }

    @Test
    void forkRejectsUnknownParentEventId() {
        fleet.parallel(new AgentCall("alpha", "do", Map.of()));
        var fork = new CoordinationFork(journal);

        var ex = assertThrows(IllegalArgumentException.class, () -> fork
                .from("fork-test", "no-such-event")
                .with(new AgentCall("beta", "do", Map.of()))
                .execute(fleet));
        assertTrue(ex.getMessage().contains("no-such-event"),
                "error must name the missing event for diagnosability");
    }

    @Test
    void forkAcceptsExplicitNewCoordinationId() {
        fleet.parallel(new AgentCall("alpha", "do", Map.of()));
        var dispatched = journal.retrieveEnveloped("fork-test").stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched)
                .findFirst()
                .orElseThrow();

        var fork = new CoordinationFork(journal);
        var result = fork
                .from("fork-test", dispatched.eventId())
                .with(new AgentCall("beta", "do", Map.of()))
                .execute(fleet, "my-fork-coord");

        assertEquals("my-fork-coord", result.newCoordinationId());
        // Forked coord = 1 ForkCreated + proxy.call emits AgentDispatched + AgentCompleted = 3
        assertEquals(3, journal.retrieveEnveloped("my-fork-coord").size(),
                "forked coord should have ForkCreated + alternate dispatch's Dispatched + Completed envelopes");
    }

    @Test
    void forkProjectionShowsForkCreatedAsRoot() {
        fleet.parallel(new AgentCall("alpha", "do", Map.of()));
        var dispatched = journal.retrieveEnveloped("fork-test").stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched)
                .findFirst()
                .orElseThrow();

        var fork = new CoordinationFork(journal);
        var result = fork
                .from("fork-test", dispatched.eventId())
                .with(new AgentCall("beta", "do", Map.of()))
                .execute(fleet);

        var projection = CoordinationProjection.from(journal, result.newCoordinationId());
        // ForkCreated has no in-coord parent, so it's a root in the new projection
        var rootIsForkCreated = projection.roots().stream()
                .anyMatch(env -> env.event() instanceof CoordinationEvent.ForkCreated);
        assertTrue(rootIsForkCreated,
                "ForkCreated must be a root in the forked coordination's projection");
    }
}
