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
package org.atmosphere.quarkus.admin.runtime;

import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.InMemoryCoordinationJournal;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Quarkus coordinator admin wiring must read the journal the coordinator
 * fleets record into. {@code CoordinatorProcessor} resolves that journal itself (for the
 * startup-team sample, through {@code ServiceLoader}, with no Spring bean), so the
 * previous wiring — a fixed NOOP for {@code /api/admin/journal} — returned an
 * empty journal after a run the console rendered in full (parity with the
 * Spring Boot starters).
 */
class CoordinatorAdminJournalWiringTest {

    private AtmosphereFramework framework;
    private InMemoryCoordinationJournal journal;

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework()
                .addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        framework.init();
        journal = new InMemoryCoordinationJournal();
        journal.start();
        var now = Instant.now();
        journal.record(new CoordinationEvent.CoordinationStarted("coord-1", "ceo", now));
        journal.record(new CoordinationEvent.AgentDispatched("coord-1", "research-agent", "web_search",
                Map.of(), now.plusMillis(1)));
        journal.record(new CoordinationEvent.AgentCompleted("coord-1", "research-agent", "web_search",
                "done", Duration.ofMillis(23), now.plusMillis(24)));
        var fleet = mock(AgentFleet.class);
        when(fleet.journal()).thenReturn(journal);
        // What CoordinatorProcessor publishes at endpoint registration.
        framework.getAtmosphereConfig().properties()
                .put(CoordinatorController.FLEETS_PROPERTY, Map.of("ceo", fleet));
    }

    @AfterEach
    void tearDown() {
        journal.stop();
        framework.destroy();
    }

    @Test
    void journalEndpointReadsTheFleetJournal() {
        var admin = new AtmosphereAdmin(framework, 16);
        CoordinatorAdminWiring.install(admin, framework);

        var entries = admin.<CoordinatorController>coordinatorController().queryJournal(null, null, null, null, 100);

        assertEquals(3, entries.size(), "/api/admin/journal must list the fleet's events: " + entries);
    }
}
