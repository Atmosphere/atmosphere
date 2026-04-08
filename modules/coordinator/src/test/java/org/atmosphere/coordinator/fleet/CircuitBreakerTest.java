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

import org.atmosphere.coordinator.test.StubActivityListener;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CircuitBreakerTest {

    @Test
    void startsInClosedState() {
        var cb = new DefaultCircuitBreaker();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void opensAfterFailureThreshold() {
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(3, Duration.ofSeconds(30), 1));

        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());

        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());

        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest());
    }

    @Test
    void successResetsFailureCount() {
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(3, Duration.ofSeconds(30), 1));

        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();

        // Reset — need 3 more consecutive failures to open
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void transitionsToHalfOpenAfterCooldown() {
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofMillis(50), 1));

        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        // Wait for cooldown
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }

        // Should transition to HALF_OPEN
        assertTrue(cb.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
    }

    @Test
    void halfOpenClosesOnSuccess() {
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofMillis(50), 1));

        cb.recordFailure();
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }

        cb.allowRequest(); // triggers HALF_OPEN
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void halfOpenReopensOnFailure() {
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofMillis(50), 1));

        cb.recordFailure();
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }

        cb.allowRequest(); // triggers HALF_OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void resilientProxyFastFailsWhenOpen() {
        var transport = mock(AgentTransport.class);
        var innerProxy = new DefaultAgentProxy("agent", "1.0.0", 1, true, transport);
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofSeconds(30), 1));

        var proxy = new ResilientAgentProxy(innerProxy, cb);

        // First call fails, opening the circuit
        var failure = AgentResult.failure("agent", "skill", "error", Duration.ZERO);
        when(transport.send("agent", "skill", Map.of())).thenReturn(failure);
        proxy.call("skill", Map.of());

        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        // Second call should fast-fail without hitting transport
        var result = proxy.call("skill", Map.of());
        assertFalse(result.success());
        assertTrue(result.text().contains("Circuit breaker open"));

        // Transport should only have been called once (the first call)
        verify(transport, times(1)).send("agent", "skill", Map.of());
    }

    @Test
    void resilientProxyPassesThroughWhenClosed() {
        var transport = mock(AgentTransport.class);
        var innerProxy = new DefaultAgentProxy("agent", "1.0.0", 1, true, transport);
        var cb = new DefaultCircuitBreaker();

        var proxy = new ResilientAgentProxy(innerProxy, cb);
        var success = new AgentResult("agent", "skill", "ok", Map.of(), Duration.ZERO, true);
        when(transport.send("agent", "skill", Map.of())).thenReturn(success);

        var result = proxy.call("skill", Map.of());
        assertTrue(result.success());
        verify(transport).send("agent", "skill", Map.of());
    }

    @Test
    void resilientProxyEmitsCircuitOpenActivity() {
        var transport = mock(AgentTransport.class);
        var innerProxy = new DefaultAgentProxy("agent", "1.0.0", 1, true, transport);
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofSeconds(30), 1));

        var listener = new StubActivityListener();
        var proxy = new ResilientAgentProxy(innerProxy, cb, java.util.List.of(listener));

        var failure = AgentResult.failure("agent", "skill", "error", Duration.ZERO);
        when(transport.send("agent", "skill", Map.of())).thenReturn(failure);

        // First call opens the circuit
        proxy.call("skill", Map.of());
        // Second call triggers CircuitOpen activity
        proxy.call("skill", Map.of());

        var circuitOpenEvents = listener.activitiesFor("agent").stream()
                .filter(a -> a instanceof AgentActivity.CircuitOpen)
                .toList();
        assertFalse(circuitOpenEvents.isEmpty(),
                "Should emit CircuitOpen activity when circuit is open");
    }

    @Test
    void resilientProxyReportsAvailabilityBasedOnCircuit() {
        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        var innerProxy = new DefaultAgentProxy("agent", "1.0.0", 1, true, transport);
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofSeconds(30), 1));

        var proxy = new ResilientAgentProxy(innerProxy, cb);
        assertTrue(proxy.isAvailable());

        cb.recordFailure();
        assertFalse(proxy.isAvailable(), "Should be unavailable when circuit is open");
    }

    @Test
    void fleetHealthIncludesCircuitState() {
        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        var innerProxy = new DefaultAgentProxy("a", "1.0.0", 1, true, transport);
        var cb = new DefaultCircuitBreaker();

        var proxy = new ResilientAgentProxy(innerProxy, cb);
        var proxies = new java.util.LinkedHashMap<String, AgentProxy>();
        proxies.put("a", proxy);

        var fleet = new DefaultAgentFleet(proxies);
        var health = fleet.health();

        assertNotNull(health);
        var agentHealth = health.agents().get("a");
        assertNotNull(agentHealth);
        assertTrue(agentHealth.available());
        assertEquals(CircuitBreaker.State.CLOSED, agentHealth.circuitState());
    }

    @Test
    void fleetHealthNullCircuitWhenNoBreaker() {
        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        var proxy = new DefaultAgentProxy("a", "1.0.0", 1, true, transport);

        var proxies = new java.util.LinkedHashMap<String, AgentProxy>();
        proxies.put("a", proxy);

        var fleet = new DefaultAgentFleet(proxies);
        var agentHealth = fleet.health().agents().get("a");
        assertNotNull(agentHealth);
        assertTrue(agentHealth.available());
        assertEquals(null, agentHealth.circuitState());
    }
}
