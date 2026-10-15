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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CEO's fleet must reach every specialist when Spring picks the port
 * ({@code server.port=0}, as here with {@code RANDOM_PORT}). Fleet URLs built
 * from {@code ${server.port}} were read before the connector bound and named
 * {@code localhost:0}, so no agent was reachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false" })
class StartupTeamFleetEphemeralPortTest {

    @Autowired AtmosphereFramework framework;

    @Test
    void everySpecialistIsReachableOnAnEphemeralPort() {
        var fleets = assertInstanceOf(Map.class, framework.getAtmosphereConfig().properties()
                .get("org.atmosphere.coordinator.fleets"), "the coordinator publishes its fleet");
        var fleet = assertInstanceOf(AgentFleet.class, fleets.get("ceo"), "the CEO's fleet");

        assertEquals(4, fleet.agents().size(), "four specialists in the fleet");
        var names = fleet.available().stream().map(AgentProxy::name).sorted().toList();
        assertEquals(List.of("finance-agent", "research-agent", "strategy-agent",
                        "writer-agent"), names,
                "every specialist must be reachable whatever port the server bound");
        assertTrue(fleet.agents().stream().allMatch(AgentProxy::isLocal),
                "co-located specialists are dispatched in-process");
    }
}
