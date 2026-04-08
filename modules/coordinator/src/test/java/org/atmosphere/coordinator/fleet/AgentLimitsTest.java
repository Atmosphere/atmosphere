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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLimitsTest {

    @Test
    void defaultLimitsHas120sTimeout() {
        assertEquals(Duration.ofSeconds(120), AgentLimits.DEFAULT.timeout());
        assertEquals(Integer.MAX_VALUE, AgentLimits.DEFAULT.maxTurns());
        assertTrue(AgentLimits.DEFAULT.isDefaultTimeout());
    }

    @Test
    void withTimeoutCreatesCustomLimit() {
        var limits = AgentLimits.withTimeout(Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), limits.timeout());
        assertFalse(limits.isDefaultTimeout());
    }

    @Test
    void proxyExposesLimits() {
        var transport = mock(AgentTransport.class);
        var limits = AgentLimits.withTimeout(Duration.ofSeconds(5));
        var proxy = new DefaultAgentProxy("test", "1.0.0", 1, true, 0,
                transport, List.of(), limits);
        assertEquals(limits, proxy.limits());
        assertFalse(proxy.limits().isDefaultTimeout());
    }

    @Test
    void proxyDefaultLimitsWhenNotSpecified() {
        var transport = mock(AgentTransport.class);
        var proxy = new DefaultAgentProxy("test", "1.0.0", 1, true, transport);
        assertEquals(AgentLimits.DEFAULT, proxy.limits());
    }

    @Test
    void callWithHandleReturnsRunningExecution() {
        var transport = mock(AgentTransport.class);
        var expected = new AgentResult("w", "s", "ok", Map.of(), Duration.ZERO, true);
        when(transport.send("w", "s", Map.of())).thenReturn(expected);

        var proxy = new DefaultAgentProxy("w", "1.0.0", 1, true, transport);
        var execution = proxy.callWithHandle("s", Map.of());

        assertInstanceOf(AgentExecution.Running.class, execution);
        assertEquals("w", execution.agentName());

        var running = (AgentExecution.Running) execution;
        var result = running.join(Duration.ofSeconds(5));
        assertTrue(result.success());
        assertEquals("ok", result.text());
        assertTrue(running.isDone());
    }

    @Test
    void callWithHandleCancellation() {
        var transport = mock(AgentTransport.class);
        // Simulate a slow agent
        when(transport.send("slow", "work", Map.of())).thenAnswer(inv -> {
            Thread.sleep(5000);
            return new AgentResult("slow", "work", "done", Map.of(), Duration.ZERO, true);
        });

        var proxy = new DefaultAgentProxy("slow", "1.0.0", 1, true, transport);
        var execution = proxy.callWithHandle("work", Map.of());

        var running = (AgentExecution.Running) execution;
        assertTrue(running.cancel());

        // Join should return quickly with a failure or cancellation
        var result = running.join(Duration.ofSeconds(1));
        assertFalse(result.success());
    }

    @Test
    void callWithHandleTimeoutOnJoin() {
        var transport = mock(AgentTransport.class);
        when(transport.send("slow", "work", Map.of())).thenAnswer(inv -> {
            Thread.sleep(5000);
            return new AgentResult("slow", "work", "done", Map.of(), Duration.ZERO, true);
        });

        var proxy = new DefaultAgentProxy("slow", "1.0.0", 1, true, transport);
        var execution = proxy.callWithHandle("work", Map.of());

        var running = (AgentExecution.Running) execution;
        var result = running.join(Duration.ofMillis(100));
        assertFalse(result.success());
        assertTrue(result.text().contains("Timed out"));
    }

    @Test
    void parallelUsesPerAgentTimeout() {
        // Agent with short timeout should fail faster than fleet default
        var fastTransport = mock(AgentTransport.class);
        when(fastTransport.send("fast", "work", Map.of()))
                .thenReturn(new AgentResult("fast", "work", "ok", Map.of(), Duration.ZERO, true));
        when(fastTransport.isAvailable()).thenReturn(true);

        var slowTransport = mock(AgentTransport.class);
        when(slowTransport.send("slow", "work", Map.of())).thenAnswer(inv -> {
            Thread.sleep(5000);
            return new AgentResult("slow", "work", "done", Map.of(), Duration.ZERO, true);
        });
        when(slowTransport.isAvailable()).thenReturn(true);

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("fast", new DefaultAgentProxy("fast", "1.0.0", 1, true, 0,
                fastTransport, List.of(), AgentLimits.DEFAULT));
        // slow agent has 200ms timeout
        proxies.put("slow", new DefaultAgentProxy("slow", "1.0.0", 1, true, 0,
                slowTransport, List.of(), AgentLimits.withTimeout(Duration.ofMillis(200))));

        var fleet = new DefaultAgentFleet(proxies);
        var results = fleet.parallel(
                fleet.call("fast", "work", Map.of()),
                fleet.call("slow", "work", Map.of())
        );

        assertTrue(results.get("fast").success());
        assertFalse(results.get("slow").success(),
                "Slow agent should timeout with per-agent limit");
        assertTrue(results.get("slow").text().contains("timed out"),
                "Error should mention timeout but got: " + results.get("slow").text());
    }

    @Test
    void parallelCancellableReturnsHandles() {
        var transport = mock(AgentTransport.class);
        when(transport.send("a", "work", Map.of()))
                .thenReturn(new AgentResult("a", "work", "ok", Map.of(), Duration.ZERO, true));
        when(transport.isAvailable()).thenReturn(true);

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("a", new DefaultAgentProxy("a", "1.0.0", 1, true, transport));

        var fleet = new DefaultAgentFleet(proxies);
        var handles = fleet.parallelCancellable(
                fleet.call("a", "work", Map.of())
        );

        assertEquals(1, handles.size());
        var handle = handles.get("a");
        assertInstanceOf(AgentExecution.Running.class, handle);

        var result = ((AgentExecution.Running) handle).join(Duration.ofSeconds(5));
        assertTrue(result.success());
    }

    @Test
    void agentExecutionDoneVariant() {
        var result = new AgentResult("a", "s", "ok", Map.of(), Duration.ZERO, true);
        var done = new AgentExecution.Done("a", result);

        assertEquals("a", done.agentName());
        assertEquals(result, done.result());
    }

    @Test
    void sealedInterfacePermitsAllVariants() {
        AgentExecution exec = new AgentExecution.Done("a",
                new AgentResult("a", "s", "ok", Map.of(), Duration.ZERO, true));
        var matched = switch (exec) {
            case AgentExecution.Running ignored -> false;
            case AgentExecution.Done ignored -> true;
        };
        assertTrue(matched);
    }
}
