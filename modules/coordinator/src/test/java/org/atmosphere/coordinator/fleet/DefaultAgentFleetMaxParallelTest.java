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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the fleet-wide dispatch cap: {@code parallel()} used to
 * start one virtual thread per call with no bound, so a wide fan-out (or many
 * concurrent fan-outs) opened unlimited simultaneous downstream requests.
 */
class DefaultAgentFleetMaxParallelTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(DefaultAgentFleet.MAX_PARALLEL_PROPERTY);
    }

    /** Transport that records peak concurrency and holds each call until released. */
    private static final class GatedTransport implements AgentTransport {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final Set<String> entered = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final CountDownLatch release;
        final long holdMs;

        GatedTransport(CountDownLatch release, long holdMs) {
            this.release = release;
            this.holdMs = holdMs;
        }

        @Override
        public AgentResult send(String agentName, String skill, Map<String, Object> args) {
            calls.incrementAndGet();
            entered.add(agentName);
            var now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                if (release != null) {
                    release.await(10, TimeUnit.SECONDS);
                }
                Thread.sleep(holdMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new AgentResult(agentName, skill, "ok", Map.of(), Duration.ZERO, true);
        }

        @Override
        public void stream(String agentName, String skill, Map<String, Object> args,
                           Consumer<String> onToken, Runnable onComplete) {
            onComplete.run();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private static DefaultAgentFleet fleet(AgentTransport transport, int agents,
                                           long timeoutMs, int maxParallel) {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        for (int i = 0; i < agents; i++) {
            proxies.put("a" + i, new DefaultAgentProxy("a" + i, "1.0.0", 1, true, transport));
        }
        return new DefaultAgentFleet(proxies, List.of(), timeoutMs, List.of(), maxParallel);
    }

    private static AgentCall[] calls(int agents) {
        var out = new AgentCall[agents];
        for (int i = 0; i < agents; i++) {
            out[i] = new AgentCall("a" + i, "s", Map.of());
        }
        return out;
    }

    @Test
    void parallelNeverExceedsMaxParallel() {
        var transport = new GatedTransport(null, 50);
        var fleet = fleet(transport, 12, 10_000, 3);

        var results = fleet.parallel(calls(12));

        assertEquals(12, results.size());
        assertTrue(results.values().stream().allMatch(AgentResult::success));
        assertEquals(12, transport.calls.get());
        assertTrue(transport.peak.get() <= 3, "peak concurrency " + transport.peak.get());
        assertEquals(3, transport.peak.get(), "the cap should be reached, not undershot");
    }

    @Test
    void callQueuedPastTimeoutGetsFailureResultNotDropped() {
        // "slow" holds the only permit for 1s under a 5s limit; "queued" has a
        // 300ms limit, so it expires while still waiting for the permit.
        var transport = new GatedTransport(null, 1_000);
        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("slow", new DefaultAgentProxy("slow", "1.0.0", 1, true, 0, transport,
                List.of(), AgentLimits.withTimeout(Duration.ofSeconds(5))));
        proxies.put("queued", new DefaultAgentProxy("queued", "1.0.0", 1, true, 0, transport,
                List.of(), AgentLimits.withTimeout(Duration.ofMillis(300))));
        var fleet = new DefaultAgentFleet(proxies, List.of(), 10_000, List.of(), 1);

        var results = fleet.parallel(new AgentCall("slow", "s", Map.of()),
                new AgentCall("queued", "s", Map.of()));

        assertEquals(2, results.size(), "every call must have a result");
        assertTrue(results.get("slow").success(), results.toString());
        var queued = results.get("queued");
        assertFalse(queued.success());
        assertTrue(queued.text().contains("without a dispatch slot (maxParallel=1)"),
                queued.text());
        assertEquals(Set.of("slow"), transport.entered,
                "the queued call must never reach the transport");
    }

    @Test
    void boundIsSharedAcrossConcurrentParallelCallsAndDerivedFleets() throws Exception {
        var transport = new GatedTransport(null, 40);
        var base = fleet(transport, 4, 10_000, 2);
        var derived = base.withParentRun("run-1");

        var threads = new ArrayList<Thread>();
        for (var f : List.of(base, derived, base)) {
            threads.add(Thread.ofVirtual().start(() -> f.parallel(calls(4))));
        }
        for (var t : threads) {
            t.join(10_000);
        }

        assertEquals(12, transport.calls.get());
        assertTrue(transport.peak.get() <= 2, "peak concurrency " + transport.peak.get());
    }

    @Test
    void parallelCancellableHonorsTheSameBound() {
        var transport = new GatedTransport(null, 40);
        var fleet = fleet(transport, 8, 10_000, 2);

        var handles = fleet.parallelCancellable(calls(8));
        for (var h : handles.values()) {
            assertTrue(((AgentExecution.Running) h).join(Duration.ofSeconds(10)).success());
        }

        assertEquals(8, transport.calls.get());
        assertTrue(transport.peak.get() <= 2, "peak concurrency " + transport.peak.get());
    }

    @Test
    void cancellingAQueuedHandleNeitherLeaksNorDoubleReleasesAPermit() throws Exception {
        var release = new CountDownLatch(1);
        var transport = new GatedTransport(release, 30);
        var fleet = fleet(transport, 2, 2_000, 1);

        var handles = fleet.parallelCancellable(calls(2));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (transport.entered.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, transport.entered.size(), "exactly one call may hold the single permit");
        var runningName = transport.entered.iterator().next();
        var queuedName = runningName.equals("a0") ? "a1" : "a0";
        var queued = (AgentExecution.Running) handles.get(queuedName);
        assertFalse(queued.isDone());
        assertTrue(queued.cancel());

        release.countDown();
        assertTrue(((AgentExecution.Running) handles.get(runningName))
                .join(Duration.ofSeconds(10)).success());
        assertEquals(1, transport.calls.get(), "the cancelled call must never reach the transport");

        // Same fleet, same semaphore: a leaked permit would make these calls
        // queue past the 2s timeout; a double release would let two run at once.
        var results = fleet.parallel(calls(2));
        assertTrue(results.values().stream().allMatch(AgentResult::success), results.toString());
        assertEquals(1, transport.peak.get());
    }

    @Test
    void systemPropertyAndDefaultResolution() {
        var transport = new GatedTransport(null, 0);
        assertEquals(DefaultAgentFleet.DEFAULT_MAX_PARALLEL,
                fleet(transport, 1, 1000, 0).maxParallel());
        System.setProperty(DefaultAgentFleet.MAX_PARALLEL_PROPERTY, "5");
        assertEquals(5, fleet(transport, 1, 1000, 0).maxParallel());
        assertEquals(7, fleet(transport, 1, 1000, 7).maxParallel(), "explicit limit wins");
        System.setProperty(DefaultAgentFleet.MAX_PARALLEL_PROPERTY, "nope");
        assertEquals(DefaultAgentFleet.DEFAULT_MAX_PARALLEL,
                fleet(transport, 1, 1000, 0).maxParallel());
    }
}
