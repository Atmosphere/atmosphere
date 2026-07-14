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

import org.atmosphere.coordinator.commitment.CommitmentRecord;
import org.atmosphere.coordinator.commitment.CommitmentRecordsFlag;
import org.atmosphere.coordinator.commitment.CommitmentSigner;
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
    /**
     * Optional explicit coordination id. When non-null, overrides the default
     * derived from {@link #coordinatorName}. Used by {@code CoordinationFork}
     * to scope a re-execution under a fresh (forked) coordination id without
     * mutating the original coordinator's identity.
     */
    private final String coordinationIdOverride;
    // Single-threaded: serialize eval calls to avoid rate-limiting LLM APIs
    private final ExecutorService evalExecutor;
    private final boolean ownsExecutor;
    /**
     * Optional commitment-record signer. When non-null and non-UNSIGNED,
     * every dispatch emits a signed {@link CommitmentRecord} alongside
     * the existing {@code AgentDispatched} event. Flag-off default (null)
     * so existing coordinators don't pay the signing cost unless operators
     * opt in. The {@code CommitmentRecord} schema may migrate by 2026-Q4.
     */
    private volatile CommitmentSigner commitmentSigner;
    /** Optional principal identifier stamped onto every emitted record. */
    private volatile String principal;

    public JournalingAgentFleet(AgentFleet delegate, CoordinationJournal journal,
                                String coordinatorName) {
        this(delegate, journal, coordinatorName,
                Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("eval-", 0).factory()),
                true, null);
    }

    private JournalingAgentFleet(AgentFleet delegate, CoordinationJournal journal,
                                 String coordinatorName, ExecutorService evalExecutor,
                                 boolean ownsExecutor, String coordinationIdOverride) {
        this.delegate = delegate;
        this.journal = journal;
        this.coordinatorName = coordinatorName;
        this.evalExecutor = evalExecutor;
        this.ownsExecutor = ownsExecutor;
        this.coordinationIdOverride = coordinationIdOverride;
    }

    /**
     * Returns a fleet decorator that records under an explicit coordination id
     * rather than the coordinator-name-derived default. Shares the executor
     * (does not own it) so the caller's lifecycle stays unaffected.
     *
     * <p>Used by {@link CoordinationFork} to re-execute a forked branch under
     * a fresh coordination id, but exposed publicly so any caller needing to
     * pin journal output to a known coordination id can use it.</p>
     */
    public JournalingAgentFleet withCoordinationId(String coordinationId) {
        return new JournalingAgentFleet(delegate, journal, coordinatorName,
                evalExecutor, false, coordinationId);
    }

    @Override
    public AgentFleet withParentRun(String parentRunId) {
        var delegated = delegate.withParentRun(parentRunId);
        if (delegated == delegate) {
            return this; // null/blank parent id — no change
        }
        // Re-wrap the parent-run-carrying delegate, sharing (not owning) the
        // eval executor exactly like withCoordinationId.
        return new JournalingAgentFleet(delegated, journal, coordinatorName,
                evalExecutor, false, coordinationIdOverride);
    }

    /**
     * Install a {@link CommitmentSigner} so every cross-agent dispatch
     * emits a signed {@link CommitmentRecord}. Pass {@code null} or
     * {@link CommitmentSigner#UNSIGNED} to disable. Flag-off default;
     * operators enable this for deployments that need verifiable audit
     * trails (financial, medical, legal-adjacent coordinators).
     *
     * @apiNote the {@code CommitmentRecord} shape may migrate by 2026-Q4 when
     *          the W3C CCG + AP2 + Visa TAP standards-track convergence resolves.
     */
    public JournalingAgentFleet signer(CommitmentSigner signer) {
        this.commitmentSigner = signer;
        return this;
    }

    /**
     * Set the principal identifier stamped onto {@link CommitmentRecord#principal()}.
     * Operators typically wire the authenticated user/service principal
     * resolved by their auth stack.
     */
    public JournalingAgentFleet principal(String principal) {
        this.principal = principal;
        return this;
    }

    /**
     * Shuts down the background evaluation executor. Should be called when
     * the coordinator is being unregistered or the application is stopping.
     */
    @Override
    public void close() {
        if (ownsExecutor) {
            evalExecutor.close();
        }
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
        var startedId = journal.recordEnveloped(EventEnvelope.root(
                new CoordinationEvent.CoordinationStarted(
                        coordId, coordinatorName, start)));

        var dispatchIds = new java.util.ArrayList<String>(calls.length);
        for (var agentCall : calls) {
            var dispatchId = journal.recordEnveloped(EventEnvelope.childOf(
                    startedId,
                    new CoordinationEvent.AgentDispatched(
                            coordId, agentCall.agentName(), agentCall.skill(),
                            agentCall.args(), Instant.now())));
            dispatchIds.add(dispatchId);
            emitCommitmentRecord(coordId, dispatchId, agentCall, "started");
        }

        var results = delegate.parallel(calls);

        int idx = 0;
        for (var entry : results.entrySet()) {
            var result = entry.getValue();
            var parentDispatchId = idx < dispatchIds.size() ? dispatchIds.get(idx) : startedId;
            var completedId = recordResult(coordId, parentDispatchId, result);
            autoEvaluate(coordId, completedId, result,
                    idx < calls.length ? calls[idx] : null);
            idx++;
        }

        journal.recordEnveloped(EventEnvelope.childOf(
                startedId,
                new CoordinationEvent.CoordinationCompleted(
                        coordId, Duration.between(start, Instant.now()),
                        calls.length, Instant.now())));

        return results;
    }

    @Override
    public AgentResult pipeline(AgentCall... calls) {
        var coordId = coordinationId();
        var start = Instant.now();
        var startedId = journal.recordEnveloped(EventEnvelope.root(
                new CoordinationEvent.CoordinationStarted(
                        coordId, coordinatorName, start)));

        AgentResult last = null;
        for (var agentCall : calls) {
            // Merge previous result into args so pipeline steps can chain
            var args = agentCall.args();
            if (last != null) {
                var merged = new java.util.LinkedHashMap<>(args);
                merged.put("_previous_result", last.text());
                args = Map.copyOf(merged);
            }

            var dispatchId = journal.recordEnveloped(EventEnvelope.childOf(
                    startedId,
                    new CoordinationEvent.AgentDispatched(
                            coordId, agentCall.agentName(), agentCall.skill(),
                            args, Instant.now())));
            emitCommitmentRecord(coordId, dispatchId, agentCall, "started");

            var proxy = delegate.agent(agentCall.agentName());
            last = proxy.call(agentCall.skill(), args);
            var completedId = recordResult(coordId, dispatchId, last);
            autoEvaluate(coordId, completedId, last, agentCall);

            if (!last.success()) {
                break;
            }
        }

        var completedCount = last != null
                ? (int) results(calls, last)
                : 0;
        journal.recordEnveloped(EventEnvelope.childOf(
                startedId,
                new CoordinationEvent.CoordinationCompleted(
                        coordId, Duration.between(start, Instant.now()),
                        completedCount, Instant.now())));

        return last;
    }

    @Override
    public AgentResult route(AgentResult input, Consumer<RoutingSpec> spec) {
        var coordId = coordinationId();
        var routing = new RoutingSpec();
        spec.accept(routing);
        var outcome = routing.evaluate(input, this);

        // route() is a standalone decision — no surrounding CoordinationStarted,
        // so the RouteEvaluated envelope is itself a root in the causal DAG.
        journal.recordEnveloped(EventEnvelope.root(
                new CoordinationEvent.RouteEvaluated(
                        coordId,
                        input.agentName(),
                        outcome.matchedIndex(),
                        outcome.result().agentName(),
                        outcome.matched(),
                        Instant.now())));

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
        // Share the parent's executor — don't create a new one per session
        return new JournalingAgentFleet(
                delegate.withActivityListener(listener), journal, coordinatorName,
                evalExecutor, false, coordinationIdOverride);
    }

    /**
     * Resolve the canonical coordination id. Computed fresh per call rather
     * than stashed in a {@code ThreadLocal} because servlet thread pools
     * would otherwise pin the id to the worker thread across unrelated
     * requests (Correctness Invariant #2 — terminal paths must reset state).
     */
    private String coordinationId() {
        // Explicit override (forked sub-coordination) wins over the default
        // coordinator-name-derived id.
        if (coordinationIdOverride != null && !coordinationIdOverride.isEmpty()) {
            return coordinationIdOverride;
        }
        // Use the @Coordinator(name="...") value as the canonical
        // coordination id so REST/journal consumers can filter by the
        // logical coordinator name (e.g. `?coordination=dispatch`).
        // Fall back to a random UUID only if the coordinator name is
        // missing — the JournalingAgentFleet may be instantiated
        // outside CoordinatorProcessor (tests, ad-hoc decorators).
        return coordinatorName != null && !coordinatorName.isEmpty()
                ? coordinatorName
                : UUID.randomUUID().toString();
    }

    /**
     * Build + sign + emit a {@link CommitmentRecord} for one dispatch.
     * No-op when {@link #commitmentSigner} is null or UNSIGNED. The record
     * is signed synchronously — typical Ed25519 sign is ~sub-millisecond so
     * the admission hot path is unaffected. Operators swapping in an
     * HSM-backed signer with higher latency should wrap via a custom
     * async {@link CommitmentSigner}.
     */
    private void emitCommitmentRecord(String coordId, String parentDispatchId,
                                      AgentCall call, String outcome) {
        // Flag-off default: even when a signer is wired, emission is gated
        // on the runtime flag so operators explicitly opt into the
        // commitment-record schema.
        if (!CommitmentRecordsFlag.isEnabled()) {
            return;
        }
        var signer = commitmentSigner;
        if (signer == null || signer == CommitmentSigner.UNSIGNED) {
            return;
        }
        try {
            var unsigned = new CommitmentRecord(
                    UUID.randomUUID().toString(),
                    coordId,
                    "coordinator:" + coordinatorName,
                    principal,
                    call.agentName(),
                    call.skill(),
                    List.of(),
                    Instant.now(),
                    null,
                    outcome,
                    call.args(),
                    null);
            var proof = signer.sign(unsigned);
            var signed = new CommitmentRecord(
                    unsigned.id(), unsigned.coordinationId(),
                    unsigned.issuer(), unsigned.principal(), unsigned.subject(),
                    unsigned.scope(), unsigned.delegationChain(),
                    unsigned.issuedAt(), unsigned.expiresAt(), unsigned.outcome(),
                    unsigned.properties(), proof);
            journal.recordEnveloped(EventEnvelope.childOf(
                    parentDispatchId,
                    new CoordinationEvent.CommitmentRecorded(
                            coordId, signed, Instant.now())));
        } catch (RuntimeException e) {
            logger.warn("Commitment-record emission failed for dispatch {}/{}: {} — "
                    + "skipping (signing is best-effort, does not block dispatch)",
                    call.agentName(), call.skill(), e.toString());
        }
    }

    private String recordResult(String coordId, String parentDispatchId, AgentResult result) {
        var envelope = EventEnvelope.childOf(
                parentDispatchId,
                result.success()
                        ? new CoordinationEvent.AgentCompleted(
                                coordId, result.agentName(), result.skillId(),
                                result.text(), result.duration(), Instant.now())
                        : new CoordinationEvent.AgentFailed(
                                coordId, result.agentName(), result.skillId(),
                                result.text(), result.duration(), Instant.now()));
        return journal.recordEnveloped(envelope);
    }

    private void autoEvaluate(String coordId, String parentCompletedId,
                              AgentResult result, AgentCall call) {
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
                    journal.recordEnveloped(EventEnvelope.childOf(
                            parentCompletedId,
                            new CoordinationEvent.AgentEvaluated(
                                    coordId, result.agentName(), evalName,
                                    eval.score(), eval.passed(), eval.reason(),
                                    Instant.now())));
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
            // No surrounding CoordinationStarted on the proxy path — each
            // dispatch is itself a root in the causal DAG.
            var dispatchId = journal.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentDispatched(
                            coordId, delegate.name(), skill, args, Instant.now())));

            var result = delegate.call(skill, args);
            var completedId = recordResult(coordId, dispatchId, result);
            autoEvaluate(coordId, completedId, result,
                    new org.atmosphere.coordinator.fleet.AgentCall(
                            delegate.name(), skill, args));
            return result;
        }

        @Override
        public CompletableFuture<AgentResult> callAsync(String skill,
                                                         Map<String, Object> args) {
            var dispatchId = journal.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentDispatched(
                            coordId, delegate.name(), skill, args, Instant.now())));

            return delegate.callAsync(skill, args)
                    .whenComplete((result, error) -> {
                        if (result != null) {
                            recordResult(coordId, dispatchId, result);
                        } else if (error != null) {
                            journal.recordEnveloped(EventEnvelope.childOf(
                                    dispatchId,
                                    new CoordinationEvent.AgentFailed(
                                            coordId, delegate.name(), skill,
                                            error.getMessage(), Duration.ZERO,
                                            Instant.now())));
                        }
                    });
        }

        @Override
        public void stream(String skill, Map<String, Object> args,
                           Consumer<String> onToken, Runnable onComplete) {
            var dispatchId = journal.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.AgentDispatched(
                            coordId, delegate.name(), skill, args, Instant.now())));

            var start = Instant.now();
            delegate.stream(skill, args, onToken, () -> {
                journal.recordEnveloped(EventEnvelope.childOf(
                        dispatchId,
                        new CoordinationEvent.AgentCompleted(
                                coordId, delegate.name(), skill, "(streamed)",
                                Duration.between(start, Instant.now()),
                                Instant.now())));
                onComplete.run();
            });
        }
    }
}
