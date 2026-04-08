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

import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of fleet health. Streamable to clients for
 * live fleet dashboard rendering.
 *
 * @param agents    health state per agent
 * @param timestamp when the snapshot was taken
 */
public record FleetHealth(Map<String, AgentHealth> agents, Instant timestamp) {

    public FleetHealth {
        agents = Map.copyOf(agents);
    }

    /**
     * Per-agent health state.
     *
     * @param name           agent name
     * @param available      whether the agent transport is reachable
     * @param circuitState   circuit breaker state (null if no breaker configured)
     * @param recentFailures number of recent consecutive failures
     */
    public record AgentHealth(
            String name,
            boolean available,
            CircuitBreaker.State circuitState,
            int recentFailures
    ) {}
}
