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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.processor.PromptMethodInvoker;
import org.atmosphere.coordinator.journal.InMemoryCoordinationJournal;
import org.atmosphere.coordinator.journal.JournalingAgentFleet;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A cancelled turn (operator cancel, the user's cancel, the client gone)
 * must stop a {@code @Coordinator}'s plan: once the turn is cancelled the
 * fleet the {@code @Prompt} method received starts no further delegation —
 * sequential call, parallel fan-out, refine loop, route, async or streamed —
 * and the plan's own side-effecting step sees the cancel.
 */
class CoordinatorTurnCancellationTest {

    /** Agents record each dispatch; the research agent's call is where the operator cancels. */
    private static final class RecordingTransport implements AgentTransport {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicBoolean cancelled;
        final String cancelAfter;

        RecordingTransport(AtomicBoolean cancelled, String cancelAfter) {
            this.cancelled = cancelled;
            this.cancelAfter = cancelAfter;
        }

        @Override
        public AgentResult send(String agentName, String skill, Map<String, Object> args) {
            calls.add(agentName + ":" + skill);
            if (agentName.equals(cancelAfter)) {
                cancelled.set(true);
            }
            return new AgentResult(agentName, skill, "GO from " + agentName, Map.of(),
                    Duration.ZERO, true);
        }

        @Override
        public void stream(String agentName, String skill, Map<String, Object> args,
                           Consumer<String> onToken, Runnable onComplete) {
            calls.add(agentName + ":" + skill + ":stream");
            onComplete.run();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /** The startup-team plan shape: research, parallel strategy+finance, writer, route, side effect. */
    public static final class Ceo {
        final List<String> sideEffects = new CopyOnWriteArrayList<>();

        @Prompt
        public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
            fleet = fleet.withActivityListener(activity -> { });
            fleet = fleet.withParentRun("run-1");
            fleet = fleet.withInterceptor(call -> new FleetInterceptor.Decision.Proceed());

            var research = fleet.agent("research-agent").call("web_search", Map.of("q", message));
            var results = fleet.parallel(
                    fleet.call("strategy-agent", "analyze_strategy", Map.of()),
                    fleet.call("finance-agent", "financial_model", Map.of()));
            var report = fleet.refineUntil(fleet.call("writer-agent", "write_report", Map.of()));
            fleet.route(results.get("finance-agent"), route -> route
                    .when(AgentResult::success,
                            f -> f.agent("writer-agent").call("write_report",
                                    Map.of("title", "Risk Assessment")))
                    .otherwise(f -> report));
            fleet.agent("writer-agent").callAsync("write_report", Map.of()).join();
            fleet.agent("writer-agent").stream("write_report", Map.of(), t -> { }, () -> { });
            fleet.pipeline(fleet.call("strategy-agent", "analyze_strategy", Map.of()));
            if (!session.isTurnCancelled()) {
                sideEffects.add("commit_budget:" + research.text());
            }
        }
    }

    private static AgentFleet journalingFleet(AgentTransport transport) {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        for (var name : List.of("research-agent", "strategy-agent", "finance-agent", "writer-agent")) {
            proxies.put(name, new DefaultAgentProxy(name, "1.0.0", 1, true, transport));
        }
        return new JournalingAgentFleet(new DefaultAgentFleet(proxies),
                new InMemoryCoordinationJournal(), "ceo");
    }

    private static StreamingSession sessionCancelledBy(AtomicBoolean cancelled) {
        var session = mock(StreamingSession.class);
        when(session.isTurnCancelled()).thenAnswer(inv -> cancelled.get());
        when(session.sessionId()).thenReturn("s-1");
        return session;
    }

    private static void runPlan(Ceo ceo, AgentFleet fleet, StreamingSession session) throws Exception {
        var method = Ceo.class.getMethod("onPrompt", String.class, AgentFleet.class,
                StreamingSession.class);
        PromptMethodInvoker.forMethod(ceo, method)
                .invoke("AI fitness apps", session, null, Map.of(AgentFleet.class, fleet));
    }

    @Test
    void cancelDuringTheFirstDelegationStopsEveryLaterDelegation() throws Exception {
        var cancelled = new AtomicBoolean();
        var transport = new RecordingTransport(cancelled, "research-agent");
        var ceo = new Ceo();

        runPlan(ceo, journalingFleet(transport), sessionCancelledBy(cancelled));

        assertEquals(List.of("research-agent:web_search"), transport.calls,
                "after the cancel no delegation may start: " + transport.calls);
        assertTrue(ceo.sideEffects.isEmpty(), "the plan's side effect ran after the cancel");
    }

    @Test
    void cancelBetweenDelegationsStopsTheRest() throws Exception {
        var cancelled = new AtomicBoolean();
        var transport = new RecordingTransport(cancelled, "finance-agent");
        var ceo = new Ceo();

        runPlan(ceo, journalingFleet(transport), sessionCancelledBy(cancelled));

        assertFalse(transport.calls.stream().anyMatch(c -> c.startsWith("writer-agent")),
                "the writer was dispatched after the cancel: " + transport.calls);
        assertTrue(ceo.sideEffects.isEmpty(), "the plan's side effect ran after the cancel");
    }

    @Test
    void aTurnNotCancelledRunsTheWholePlan() throws Exception {
        var cancelled = new AtomicBoolean();
        var transport = new RecordingTransport(cancelled, "nobody");
        var ceo = new Ceo();

        runPlan(ceo, journalingFleet(transport), sessionCancelledBy(cancelled));

        assertTrue(transport.calls.contains("writer-agent:write_report:stream"), transport.calls::toString);
        assertEquals(List.of("commit_budget:GO from research-agent"), ceo.sideEffects);
    }

    @Test
    void cancelledFleetAnswersFailedResultsWithoutDispatching() {
        var cancelled = new AtomicBoolean(true);
        var transport = new RecordingTransport(cancelled, "nobody");
        var fleet = new DefaultAgentFleet(Map.of("research-agent",
                new DefaultAgentProxy("research-agent", "1.0.0", 1, true, transport)))
                .forTurn(sessionCancelledBy(cancelled));

        var result = fleet.agent("research-agent").call("web_search", Map.of());

        assertFalse(result.success());
        assertTrue(result.text().contains("cancelled"), result.text());
        assertTrue(transport.calls.isEmpty(), transport.calls::toString);
    }

    @Test
    void aDelegationInFlightIsInterruptedWhenTheTurnIsCancelled() throws Exception {
        // The agent's run is in flight (its model call blocks) when the
        // operator cancels: the worker is interrupted — the signal the AI
        // pipeline turns into its runtime's cancel — and the call returns
        // cancelled instead of waiting for the agent.
        var cancelled = new AtomicBoolean();
        var started = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.CountDownLatch(1);
        AgentTransport blocking = new AgentTransport() {
            @Override
            public AgentResult send(String agentName, String skill, Map<String, Object> args) {
                started.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return new AgentResult(agentName, skill, "late", Map.of(), Duration.ZERO, true);
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
        };
        var fleet = new DefaultAgentFleet(Map.of("writer-agent",
                new DefaultAgentProxy("writer-agent", "1.0.0", 1, true, blocking)))
                .forTurn(sessionCancelledBy(cancelled));
        Thread.startVirtualThread(() -> {
            try {
                started.await(5, java.util.concurrent.TimeUnit.SECONDS);
                cancelled.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var begin = System.nanoTime();
        var result = fleet.agent("writer-agent").call("write_report", Map.of());
        var elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertFalse(result.success(), result.text());
        assertTrue(result.text().contains("cancelled"), result.text());
        assertTrue(elapsedMs < 5_000, "the call waited for the agent after the cancel: " + elapsedMs + "ms");
        assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the agent's in-flight run was not interrupted");
    }

    @Test
    void aProxyWithoutItsOwnCancellationIsWrapped() {
        var cancelled = new AtomicBoolean();
        var delegate = mock(AgentProxy.class);
        when(delegate.name()).thenReturn("custom");
        when(delegate.call("s", Map.of())).thenReturn(
                new AgentResult("custom", "s", "ran", Map.of(), Duration.ZERO, true));
        // A Mockito mock does not run default methods; build the default view directly.
        AgentProxy proxy = new CancellableAgentProxy(delegate, cancelled::get);

        assertEquals("ran", proxy.call("s", Map.of()).text());
        cancelled.set(true);
        assertFalse(proxy.call("s", Map.of()).success());
        assertFalse(proxy.callAsync("s", Map.of()).join().success());
    }
}
