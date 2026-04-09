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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for code review findings across Windows 1-4.
 */
class ReviewRegressionTest {

    // ── Window 2 P1: Circuit breaker probe counter must cap ──

    @Test
    void halfOpenProbeCounterCapsAtLimit() {
        // Config: 1 probe allowed in HALF_OPEN state
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofMillis(50), 1));

        // Trip to OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        // Wait for cooldown
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }

        // First call transitions OPEN -> HALF_OPEN (the transition probe)
        assertTrue(cb.allowRequest(), "Transition probe should be allowed");
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

        // Second call is the first real HALF_OPEN probe (counter 0 < 1 = allowed)
        assertTrue(cb.allowRequest(), "First HALF_OPEN probe should be allowed");

        // Third call should be rejected — counter reached limit
        assertFalse(cb.allowRequest(), "Probe after limit should be rejected");
    }

    @Test
    void halfOpenProbeCounterResetsOnSuccess() {
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofMillis(50), 1));

        cb.recordFailure();
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }

        assertTrue(cb.allowRequest());
        cb.recordSuccess();

        // Should be back to CLOSED — unlimited requests
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
        assertTrue(cb.allowRequest());
    }

    // ── Window 2 P2: parallelCancellable duplicate key handling ──

    @Test
    void parallelCancellableDuplicateAgentNames() {
        var transport = mock(AgentTransport.class);
        when(transport.send("agent", "skill", Map.of()))
                .thenReturn(new AgentResult("agent", "skill", "ok", Map.of(), Duration.ZERO, true));
        when(transport.isAvailable()).thenReturn(true);

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("agent", new DefaultAgentProxy("agent", "1.0.0", 1, true, transport));

        var fleet = new DefaultAgentFleet(proxies);
        var handles = fleet.parallelCancellable(
                fleet.call("agent", "skill", Map.of()),
                fleet.call("agent", "skill", Map.of())
        );

        // Should have 2 entries with deduplicated keys
        assertEquals(2, handles.size(), "Duplicate calls should produce distinct keys");
        assertTrue(handles.containsKey("agent"), "First call should use plain name");
        assertTrue(handles.containsKey("agent#2"), "Second call should use #2 suffix");
    }

    // ── Window 3 P3: ResilientAgentProxy circuit-open events in all paths ──

    @Test
    void circuitOpenEventEmittedInCallAsync() {
        var transport = mock(AgentTransport.class);
        var innerProxy = new DefaultAgentProxy("agent", "1.0.0", 1, true, transport);
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofSeconds(30), 1));
        cb.recordFailure(); // trip to OPEN

        var listener = new org.atmosphere.coordinator.test.StubActivityListener();
        var proxy = new ResilientAgentProxy(innerProxy, cb, java.util.List.of(listener));

        var future = proxy.callAsync("skill", Map.of());
        var result = future.join();

        assertFalse(result.success());
        var circuitEvents = listener.activitiesFor("agent").stream()
                .filter(a -> a instanceof AgentActivity.CircuitOpen)
                .toList();
        assertFalse(circuitEvents.isEmpty(),
                "callAsync() should emit CircuitOpen activity event");
    }

    @Test
    void circuitOpenEventEmittedInStream() {
        var transport = mock(AgentTransport.class);
        var innerProxy = new DefaultAgentProxy("agent", "1.0.0", 1, true, transport);
        var cb = new DefaultCircuitBreaker(
                new CircuitBreaker.Config(1, Duration.ofSeconds(30), 1));
        cb.recordFailure();

        var listener = new org.atmosphere.coordinator.test.StubActivityListener();
        var proxy = new ResilientAgentProxy(innerProxy, cb, java.util.List.of(listener));

        var completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        proxy.stream("skill", Map.of(), token -> {}, () -> completed.set(true));

        assertTrue(completed.get(), "stream() should complete immediately when circuit open");
        var circuitEvents = listener.activitiesFor("agent").stream()
                .filter(a -> a instanceof AgentActivity.CircuitOpen)
                .toList();
        assertFalse(circuitEvents.isEmpty(),
                "stream() should emit CircuitOpen activity event");
    }
}
