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

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cold-start warm-up gate added on top of
 * {@link DefaultAgentFleet#parallel}. The first dispatch of each sub-agent
 * runs sequentially so {@code ToolPermission.CONFIRM} prompts cannot race
 * across virtual threads; subsequent dispatches fan out in parallel.
 */
class DefaultAgentFleetFirstRunTest {

    @BeforeEach
    void clear() {
        DefaultAgentFleet.resetFirstRunStateForTesting();
        // Default is opt-in OFF; tests that exercise the warm-up path turn it
        // on explicitly. The opt-out case clears the property to confirm
        // absent-property behavior is parallel.
        System.setProperty(DefaultAgentFleet.FIRST_RUN_SEQUENTIAL_PROPERTY, "true");
    }

    @AfterEach
    void cleanup() {
        DefaultAgentFleet.resetFirstRunStateForTesting();
        System.clearProperty(DefaultAgentFleet.FIRST_RUN_SEQUENTIAL_PROPERTY);
    }

    @Test
    void firstParallelCallRunsSequentially() {
        var probe = new ConcurrencyProbe();
        var fleet = newFleetWithProbe(probe, "alpha", "beta", "gamma");

        var results = fleet.parallel(
                fleet.call("alpha", "skill", Map.of()),
                fleet.call("beta", "skill", Map.of()),
                fleet.call("gamma", "skill", Map.of()));

        assertEquals(3, results.size());
        assertEquals(1, probe.peakConcurrency.get(),
                "cold-start warm-up must dispatch sub-agents one at a time");
    }

    @Test
    void secondParallelCallFansOutInParallel() throws Exception {
        var warmup = new ConcurrencyProbe();
        var warmupFleet = newFleetWithProbe(warmup, "alpha", "beta", "gamma");
        warmupFleet.parallel(
                warmupFleet.call("alpha", "skill", Map.of()),
                warmupFleet.call("beta", "skill", Map.of()),
                warmupFleet.call("gamma", "skill", Map.of()));

        var parallel = new ConcurrencyProbe();
        parallel.barrier = new CountDownLatch(3);
        var fleet = newFleetWithProbe(parallel, "alpha", "beta", "gamma");
        var results = fleet.parallel(
                fleet.call("alpha", "skill", Map.of()),
                fleet.call("beta", "skill", Map.of()),
                fleet.call("gamma", "skill", Map.of()));

        assertEquals(3, results.size());
        assertEquals(3, parallel.peakConcurrency.get(),
                "after warm-up the dispatches must run concurrently");
    }

    @Test
    void absentPropertyDispatchesInParallel() {
        // Clear the opt-in so the default-OFF behavior takes over.
        System.clearProperty(DefaultAgentFleet.FIRST_RUN_SEQUENTIAL_PROPERTY);
        var probe = new ConcurrencyProbe();
        probe.barrier = new CountDownLatch(3);
        var fleet = newFleetWithProbe(probe, "alpha", "beta", "gamma");

        var results = fleet.parallel(
                fleet.call("alpha", "skill", Map.of()),
                fleet.call("beta", "skill", Map.of()),
                fleet.call("gamma", "skill", Map.of()));

        assertEquals(3, results.size());
        assertEquals(3, probe.peakConcurrency.get(),
                "with the opt-in unset, dispatches must run in parallel from the cold start");
    }

    @Test
    void explicitFalseDispatchesInParallel() {
        System.setProperty(DefaultAgentFleet.FIRST_RUN_SEQUENTIAL_PROPERTY, "false");
        var probe = new ConcurrencyProbe();
        probe.barrier = new CountDownLatch(3);
        var fleet = newFleetWithProbe(probe, "alpha", "beta", "gamma");

        var results = fleet.parallel(
                fleet.call("alpha", "skill", Map.of()),
                fleet.call("beta", "skill", Map.of()),
                fleet.call("gamma", "skill", Map.of()));

        assertEquals(3, results.size());
        assertEquals(3, probe.peakConcurrency.get(),
                "explicit opt-out must defeat the warm-up and dispatch in parallel");
    }

    @Test
    void coldStartEmitsFirstRunJfrEvents() throws Exception {
        var probe = new ConcurrencyProbe();
        var fleet = newFleetWithProbe(probe, "alpha", "beta");

        var events = recordSubAgentDispatch(() -> {
            fleet.parallel(
                    fleet.call("alpha", "skill", Map.of()),
                    fleet.call("beta", "skill", Map.of()));
        });

        assertEquals(2, events.size());
        for (var event : events) {
            assertTrue((boolean) event.getValue("firstRun"),
                    "cold-start dispatches must report firstRun=true");
            assertTrue((boolean) event.getValue("success"));
        }
    }

    @Test
    void failedFirstRunDoesNotMarkAsCompleted() {
        var failingProxy = failingProxy("flaky");
        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("flaky", failingProxy);
        var fleet = new DefaultAgentFleet(proxies);

        var first = fleet.parallel(fleet.call("flaky", "skill", Map.of()));
        assertFalse(first.get("flaky").success());

        var probe = new ConcurrencyProbe();
        var retryProxies = new LinkedHashMap<String, AgentProxy>();
        retryProxies.put("flaky", newProxyWithProbe("flaky", probe));
        var retryFleet = new DefaultAgentFleet(retryProxies);

        retryFleet.parallel(retryFleet.call("flaky", "skill", Map.of()));
        assertEquals(1, probe.peakConcurrency.get(),
                "after a failed warm-up the next dispatch must still go through the sequential path");
    }

    private DefaultAgentFleet newFleetWithProbe(ConcurrencyProbe probe, String... names) {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        for (var name : names) {
            proxies.put(name, newProxyWithProbe(name, probe));
        }
        return new DefaultAgentFleet(proxies);
    }

    private AgentProxy newProxyWithProbe(String name, ConcurrencyProbe probe) {
        var transport = new ProbingTransport(name, probe);
        return new DefaultAgentProxy(name, "1.0.0", 1, true, transport);
    }

    private AgentProxy failingProxy(String name) {
        var transport = new AgentTransport() {
            @Override
            public boolean isAvailable() { return true; }
            @Override
            public AgentResult send(String agentName, String skillId, Map<String, Object> args) {
                return AgentResult.failure(agentName, skillId, "boom", Duration.ZERO);
            }
            @Override
            public void stream(String agentName, String skillId, Map<String, Object> args,
                               java.util.function.Consumer<String> onToken, Runnable onComplete) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        return new DefaultAgentProxy(name, "1.0.0", 1, true, transport);
    }

    private static List<RecordedEvent> recordSubAgentDispatch(Runnable body) throws Exception {
        try (var recording = new Recording()) {
            recording.enable("org.atmosphere.ai.SubAgentDispatch");
            recording.start();
            body.run();
            recording.stop();
            var dump = Files.createTempFile("atmosphere-coord-test-", ".jfr");
            try {
                recording.dump(dump);
                var collected = new ArrayList<RecordedEvent>();
                try (var file = new RecordingFile(dump)) {
                    while (file.hasMoreEvents()) {
                        var event = file.readEvent();
                        if ("org.atmosphere.ai.SubAgentDispatch".equals(event.getEventType().getName())) {
                            collected.add(event);
                        }
                    }
                }
                return collected;
            } finally {
                Files.deleteIfExists(dump);
            }
        }
    }

    /**
     * Tracks the maximum number of dispatches running concurrently. Tests
     * inspect {@link #peakConcurrency} to distinguish sequential warm-up
     * (=1) from parallel fan-out (>1). The optional {@link #barrier} forces
     * concurrent callers to wait for each other so the parallel case can be
     * detected even when individual dispatches finish near-instantly.
     */
    private static final class ConcurrencyProbe {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peakConcurrency = new AtomicInteger();
        volatile CountDownLatch barrier;

        void onEnter() {
            var current = inFlight.incrementAndGet();
            peakConcurrency.accumulateAndGet(current, Math::max);
            var b = barrier;
            if (b != null) {
                b.countDown();
                try {
                    b.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void onExit() {
            inFlight.decrementAndGet();
        }
    }

    private static final class ProbingTransport implements AgentTransport {
        private final String agentName;
        private final ConcurrencyProbe probe;

        ProbingTransport(String agentName, ConcurrencyProbe probe) {
            this.agentName = agentName;
            this.probe = probe;
        }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public AgentResult send(String name, String skillId, Map<String, Object> args) {
            probe.onEnter();
            try {
                return new AgentResult(name, skillId, "ok-" + agentName,
                        Map.of(), Duration.ofMillis(1), true);
            } finally {
                probe.onExit();
            }
        }

        @Override
        public void stream(String name, String skillId, Map<String, Object> args,
                           java.util.function.Consumer<String> onToken, Runnable onComplete) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }
}
