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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end exercise of the event-sourced journal: lineage emission via
 * {@link JournalingAgentFleet}, persistence via {@link FileCoordinationJournal},
 * projection via {@link CoordinationProjection}, and what-if branching via
 * {@link CoordinationFork} — all wired together across a JVM restart boundary.
 *
 * <p>Verifies the paper's runtime contract: from the persisted log alone, a
 * fresh process can rebuild the causal DAG of the original coordination and
 * any forked branches.</p>
 */
class EventSourcedJournalIntegrationTest {

    @Test
    void parallelRunIsPersistedProjectedAndForkableAcrossRestart(@TempDir Path tmp) {
        var journalFile = tmp.resolve("integration.ndjson");

        // ============================================================
        // Process 1: run a parallel coordination, persist to disk
        // ============================================================
        String parentDispatchId;
        String parentCoordId;
        {
            var journal = new FileCoordinationJournal(journalFile);
            journal.start();
            var fleet = newFleet(journal, "integration");
            try {
                fleet.parallel(
                        new AgentCall("alpha", "do", Map.of("k", "v1")),
                        new AgentCall("beta", "do", Map.of("k", "v2"))
                );

                // Capture the dispatch we want to fork from later
                var envelopes = journal.retrieveEnveloped("integration");
                parentDispatchId = envelopes.stream()
                        .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched ad
                                && "alpha".equals(ad.agentName()))
                        .findFirst().orElseThrow()
                        .eventId();
                parentCoordId = "integration";

                // Sanity: projection of process 1 sees the full chain
                var projection = CoordinationProjection.from(journal, parentCoordId);
                assertEquals(1, projection.roots().size());
                assertEquals(2, projection.agents().size());
            } finally {
                journal.stop();
                fleet.close();
            }
        }

        // ============================================================
        // Process 2: restart, replay from disk, project, fork an alternate
        // ============================================================
        var replayed = new FileCoordinationJournal(journalFile);
        replayed.start();
        var replayedFleet = newFleet(replayed, "integration");
        try {
            // Lineage survived ser/deser
            var projection = CoordinationProjection.from(replayed, parentCoordId);
            assertEquals(1, projection.roots().size(),
                    "after restart, projection should rebuild the single Started root");
            assertEquals(2, projection.agents().size(),
                    "after restart, projection should still see both agents");

            var rebuiltParent = projection.event(parentDispatchId).orElseThrow();
            assertNotNull(rebuiltParent.parentEventId(),
                    "parent dispatch's parentEventId (CoordinationStarted) must survive replay");

            // Fork at the alpha dispatch, run beta as the alternate
            var fork = new CoordinationFork(replayed);
            var result = fork
                    .from(parentCoordId, parentDispatchId)
                    .reason("integration: try beta instead")
                    .with(new AgentCall("beta", "do", Map.of("k", "alt")))
                    .execute(replayedFleet);

            assertNotNull(result.newCoordinationId());
            assertEquals("beta-ok", result.result().text());

            // Forked coord is queryable in the live journal
            var forkProjection = CoordinationProjection.from(replayed, result.newCoordinationId());
            var forkRoot = forkProjection.roots().stream()
                    .filter(env -> env.event() instanceof CoordinationEvent.ForkCreated)
                    .findFirst().orElseThrow();
            var fc = (CoordinationEvent.ForkCreated) forkRoot.event();
            assertEquals(parentCoordId, fc.parentCoordinationId());
            assertEquals(parentDispatchId, fc.parentEventId());
            assertEquals("integration: try beta instead", fc.reason());
        } finally {
            replayed.stop();
            replayedFleet.close();
        }

        // ============================================================
        // Process 3: restart again, both original AND forked branches survive
        // ============================================================
        var third = new FileCoordinationJournal(journalFile);
        third.start();
        try {
            var originalProjection = CoordinationProjection.from(third, parentCoordId);
            assertTrue(originalProjection.envelopes().size() >= 6,
                    "original coord has: Started + 2 Dispatched + 2 Completed + 1 CoordinationCompleted = 6");

            // Find the forked coordination by listing all events of type ForkCreated
            // across the journal — we don't know its UUID-derived id this time
            var allForks = third.query(CoordinationQuery.all()).stream()
                    .filter(event -> event instanceof CoordinationEvent.ForkCreated)
                    .map(event -> (CoordinationEvent.ForkCreated) event)
                    .toList();
            assertEquals(1, allForks.size(), "exactly one fork was created in process 2");

            var forkedCoordId = allForks.get(0).coordinationId();
            var forkedProjection = CoordinationProjection.from(third, forkedCoordId);
            assertEquals(3, forkedProjection.envelopes().size(),
                    "forked coord: ForkCreated + AgentDispatched + AgentCompleted = 3");
        } finally {
            third.stop();
        }
    }

    private static JournalingAgentFleet newFleet(CoordinationJournal journal, String name) {
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
        return new JournalingAgentFleet(new DefaultAgentFleet(proxies), journal, name);
    }
}
