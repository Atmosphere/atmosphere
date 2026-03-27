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

import org.atmosphere.coordinator.fleet.AgentResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordinatorAssertionsTest {

    @Test
    void succeededPassesOnSuccess() {
        var result = new AgentResult("weather", "forecast", "Sunny", Map.of(), Duration.ZERO, true);
        assertDoesNotThrow(() -> CoordinatorAssertions.assertThat(result).succeeded());
    }

    @Test
    void succeededFailsOnFailure() {
        var result = AgentResult.failure("weather", "forecast", "timeout", Duration.ZERO);
        assertThrows(AssertionError.class, () -> CoordinatorAssertions.assertThat(result).succeeded());
    }

    @Test
    void failedPassesOnFailure() {
        var result = AgentResult.failure("weather", "forecast", "timeout", Duration.ZERO);
        assertDoesNotThrow(() -> CoordinatorAssertions.assertThat(result).failed());
    }

    @Test
    void failedFailsOnSuccess() {
        var result = new AgentResult("weather", "forecast", "Sunny", Map.of(), Duration.ZERO, true);
        assertThrows(AssertionError.class, () -> CoordinatorAssertions.assertThat(result).failed());
    }

    @Test
    void containsTextPasses() {
        var result = new AgentResult("weather", "forecast", "Sunny 72F", Map.of(), Duration.ZERO, true);
        assertDoesNotThrow(() -> CoordinatorAssertions.assertThat(result).containsText("Sunny"));
    }

    @Test
    void containsTextFails() {
        var result = new AgentResult("weather", "forecast", "Rainy", Map.of(), Duration.ZERO, true);
        assertThrows(AssertionError.class, () -> CoordinatorAssertions.assertThat(result).containsText("Sunny"));
    }

    @Test
    void completedWithinPasses() {
        var result = new AgentResult("weather", "forecast", "Sunny", Map.of(), Duration.ofMillis(100), true);
        assertDoesNotThrow(() -> CoordinatorAssertions.assertThat(result).completedWithin(Duration.ofSeconds(1)));
    }

    @Test
    void completedWithinFails() {
        var result = new AgentResult("weather", "forecast", "Sunny", Map.of(), Duration.ofSeconds(10), true);
        assertThrows(AssertionError.class,
                () -> CoordinatorAssertions.assertThat(result).completedWithin(Duration.ofSeconds(1)));
    }

    @Test
    void fromAgentPasses() {
        var result = new AgentResult("weather", "forecast", "Sunny", Map.of(), Duration.ZERO, true);
        assertDoesNotThrow(() -> CoordinatorAssertions.assertThat(result).fromAgent("weather"));
    }

    @Test
    void fromAgentFails() {
        var result = new AgentResult("weather", "forecast", "Sunny", Map.of(), Duration.ZERO, true);
        assertThrows(AssertionError.class, () -> CoordinatorAssertions.assertThat(result).fromAgent("news"));
    }

    @Test
    void chainingWorks() {
        var result = new AgentResult("weather", "forecast", "Sunny 72F", Map.of(), Duration.ofMillis(50), true);
        assertDoesNotThrow(() ->
                CoordinatorAssertions.assertThat(result)
                        .succeeded()
                        .containsText("Sunny")
                        .fromAgent("weather")
                        .completedWithin(Duration.ofSeconds(1))
        );
    }

    @Test
    void nullResultThrows() {
        assertThrows(AssertionError.class, () -> CoordinatorAssertions.assertThat(null));
    }
}
