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
package org.atmosphere.coordinator.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.Test;

/**
 * Send-side contract for {@link AgentFleet#withParentRun(String)}: the
 * coordinator's tape run id must reach the child dispatch as
 * {@code atmosphere.tape.parentRunId} dispatch metadata (the value the wire
 * message carries so the child records its parent run). Uses a capturing
 * transport that records the metadata it is handed.
 */
class AgentFleetParentRunTest {

    private static final String KEY = "atmosphere.tape.parentRunId";

    /** Transport that records the last dispatch metadata it was handed. */
    private static final class CapturingTransport implements AgentTransport {
        final AtomicReference<Map<String, Object>> lastMetadata = new AtomicReference<>(Map.of());

        @Override
        public AgentResult send(String agentName, String skill, Map<String, Object> args) {
            return send(agentName, skill, args, Map.of());
        }

        @Override
        public AgentResult send(String agentName, String skill, Map<String, Object> args,
                                Map<String, Object> dispatchMetadata) {
            lastMetadata.set(dispatchMetadata);
            return new AgentResult(agentName, skill, "ok", Map.of(), Duration.ZERO, true);
        }

        @Override
        public void stream(String agentName, String skill, Map<String, Object> args,
                           Consumer<String> onToken, Runnable onComplete) {
            onComplete.run();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private static AgentFleet fleetWith(CapturingTransport transport) {
        var proxy = new DefaultAgentProxy("research", "1.0", 1, true, transport);
        return new DefaultAgentFleet(Map.of("research", proxy));
    }

    @Test
    void withParentRunStampsParentRunIdOntoDispatch() {
        var transport = new CapturingTransport();
        var fleet = fleetWith(transport);

        fleet.withParentRun("ceo-run-1").agent("research").call("assess", Map.of("q", "market"));

        assertEquals("ceo-run-1", transport.lastMetadata.get().get(KEY),
                "the coordinator's run id must ride the child dispatch metadata");
    }

    @Test
    void parallelDispatchCarriesParentRun() {
        var transport = new CapturingTransport();
        var fleet = fleetWith(transport);

        fleet.withParentRun("ceo-run-2")
                .parallel(new AgentCall("research", "assess", Map.of("q", "market")));

        assertEquals("ceo-run-2", transport.lastMetadata.get().get(KEY));
    }

    @Test
    void withoutParentRunNoMetadataIsSent() {
        var transport = new CapturingTransport();
        var fleet = fleetWith(transport);

        fleet.agent("research").call("assess", Map.of("q", "market"));

        assertTrue(transport.lastMetadata.get().isEmpty(),
                "a plain dispatch carries no parent-run metadata");
    }

    @Test
    void nullOrBlankParentRunIsANoOpView() {
        var transport = new CapturingTransport();
        var fleet = fleetWith(transport);

        assertSame(fleet, fleet.withParentRun(null), "null parent id returns the same fleet");
        assertSame(fleet, fleet.withParentRun("  "), "blank parent id returns the same fleet");

        fleet.withParentRun(null).agent("research").call("assess", Map.of("q", "x"));
        assertNull(transport.lastMetadata.get().get(KEY));
    }

    // Regression: the parent run must survive the proxy/fleet wrappers a real
    // coordinator dispatch goes through. The tree-linkage silently vanished when
    // ResilientAgentProxy and the governance InterceptingAgentFleet did not
    // override the propagation and fell through to the no-op interface default.

    @Test
    void withParentRunPropagatesThroughResilientProxyWrapper() {
        var transport = new CapturingTransport();
        var proxy = new ResilientAgentProxy(
                new DefaultAgentProxy("research", "1.0", 1, true, transport),
                new DefaultCircuitBreaker());
        var fleet = new DefaultAgentFleet(Map.of("research", proxy));

        fleet.withParentRun("ceo-run-1").agent("research").call("assess", Map.of("q", "market"));

        assertEquals("ceo-run-1", transport.lastMetadata.get().get(KEY),
                "ResilientAgentProxy must thread the parent run into its delegate");
    }

    @Test
    void withParentRunPropagatesThroughGovernanceInterceptorWrapper() {
        var transport = new CapturingTransport();
        var proxy = new ResilientAgentProxy(
                new DefaultAgentProxy("research", "1.0", 1, true, transport),
                new DefaultCircuitBreaker());
        AgentFleet fleet = new DefaultAgentFleet(Map.of("research", proxy));
        // The coordinator sees a governance-wrapped fleet (InterceptingAgentFleet).
        var governed = fleet.withInterceptor(call -> FleetInterceptor.Decision.proceed());

        governed.withParentRun("ceo-run-2").agent("research").call("assess", Map.of("q", "market"));

        assertEquals("ceo-run-2", transport.lastMetadata.get().get(KEY),
                "a governance-wrapped fleet must still propagate the parent run to children");
    }
}
