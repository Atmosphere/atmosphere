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
package org.atmosphere.admin.coordinator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the supplier-based fleet wiring behind {@code /api/admin/coordinators}.
 * The regression class: the controller used to be constructed with an empty
 * immutable map while {@code CoordinatorProcessor} registered fleets later —
 * the endpoint permanently returned {@code []} and the console fleet roster
 * could never render. A live supplier must reflect fleets registered AFTER
 * controller construction.
 */
class CoordinatorControllerFleetTest {

    @Test
    void reflectsFleetsRegisteredAfterConstruction() {
        var registry = new ConcurrentHashMap<String, AgentFleet>();
        var controller = new CoordinatorController(() -> registry, CoordinationJournal.NOOP);

        // Bean-vs-framework startup ordering: nothing registered yet.
        assertTrue(controller.listCoordinators().isEmpty());

        // CoordinatorProcessor registers the fleet later.
        registry.put("ceo", fleet("research-agent", "writer-agent"));

        var coordinators = controller.listCoordinators();
        assertEquals(1, coordinators.size());
        assertEquals("ceo", coordinators.get(0).get("name"));
        assertEquals(2, coordinators.get(0).get("agentCount"));

        var detail = controller.getFleet("ceo").orElseThrow();
        @SuppressWarnings("unchecked") // shape produced by getFleet itself
        var agents = (List<Map<String, Object>>) detail.get("agents");
        assertEquals(List.of("research-agent", "writer-agent"),
                agents.stream().map(a -> a.get("name")).toList());
    }

    @Test
    void staticMapConstructorKeepsItsSnapshotSemantics() {
        var controller = new CoordinatorController(
                Map.of("solo", fleet("only-agent")), CoordinationJournal.NOOP);
        assertEquals(1, controller.listCoordinators().size());
        assertTrue(controller.getFleet("other").isEmpty());
    }

    private AgentFleet fleet(String... agentNames) {
        var proxies = new java.util.ArrayList<AgentProxy>();
        for (var name : agentNames) {
            var proxy = mock(AgentProxy.class);
            when(proxy.name()).thenReturn(name);
            when(proxy.version()).thenReturn("1.0");
            when(proxy.isAvailable()).thenReturn(true);
            when(proxy.isLocal()).thenReturn(true);
            when(proxy.weight()).thenReturn(1);
            proxies.add(proxy);
        }
        var fleet = mock(AgentFleet.class);
        when(fleet.agents()).thenReturn(List.copyOf(proxies));
        when(fleet.available()).thenReturn(List.copyOf(proxies));
        return fleet;
    }
}
