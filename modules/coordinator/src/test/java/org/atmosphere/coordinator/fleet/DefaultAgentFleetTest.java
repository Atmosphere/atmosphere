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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void parallelInterruptsSiblingsOnFirstFailure() throws InterruptedException {
        // Regression: the old parallel() layout called vtExecutor.close() —
        // which blocks until every virtual thread joins — BEFORE calling
        // cancel(true) on the sibling futures. That made the cancel a no-op
        // and left slow agents running to full completion even after a peer
        // had already failed (Correctness Invariant #2).
        //
        // This test wires two agents: `fast` throws immediately, `slow`
        // blocks on a 30-second sleep and flips an interrupted-flag if and
        // only if the thread is interrupted. The fix must make parallel()
        // return in a small bounded time AND flag the slow worker.
        var sleepDurationMs = 30_000L;
        var sleepStarted = new CountDownLatch(1);
        var interruptObserved = new AtomicBoolean(false);
        var sleepDoneOrInterrupted = new CountDownLatch(1);

        var fastTransport = mock(AgentTransport.class);
        when(fastTransport.isAvailable()).thenReturn(true);
        when(fastTransport.send(anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("boom"));

        AgentTransport slowTransport = new AgentTransport() {
            @Override
            public AgentResult send(String agent, String skill,
                                    Map<String, Object> args) {
                sleepStarted.countDown();
                try {
                    Thread.sleep(sleepDurationMs);
                } catch (InterruptedException ie) {
                    interruptObserved.set(true);
                    Thread.currentThread().interrupt();
                    sleepDoneOrInterrupted.countDown();
                    throw new RuntimeException(ie);
                }
                sleepDoneOrInterrupted.countDown();
                return new AgentResult(agent, skill, "ok",
                        Map.of(), Duration.ZERO, true);
            }

            @Override
            public void stream(String agent, String skill,
                               Map<String, Object> args,
                               java.util.function.Consumer<String> onToken,
                               Runnable onComplete) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        var proxies = new LinkedHashMap<String, AgentProxy>();
        // Order matters: `fast` is iterated first by the join loop so its
        // failure triggers cancellation of the slow sibling. If the old
        // layout shipped, this test would hang for ~30s and timeout.
        proxies.put("fast", new DefaultAgentProxy("fast", "1.0.0", 1, true, fastTransport));
        proxies.put("slow", new DefaultAgentProxy("slow", "1.0.0", 1, true, slowTransport));
        var fleet = new DefaultAgentFleet(proxies);

        var start = System.nanoTime();
        var results = fleet.parallel(
                fleet.call("fast", "s", Map.of()),
                fleet.call("slow", "s", Map.of())
        );
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(sleepStarted.await(5, TimeUnit.SECONDS),
                "slow agent must have actually started sleeping");
        assertTrue(sleepDoneOrInterrupted.await(5, TimeUnit.SECONDS),
                "slow agent must have observed cancellation within the 5s window — "
                        + "before the fix it would run to completion at " + sleepDurationMs + "ms");

        assertTrue(elapsedMs < sleepDurationMs / 2,
                "parallel() must return in well under the sleeper's "
                        + sleepDurationMs + "ms sleep — the fix is to cancel siblings "
                        + "WHILE the executor is live. Elapsed: " + elapsedMs + "ms");
        assertTrue(interruptObserved.get(),
                "slow agent's thread must have been interrupted — otherwise the "
                        + "pre-fix no-op cancel pattern has shipped again");

        // Both agents must appear in the results map — parallel() must not
        // return a half-filled map on failure (Correctness Invariant #2).
        assertEquals(2, results.size());
        assertTrue(results.containsKey("fast"));
        assertTrue(results.containsKey("slow"));
        assertFalse(results.get("fast").success());
        assertFalse(results.get("slow").success());
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
