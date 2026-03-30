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

import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingSpecTest {

    private AgentResult success(String agent, String text) {
        return new AgentResult(agent, "skill", text, Map.of(), Duration.ofMillis(50), true);
    }

    private AgentResult failure(String agent) {
        return AgentResult.failure(agent, "skill", "error", Duration.ofMillis(50));
    }

    private DefaultAgentFleet createFleet() {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("outdoor", mockProxy("outdoor"));
        proxies.put("indoor", mockProxy("indoor"));
        return new DefaultAgentFleet(proxies);
    }

    private AgentProxy mockProxy(String name) {
        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        when(transport.send(anyString(), anyString(), anyMap()))
                .thenReturn(success(name, "result from " + name));
        return new DefaultAgentProxy(name, "1.0.0", 1, true, transport);
    }

    @Test
    void singleMatchingRouteExecutes() {
        var fleet = createFleet();
        var weather = success("weather", "sunny and warm");

        var result = fleet.route(weather, route -> route
                .when(r -> r.text().contains("sunny"),
                        f -> f.agent("outdoor").call("plan", Map.of()))
        );

        assertTrue(result.success());
        assertEquals("outdoor", result.agentName());
    }

    @Test
    void firstMatchWinsMultipleRoutes() {
        var fleet = createFleet();
        var weather = success("weather", "sunny and warm");

        var result = fleet.route(weather, route -> route
                .when(r -> r.text().contains("sunny"),
                        f -> f.agent("outdoor").call("plan", Map.of()))
                .when(r -> r.success(),
                        f -> f.agent("indoor").call("plan", Map.of()))
        );

        assertEquals("outdoor", result.agentName());
    }

    @Test
    void secondRouteMatchesWhenFirstFails() {
        var fleet = createFleet();
        var weather = success("weather", "cloudy and cold");

        var result = fleet.route(weather, route -> route
                .when(r -> r.text().contains("sunny"),
                        f -> f.agent("outdoor").call("plan", Map.of()))
                .when(r -> r.success(),
                        f -> f.agent("indoor").call("plan", Map.of()))
        );

        assertEquals("indoor", result.agentName());
    }

    @Test
    void otherwiseFallbackWhenNoMatch() {
        var fleet = createFleet();
        var weather = failure("weather");

        var result = fleet.route(weather, route -> route
                .when(r -> r.success() && r.text().contains("sunny"),
                        f -> f.agent("outdoor").call("plan", Map.of()))
                .otherwise(f -> AgentResult.failure("router", "route",
                        "Weather unavailable", Duration.ZERO))
        );

        assertFalse(result.success());
        assertEquals("Weather unavailable", result.text());
    }

    @Test
    void noMatchNoOtherwiseReturnsFailure() {
        var fleet = createFleet();
        var weather = failure("weather");

        var result = fleet.route(weather, route -> route
                .when(r -> r.success(), f -> f.agent("outdoor").call("plan", Map.of()))
        );

        assertFalse(result.success());
        assertTrue(result.text().contains("No routing condition matched"));
    }

    @Test
    void routeWithAgentCallShorthand() {
        var fleet = createFleet();
        var weather = success("weather", "sunny");

        var result = fleet.route(weather, route -> route
                .when(r -> r.success(),
                        fleet.call("outdoor", "plan", Map.of()))
        );

        assertTrue(result.success());
        assertEquals("outdoor", result.agentName());
    }

    @Test
    void routeEvaluateRecordsCorrectIndex() {
        var fleet = createFleet();

        var spec = new RoutingSpec();
        spec.when(r -> false, f -> f.agent("outdoor").call("plan", Map.of()));
        spec.when(r -> true, f -> f.agent("indoor").call("plan", Map.of()));

        var outcome = spec.evaluate(success("input", "test"), fleet);
        assertEquals(1, outcome.matchedIndex());
        assertTrue(outcome.matched());
        assertEquals("indoor", outcome.result().agentName());
    }
}
