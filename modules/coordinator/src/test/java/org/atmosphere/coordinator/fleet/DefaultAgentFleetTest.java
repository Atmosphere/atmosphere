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
import static org.mockito.Mockito.*;

public class DefaultAgentFleetTest {

    private AgentProxy mockProxy(String name, boolean available) {
        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(available);
        when(transport.send(anyString(), anyString(), anyMap()))
                .thenReturn(new AgentResult(name, "skill", "result from " + name,
                        Map.of(), Duration.ofMillis(50), true));
        return new DefaultAgentProxy(name, "1.0.0", 1, true, transport);
    }

    private DefaultAgentFleet createFleet() {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("research", mockProxy("research", true));
        proxies.put("strategy", mockProxy("strategy", true));
        proxies.put("optional", mockProxy("optional", false));
        return new DefaultAgentFleet(proxies);
    }

    @Test
    void agentReturnsCorrectProxy() {
        var fleet = createFleet();
        assertEquals("research", fleet.agent("research").name());
        assertEquals("strategy", fleet.agent("strategy").name());
    }

    @Test
    void agentUnknownThrows() {
        var fleet = createFleet();
        var ex = assertThrows(IllegalArgumentException.class,
                () -> fleet.agent("unknown"));
        assertTrue(ex.getMessage().contains("unknown"));
        assertTrue(ex.getMessage().contains("research"));
    }

    @Test
    void agentsReturnsAll() {
        var fleet = createFleet();
        assertEquals(3, fleet.agents().size());
    }

    @Test
    void availableFiltersUnavailable() {
        var fleet = createFleet();
        var available = fleet.available();
        assertEquals(2, available.size());
        assertTrue(available.stream().allMatch(AgentProxy::isAvailable));
    }

    @Test
    void callCreatesAgentCall() {
        var fleet = createFleet();
        var call = fleet.call("research", "search", Map.of("q", "test"));
        assertEquals("research", call.agentName());
        assertEquals("search", call.skill());
        assertEquals("test", call.args().get("q"));
    }

    @Test
    void parallelExecutesConcurrently() {
        var fleet = createFleet();
        var results = fleet.parallel(
                fleet.call("research", "search", Map.of("q", "ai")),
                fleet.call("strategy", "analyze", Map.of("data", "x"))
        );
        assertEquals(2, results.size());
        assertTrue(results.containsKey("research"));
        assertTrue(results.containsKey("strategy"));
        assertTrue(results.get("research").success());
        assertTrue(results.get("strategy").success());
    }

    @Test
    void pipelineExecutesSequentially() {
        var fleet = createFleet();
        var result = fleet.pipeline(
                fleet.call("research", "search", Map.of("q", "ai")),
                fleet.call("strategy", "analyze", Map.of("data", "x"))
        );
        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void parallelHandlesDuplicateAgentNames() {
        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        when(transport.send(eq("analyzer"), eq("summarize"), anyMap()))
                .thenReturn(new AgentResult("analyzer", "summarize", "summary result",
                        Map.of(), Duration.ofMillis(50), true));
        when(transport.send(eq("analyzer"), eq("classify"), anyMap()))
                .thenReturn(new AgentResult("analyzer", "classify", "classify result",
                        Map.of(), Duration.ofMillis(50), true));

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("analyzer", new DefaultAgentProxy("analyzer", "1.0.0", 1, true, transport));
        var fleet = new DefaultAgentFleet(proxies);

        var results = fleet.parallel(
                fleet.call("analyzer", "summarize", Map.of("text", "abc")),
                fleet.call("analyzer", "classify", Map.of("text", "abc"))
        );

        assertEquals(2, results.size(),
                "Both calls should be present — second must not overwrite first");

        // First call uses plain name as key
        assertTrue(results.containsKey("analyzer"));
        // Second call uses disambiguated key
        assertTrue(results.containsKey("analyzer#2"));

        // Verify both results are present
        var allTexts = results.values().stream()
                .map(AgentResult::text).collect(java.util.stream.Collectors.toSet());
        assertTrue(allTexts.contains("summary result"));
        assertTrue(allTexts.contains("classify result"));
    }

    @Test
    void parallelUniqueAgentNamesUseSimpleKeys() {
        var fleet = createFleet();
        var results = fleet.parallel(
                fleet.call("research", "search", Map.of("q", "ai")),
                fleet.call("strategy", "analyze", Map.of("data", "x"))
        );
        // Non-duplicate names should use plain names as keys
        assertTrue(results.containsKey("research"));
        assertTrue(results.containsKey("strategy"));
    }

    @Test
    void pipelineAbortsOnFailure() {
        var failTransport = mock(AgentTransport.class);
        when(failTransport.isAvailable()).thenReturn(true);
        when(failTransport.send(anyString(), anyString(), anyMap()))
                .thenReturn(AgentResult.failure("fail", "s", "boom", Duration.ZERO));
        var failProxy = new DefaultAgentProxy("fail", "1.0.0", 1, true, failTransport);

        var okTransport = mock(AgentTransport.class);
        when(okTransport.isAvailable()).thenReturn(true);
        var okProxy = new DefaultAgentProxy("ok", "1.0.0", 1, true, okTransport);

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("fail", failProxy);
        proxies.put("ok", okProxy);
        var fleet = new DefaultAgentFleet(proxies);

        var result = fleet.pipeline(
                fleet.call("fail", "s", Map.of()),
                fleet.call("ok", "s", Map.of())
        );

        assertFalse(result.success());
        assertEquals("boom", result.text());
        // "ok" agent should never be called
        verify(okTransport, never()).send(anyString(), anyString(), anyMap());
    }
}
