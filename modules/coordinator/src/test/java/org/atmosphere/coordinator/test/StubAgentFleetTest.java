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
package org.atmosphere.coordinator.test;

import org.atmosphere.coordinator.fleet.AgentCall;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubAgentFleetTest {

    @Test
    void simpleAgentReturnsResponse() {
        var fleet = StubAgentFleet.builder()
                .agent("weather", "Sunny 72F")
                .build();

        var result = fleet.agent("weather").call("forecast", Map.of());
        assertTrue(result.success());
        assertEquals("Sunny 72F", result.text());
    }

    @Test
    void multipleAgents() {
        var fleet = StubAgentFleet.builder()
                .agent("weather", "Sunny")
                .agent("news", "Headlines")
                .build();

        assertEquals(2, fleet.agents().size());
        assertEquals(2, fleet.available().size());
    }

    @Test
    void parallelExecution() {
        var fleet = StubAgentFleet.builder()
                .agent("weather", "Sunny")
                .agent("news", "Headlines")
                .build();

        var results = fleet.parallel(
                new AgentCall("weather", "forecast", Map.of()),
                new AgentCall("news", "headlines", Map.of())
        );

        assertEquals(2, results.size());
        assertEquals("Sunny", results.get("weather").text());
        assertEquals("Headlines", results.get("news").text());
    }

    @Test
    void pipelineExecution() {
        var fleet = StubAgentFleet.builder()
                .agent("step1", "result1")
                .agent("step2", "result2")
                .build();

        var result = fleet.pipeline(
                new AgentCall("step1", "process", Map.of()),
                new AgentCall("step2", "process", Map.of())
        );

        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("result2", result.text());
    }

    @Test
    void unknownAgentThrows() {
        var fleet = StubAgentFleet.builder()
                .agent("weather", "Sunny")
                .build();

        assertThrows(IllegalArgumentException.class, () -> fleet.agent("unknown"));
    }

    @Test
    void customTransport() {
        var transport = StubAgentTransport.builder()
                .when("rain", "Yes, bring umbrella")
                .defaultResponse("No rain")
                .build();

        var fleet = StubAgentFleet.builder()
                .agent("weather", transport)
                .build();

        var result = fleet.agent("weather").call("forecast", Map.of("q", "will it rain?"));
        assertEquals("Yes, bring umbrella", result.text());
    }
}
