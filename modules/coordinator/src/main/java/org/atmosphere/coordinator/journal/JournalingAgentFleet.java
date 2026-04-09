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
package org.atmosphere.coordinator.journal;

import org.atmosphere.coordinator.evaluation.Evaluation;
import org.atmosphere.coordinator.fleet.AgentActivity;
import org.atmosphere.coordinator.fleet.AgentActivityListener;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.RoutingSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Transparent decorator around {@link AgentFleet} that records coordination
 * events to a {@link CoordinationJournal}. Also triggers auto-evaluation
 * after each successful agent call.
 *
 * <p>Follows the decorator pattern used by {@code MemoryCapturingSession}
 * and {@code MetricsCapturingSession} in the AI module.</p>
 */
public final class JournalingAgentFleet implements AgentFleet, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(JournalingAgentFleet.class);

    private final AgentFleet delegate;
    private final CoordinationJournal journal;
    private final String coordinatorName;
    // Single-threaded: serialize eval calls to avoid rate-limiting LLM APIs
    private final ExecutorService evalExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("eval-", 0).factory());
    private final ThreadLocal<String> activeCoordinationId = new ThreadLocal<>();

    public JournalingAgentFleet(AgentFleet delegate, CoordinationJournal journal,
                                String coordinatorName) {
        this.delegate = delegate;
        this.journal = journal;
        this.coordinatorName = coordinatorName;
    }

    /**
     * Shuts down the background evaluation executor. Should be called when
     * the coordinator is being unregistered or the application is stopping.
     */
    @Override
    public void close() {
        evalExecutor.close();
    }

    @Override
    public AgentProxy agent(String name) {
        return new JournalingAgentProxy(delegate.agent(name), coordinationId());
    }

    @Override
    public List<AgentProxy> agents() {
        return delegate.agents();
    }

    @Override
    public List<AgentProxy> available() {
        return delegate.available();
    }

    @Override
    public AgentCall call(String agentName, String skill, Map<String, Object> args) {
        return delegate.call(agentName, skill, args);
    }

    @Override
    public Map<String, AgentResult> parallel(AgentCall... calls) {
        var coordId = coordinationId();
        var start = Instant.now();
        try {
            journal.record(new CoordinationEvent.CoordinationStarted(
                    coordId, coordinatorName, start));

            for (var agentCall : calls) {
                journal.record(new CoordinationEvent.AgentDispatched(
                        coordId, agentCall.agentName(), agentCall.skill(),
                        agentCall.args(), Instant.now()));
            }

            var results = delegate.parallel(calls);

            int idx = 0;
            for (var entry : results.entrySet()) {
                var result = entry.getValue();
                recordResult(coordId, result);
                autoEvaluate(coordId, result, idx < calls.length ? calls[idx] : null);
                idx++;
            }

            journal.record(new CoordinationEvent.CoordinationCompleted(
                    coordId, Duration.between(start, Instant.now()),
                    calls.length, Instant.now()));

            return results;
        } finally {
            activeCoordinationId.remove();
        }
    }

    @Override
    public AgentResult pipeline(AgentCall... calls) {
        var coordId = coordinationId();
        var start = Instant.now();
        try {
            journal.record(new CoordinationEvent.CoordinationStarted(
                    coordId, coordinatorName, start));

            AgentResult last = null;
            for (var agentCall : calls) {
                // Merge previous result into args so pipeline steps can chain
                var args = agentCall.args();
                if (last != null) {
                    var merged = new java.util.LinkedHashMap<>(args);
                    merged.put("_previous_result", last.text());
                    args = Map.copyOf(merged);
                }

                journal.record(new CoordinationEvent.AgentDispatched(
                        coordId, agentCall.agentName(), agentCall.skill(),
                        args, Instant.now()));

                var proxy = delegate.agent(agentCall.agentName());
                last = proxy.call(agentCall.skill(), args);
                recordResult(coordId, last);
                autoEvaluate(coordId, last, agentCall);

                if (!last.success()) {
                    break;
                }
            }

            var completedCount = last != null
                    ? (int) results(calls, last)
                    : 0;
            journal.record(new CoordinationEvent.CoordinationCompleted(
                    coordId, Duration.between(start, Instant.now()),
                    completedCount, Instant.now()));

            return last;
        } finally {
            activeCoordinationId.remove();
        }
    }

    @Override
    public AgentResult route(AgentResult input, Consumer<RoutingSpec> spec) {
        var coordId = coordinationId();
        var routing = new RoutingSpec();
        spec.accept(routing);
        var outcome = routing.evaluate(input, this);

        journal.record(new CoordinationEvent.RouteEvaluated(
                coordId,
                input.agentName(),
                outcome.matchedIndex(),
                outcome.result().agentName(),
                outcome.matched(),
                Instant.now()));

        return outcome.result();
    }

    @Override
    public List<Evaluation> evaluate(AgentResult result, AgentCall originalCall) {
        return delegate.evaluate(result, originalCall);
    }

    @Override
    public CoordinationJournal journal() {
        return journal;
    }

    @Override
    public AgentFleet withActivityListener(AgentActivityListener listener) {
        return new JournalingAgentFleet(
                delegate.withActivityListener(listener), journal, coordinatorName);
    }

    private String coordinationId() {
        var id = activeCoordinationId.get();
        if (id == null) {
            id = UUID.randomUUID().toString();
            activeCoordinationId.set(id);
        }
        return id;
    }

    private void recordResult(String coordId, AgentResult result) {
        if (result.success()) {
            journal.record(new CoordinationEvent.AgentCompleted(
                    coordId, result.agentName(), result.skillId(),
                    result.text(), result.duration(), Instant.now()));
        } else {
            journal.record(new CoordinationEvent.AgentFailed(
                    coordId, result.agentName(), result.skillId(),
                    result.text(), result.duration(), Instant.now()));
        }
    }

    private void autoEvaluate(String coordId, AgentResult result, AgentCall call) {
        if (!result.success() || call == null) {
            return;
        }
        // Run on a single virtual thread (not parallel) to avoid rate-limiting LLM APIs
        CompletableFuture.runAsync(() -> {
            try {
                var evaluations = delegate.evaluate(result, call);
                for (var eval : evaluations) {
                    var evalName = eval.metadata().getOrDefault("evaluator",
                            "auto").toString();
                    journal.record(new CoordinationEvent.AgentEvaluated(
                            coordId, result.agentName(), evalName,
                            eval.score(), eval.passed(), eval.reason(),
                            Instant.now()));
                    emitEvalActivity(result.agentName(), evalName, eval);
                }
            } catch (Exception e) {
                logger.debug("Auto-evaluation failed for agent '{}'",
                        result.agentName(), e);
            }
        }, evalExecutor);
    }

    private void emitEvalActivity(String agentName, String evaluatorName,
                                   Evaluation eval) {
        var activity = new AgentActivity.Evaluated(
                agentName, evaluatorName, eval.score(),
                eval.passed(), eval.reason());
        // Get activity listeners from the delegate fleet
        if (delegate instanceof DefaultAgentFleet daf) {
            for (var listener : daf.activityListeners()) {
                try {
                    listener.onActivity(activity);
                } catch (Exception e) {
                    logger.trace("Activity listener failed for eval of '{}'", agentName, e);
                }
            }
        }
    }

    private static long results(AgentCall[] calls, AgentResult last) {
        // Count how many calls completed (up to and including the last)
        long count = 0;
        for (var c : calls) {
            count++;
            if (c.agentName().equals(last.agentName())) {
                break;
            }
        }
        return count;
    }

    /**
     * Wraps an {@link AgentProxy} to journal individual call/callAsync/stream.
     */
    private final class JournalingAgentProxy implements AgentProxy {

        private final AgentProxy delegate;
        private final String coordId;

        JournalingAgentProxy(AgentProxy delegate, String coordId) {
            this.delegate = delegate;
            this.coordId = coordId;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String version() {
            return delegate.version();
        }

        @Override
        public boolean isAvailable() {
            return delegate.isAvailable();
        }

        @Override
        public int weight() {
            return delegate.weight();
        }

        @Override
        public boolean isLocal() {
            return delegate.isLocal();
        }

        @Override
        public AgentResult call(String skill, Map<String, Object> args) {
            journal.record(new CoordinationEvent.AgentDispatched(
                    coordId, delegate.name(), skill, args, Instant.now()));

            var result = delegate.call(skill, args);
            recordResult(coordId, result);
            autoEvaluate(coordId, result,
                    new org.atmosphere.coordinator.fleet.AgentCall(
                            delegate.name(), skill, args));
            return result;
        }

        @Override
        public CompletableFuture<AgentResult> callAsync(String skill,
                                                         Map<String, Object> args) {
            journal.record(new CoordinationEvent.AgentDispatched(
                    coordId, delegate.name(), skill, args, Instant.now()));

            return delegate.callAsync(skill, args)
                    .whenComplete((result, error) -> {
                        if (result != null) {
                            recordResult(coordId, result);
                        } else if (error != null) {
                            journal.record(new CoordinationEvent.AgentFailed(
                                    coordId, delegate.name(), skill,
                                    error.getMessage(), Duration.ZERO, Instant.now()));
                        }
                    });
        }

        @Override
        public void stream(String skill, Map<String, Object> args,
                           Consumer<String> onToken, Runnable onComplete) {
            journal.record(new CoordinationEvent.AgentDispatched(
                    coordId, delegate.name(), skill, args, Instant.now()));

            var start = Instant.now();
            delegate.stream(skill, args, onToken, () -> {
                journal.record(new CoordinationEvent.AgentCompleted(
                        coordId, delegate.name(), skill, "(streamed)",
                        Duration.between(start, Instant.now()), Instant.now()));
                onComplete.run();
            });
        }
    }
}
