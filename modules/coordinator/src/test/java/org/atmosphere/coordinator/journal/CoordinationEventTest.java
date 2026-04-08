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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationEventTest {

    @Test
    void coordinationStartedHoldsFields() {
        var now = Instant.now();
        var event = new CoordinationEvent.CoordinationStarted("c1", "ceo", now);

        assertEquals("c1", event.coordinationId());
        assertEquals("ceo", event.coordinatorName());
        assertEquals(now, event.timestamp());
    }

    @Test
    void agentDispatchedDefensivelyCopiesArgs() {
        var args = new java.util.HashMap<String, Object>();
        args.put("q", "test");
        var event = new CoordinationEvent.AgentDispatched("c1", "weather", "forecast", args, Instant.now());

        assertEquals(Map.of("q", "test"), event.args());
    }

    @Test
    void agentDispatchedHandlesNullArgs() {
        var event = new CoordinationEvent.AgentDispatched("c1", "weather", "forecast", null, Instant.now());

        assertNotNull(event.args());
        assertTrue(event.args().isEmpty());
    }

    @Test
    void agentCompletedHoldsDuration() {
        var duration = Duration.ofMillis(250);
        var event = new CoordinationEvent.AgentCompleted(
                "c1", "weather", "forecast", "Sunny", duration, Instant.now());

        assertEquals(Duration.ofMillis(250), event.duration());
        assertEquals("Sunny", event.resultText());
    }

    @Test
    void agentFailedHoldsError() {
        var event = new CoordinationEvent.AgentFailed(
                "c1", "weather", "forecast", "timeout", Duration.ZERO, Instant.now());

        assertEquals("timeout", event.error());
    }

    @Test
    void agentEvaluatedHoldsScore() {
        var event = new CoordinationEvent.AgentEvaluated(
                "c1", "weather", "quality", 0.85, true, Instant.now());

        assertEquals(0.85, event.score());
        assertTrue(event.passed());
    }

    @Test
    void coordinationCompletedHoldsCallCount() {
        var event = new CoordinationEvent.CoordinationCompleted(
                "c1", Duration.ofSeconds(2), 3, Instant.now());

        assertEquals(3, event.agentCallCount());
    }

    @Test
    void sealedInterfacePermitsAllVariants() {
        CoordinationEvent event = new CoordinationEvent.CoordinationStarted("c1", "ceo", Instant.now());
        var matched = switch (event) {
            case CoordinationEvent.CoordinationStarted ignored -> true;
            case CoordinationEvent.AgentDispatched ignored -> false;
            case CoordinationEvent.AgentCompleted ignored -> false;
            case CoordinationEvent.AgentFailed ignored -> false;
            case CoordinationEvent.AgentEvaluated ignored -> false;
            case CoordinationEvent.AgentHandoff ignored -> false;
            case CoordinationEvent.RouteEvaluated ignored -> false;
            case CoordinationEvent.AgentActivityChanged ignored -> false;
            case CoordinationEvent.CircuitStateChanged ignored -> false;
            case CoordinationEvent.CoordinationCompleted ignored -> false;
        };
        assertTrue(matched);
    }
}
