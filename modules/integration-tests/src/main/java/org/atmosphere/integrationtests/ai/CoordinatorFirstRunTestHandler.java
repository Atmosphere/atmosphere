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
package org.atmosphere.integrationtests.ai;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Drives {@link DefaultAgentFleet#parallel} under the first-run-sequential
 * opt-in and reports the observed concurrency + per-dispatch JFR
 * {@code SubAgentDispatch.firstRun} flag back through the websocket.
 *
 * <p>The handler:</p>
 * <ol>
 *   <li>Resets the in-process first-run set so two requests with the same
 *       sub-agent names exercise the cold path each time.</li>
 *   <li>Turns the opt-in on for the warm-up call, off for the parallel
 *       baseline call, so both branches are covered without test runner
 *       ordering coupling.</li>
 *   <li>Captures peak concurrency via a shared in-process probe.</li>
 *   <li>Dumps a JFR recording and reports the boolean breakdown of the
 *       {@code firstRun} flag on dispatch events.</li>
 * </ol>
 */
public class CoordinatorFirstRunTestHandler implements AtmosphereHandler {

    private static final Logger logger =
            LoggerFactory.getLogger(CoordinatorFirstRunTestHandler.class);

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var prompt = resource.getRequest().getReader().readLine();
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        Thread.ofVirtual().name("coord-first-run-test")
                .start(() -> handlePrompt(resource, prompt.trim()));
    }

    private void handlePrompt(AtmosphereResource resource, String mode) {
        var session = StreamingSessions.start(resource);
        Path dump = null;
        var previousOptIn = System.getProperty(
                DefaultAgentFleet.FIRST_RUN_SEQUENTIAL_PROPERTY);
        try {
            DefaultAgentFleet.resetFirstRunStateForTesting();
            var optIn = "warmup".equalsIgnoreCase(mode);
            System.setProperty(DefaultAgentFleet.FIRST_RUN_SEQUENTIAL_PROPERTY,
                    Boolean.toString(optIn));

            var probe = new ConcurrencyProbe();
            // For the parallel baseline path we need a barrier so the
            // dispatches actually overlap on virtual threads — without one,
            // each instant transport completes before the next starts and
            // peak concurrency reads 1 even with the opt-in off.
            if (!optIn) {
                probe.barrier = new CountDownLatch(3);
            }
            var fleet = buildFleet(probe);

            try (var recording = new Recording()) {
                recording.enable("org.atmosphere.ai.SubAgentDispatch");
                recording.start();

                var results = fleet.parallel(
                        fleet.call("alpha", "skill", Map.of()),
                        fleet.call("beta", "skill", Map.of()),
                        fleet.call("gamma", "skill", Map.of()));

                recording.stop();
                dump = Files.createTempFile("atmosphere-coord-e2e-", ".jfr");
                recording.dump(dump);

                session.sendMetadata("ai.coordinator.firstRun.mode", mode);
                session.sendMetadata("ai.coordinator.firstRun.peakConcurrency",
                        probe.peakConcurrency.get());
                session.sendMetadata("ai.coordinator.firstRun.resultCount",
                        results.size());
                emitJfrFirstRunBreakdown(session, dump);
            }
            session.complete();
        } catch (Exception e) {
            logger.error("Coordinator first-run e2e handler failed", e);
            session.error(e);
        } finally {
            if (previousOptIn != null) {
                System.setProperty(DefaultAgentFleet.FIRST_RUN_SEQUENTIAL_PROPERTY,
                        previousOptIn);
            } else {
                System.clearProperty(DefaultAgentFleet.FIRST_RUN_SEQUENTIAL_PROPERTY);
            }
            DefaultAgentFleet.resetFirstRunStateForTesting();
            if (dump != null) {
                try {
                    Files.deleteIfExists(dump);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private static DefaultAgentFleet buildFleet(ConcurrencyProbe probe) {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        for (var name : new String[]{"alpha", "beta", "gamma"}) {
            var transport = new ProbingTransport(name, probe);
            proxies.put(name, new DefaultAgentProxy(name, "1.0.0", 1, true, transport));
        }
        return new DefaultAgentFleet(proxies);
    }

    private static void emitJfrFirstRunBreakdown(org.atmosphere.ai.StreamingSession session,
                                                 Path dump) throws IOException {
        int firstRunTrue = 0;
        int firstRunFalse = 0;
        try (var file = new RecordingFile(dump)) {
            while (file.hasMoreEvents()) {
                var event = file.readEvent();
                if (!"org.atmosphere.ai.SubAgentDispatch".equals(event.getEventType().getName())) {
                    continue;
                }
                if (Boolean.TRUE.equals(event.getValue("firstRun"))) {
                    firstRunTrue++;
                } else {
                    firstRunFalse++;
                }
            }
        }
        session.sendMetadata("ai.coordinator.firstRun.jfr.firstRunTrue", firstRunTrue);
        session.sendMetadata("ai.coordinator.firstRun.jfr.firstRunFalse", firstRunFalse);
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }

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
        public boolean isAvailable() {
            return true;
        }

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
                           Consumer<String> onToken, Runnable onComplete) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }
}
