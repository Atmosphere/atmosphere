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

import org.atmosphere.coordinator.evaluation.Evaluation;
import org.atmosphere.coordinator.evaluation.ResultEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Default {@link AgentFleet} implementation. Holds a map of agent proxies and
 * provides parallel/pipeline execution patterns using virtual threads.
 */
public final class DefaultAgentFleet implements AgentFleet {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentFleet.class);

    /** Default per-agent timeout for parallel calls: 2 minutes. */
    public static final long DEFAULT_PARALLEL_TIMEOUT_MS = 120_000L;

    /**
     * Default cap on simultaneous sub-agent dispatches per fleet: 16. Shared
     * by every {@link #parallel} / {@link #parallelCancellable} call on the
     * fleet (and on the fleets derived from it through
     * {@link #withActivityListener} / {@link #withParentRun}), so neither one
     * large fan-out nor many concurrent fan-outs can open an unbounded number
     * of downstream agent or LLM requests (Correctness Invariant #3).
     */
    public static final int DEFAULT_MAX_PARALLEL = 16;

    /**
     * System property overriding {@link #DEFAULT_MAX_PARALLEL} for fleets that
     * do not set an explicit limit (e.g. {@code @Fleet(maxParallel = 0)}).
     */
    public static final String MAX_PARALLEL_PROPERTY = "atmosphere.coordinator.max-parallel";

    /**
     * System property toggling the first-run-sequential warm-up. Defaults to
     * {@code false} — Atmosphere's typical websocket/HTTP deployment routes
     * approval prompts through {@code ApprovalStrategy} on per-session
     * transports, so cross-thread stdout collisions are not a concern. Opt
     * in with {@code -Datmosphere.coordinator.first-run-sequential=true}
     * when the coordinator drives interactive prompts on a shared channel
     * (CLI, embedded scripts, single-terminal demos) and a cold-start
     * dispatch of multiple {@code ToolPermission.CONFIRM} tools could race.
     */
    public static final String FIRST_RUN_SEQUENTIAL_PROPERTY =
            "atmosphere.coordinator.first-run-sequential";

    /**
     * In-process record of sub-agents that have completed at least one
     * successful dispatch. The first dispatch of each name runs sequentially
     * so confirm prompts do not collide; subsequent dispatches fan out in
     * parallel as before. Static because the warm-up is per-JVM, not
     * per-fleet-instance.
     */
    private static final Set<String> FIRST_RUN_COMPLETED = ConcurrentHashMap.newKeySet();

    private final Map<String, AgentProxy> proxies;
    private final List<ResultEvaluator> evaluators;
    private final long parallelTimeoutMs;
    private final List<AgentActivityListener> activityListeners;
    private final int maxParallel;
    private final Semaphore dispatchPermits;

    public DefaultAgentFleet(Map<String, AgentProxy> proxies) {
        this(proxies, List.of());
    }

    public DefaultAgentFleet(Map<String, AgentProxy> proxies,
                             List<ResultEvaluator> evaluators) {
        this(proxies, evaluators, DEFAULT_PARALLEL_TIMEOUT_MS);
    }

    public DefaultAgentFleet(Map<String, AgentProxy> proxies,
                             List<ResultEvaluator> evaluators,
                             long parallelTimeoutMs) {
        this(proxies, evaluators, parallelTimeoutMs, List.of());
    }

    public DefaultAgentFleet(Map<String, AgentProxy> proxies,
                             List<ResultEvaluator> evaluators,
                             long parallelTimeoutMs,
                             List<AgentActivityListener> activityListeners) {
        this(proxies, evaluators, parallelTimeoutMs, activityListeners, 0);
    }

    /**
     * @param maxParallel maximum simultaneous sub-agent dispatches across all
     *                    fan-outs on this fleet; {@code 0} (or negative) means
     *                    {@link #MAX_PARALLEL_PROPERTY} or
     *                    {@link #DEFAULT_MAX_PARALLEL}
     */
    public DefaultAgentFleet(Map<String, AgentProxy> proxies,
                             List<ResultEvaluator> evaluators,
                             long parallelTimeoutMs,
                             List<AgentActivityListener> activityListeners,
                             int maxParallel) {
        this(proxies, evaluators, parallelTimeoutMs, activityListeners,
                resolveMaxParallel(maxParallel), null);
    }

    private DefaultAgentFleet(Map<String, AgentProxy> proxies,
                              List<ResultEvaluator> evaluators,
                              long parallelTimeoutMs,
                              List<AgentActivityListener> activityListeners,
                              int maxParallel,
                              Semaphore sharedPermits) {
        this.proxies = Map.copyOf(proxies);
        this.evaluators = List.copyOf(evaluators);
        this.parallelTimeoutMs = parallelTimeoutMs;
        this.activityListeners = List.copyOf(activityListeners);
        this.maxParallel = maxParallel;
        this.dispatchPermits = sharedPermits != null
                ? sharedPermits : new Semaphore(maxParallel, true);
    }

    /** Maximum simultaneous sub-agent dispatches this fleet allows. */
    public int maxParallel() {
        return maxParallel;
    }

    private static int resolveMaxParallel(int requested) {
        if (requested > 0) {
            return requested;
        }
        var raw = System.getProperty(MAX_PARALLEL_PROPERTY);
        if (raw != null && !raw.isBlank()) {
            try {
                var parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
                logger.warn("Ignoring non-positive {}={}; using {}",
                        MAX_PARALLEL_PROPERTY, raw, DEFAULT_MAX_PARALLEL);
            } catch (NumberFormatException e) {
                logger.warn("Ignoring invalid {}={}; using {}",
                        MAX_PARALLEL_PROPERTY, raw, DEFAULT_MAX_PARALLEL, e);
            }
        }
        return DEFAULT_MAX_PARALLEL;
    }

    /**
     * Returns the activity listeners configured for this fleet.
     * Used by the processor to thread listeners through to proxies.
     */
    public List<AgentActivityListener> activityListeners() {
        return activityListeners;
    }

    @Override
    public AgentFleet withActivityListener(AgentActivityListener listener) {
        var extra = List.of(listener);
        var combined = new ArrayList<>(this.activityListeners);
        combined.add(listener);
        var newProxies = new LinkedHashMap<String, AgentProxy>();
        for (var entry : proxies.entrySet()) {
            var proxy = entry.getValue();
            if (proxy instanceof DefaultAgentProxy dap) {
                newProxies.put(entry.getKey(), dap.withAdditionalListeners(extra));
            } else {
                newProxies.put(entry.getKey(), proxy);
            }
        }
        return new DefaultAgentFleet(newProxies, evaluators, parallelTimeoutMs, combined,
                maxParallel, dispatchPermits);
    }

    @Override
    public AgentFleet withParentRun(String parentRunId) {
        if (parentRunId == null || parentRunId.isBlank()) {
            return this;
        }
        // Stamp the coordinator's tape run id onto every child dispatch's wire
        // message metadata; the child records it as parentRunId so the whole
        // coordination replays as a tree (TapeReplay#reconstructTree).
        var md = Map.<String, Object>of(
                org.atmosphere.ai.tape.TapeSupport.PARENT_RUN_METADATA_KEY, parentRunId);
        var newProxies = new LinkedHashMap<String, AgentProxy>();
        for (var entry : proxies.entrySet()) {
            newProxies.put(entry.getKey(), entry.getValue().withDispatchMetadata(md));
        }
        return new DefaultAgentFleet(newProxies, evaluators, parallelTimeoutMs,
                activityListeners, maxParallel, dispatchPermits);
    }

    @Override
    public AgentProxy agent(String name) {
        var proxy = proxies.get(name);
        if (proxy == null) {
            throw new IllegalArgumentException(
                    "No agent named '" + name + "' in fleet. Available: " + proxies.keySet());
        }
        return proxy;
    }

    @Override
    public List<AgentProxy> agents() {
        return List.copyOf(proxies.values());
    }

    @Override
    public List<AgentProxy> available() {
        return proxies.values().stream()
                .filter(AgentProxy::isAvailable)
                .toList();
    }

    @Override
    public AgentCall call(String agentName, String skill, Map<String, Object> args) {
        return new AgentCall(agentName, skill, args);
    }

    // The CompletableFuture fan-out below is deliberate, not a stopgap:
    // StructuredTaskScope (JEP 525) would express this more directly —
    // sibling cancellation on first failure, join().throwIfFailed() error
    // collection, parent-owned child lifetimes — but it is still a preview
    // API, and a library cannot require --enable-preview of its consumers.
    // Until it finalizes, this code carries those guarantees by hand:
    // shutdownNow() interrupts siblings on first failure, which is what
    // preserves Correctness Invariant #2 (Terminal Path Completeness).
    @Override
    public Map<String, AgentResult> parallel(AgentCall... calls) {
        logger.debug("Parallel fan-out to {} agents", calls.length);

        // Stable-key assignment up front so warm-up and parallel phases agree
        // on result-map keys regardless of which phase processed which entry.
        var nameCount = new HashMap<String, Integer>();
        var ordered = new ArrayList<KeyedCall>(calls.length);
        for (var agentCall : calls) {
            var name = agentCall.agentName();
            var count = nameCount.merge(name, 1, Integer::sum);
            var key = count == 1 ? name : name + "#" + count;
            ordered.add(new KeyedCall(key, agentCall));
        }

        var results = new LinkedHashMap<String, AgentResult>();

        // First-run warm-up: any sub-agent that has not completed a successful
        // dispatch in this JVM runs sequentially before the parallel fan-out
        // begins. This prevents CONFIRM-shaped tool prompts from colliding
        // across virtual threads on the cold path. Same-name duplicates
        // within a single parallel() call still pay the warm-up once.
        if (firstRunSequentialEnabled()) {
            var warmedThisCall = new java.util.HashSet<String>();
            for (var entry : ordered) {
                var name = entry.call().agentName();
                if (FIRST_RUN_COMPLETED.contains(name) || warmedThisCall.contains(name)) {
                    continue;
                }
                var warm = dispatchBounded(entry.call(), true,
                        timeoutFor(agent(name)), new AtomicBoolean());
                results.put(entry.key(), warm);
                warmedThisCall.add(name);
                if (warm.success()) {
                    FIRST_RUN_COMPLETED.add(name);
                }
            }
        }

        ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new LinkedHashMap<String, CompletableFuture<AgentResult>>();
        var timeouts = new HashMap<String, Long>();
        var started = new HashMap<String, AtomicBoolean>();
        for (var entry : ordered) {
            if (results.containsKey(entry.key())) {
                continue;
            }
            // The per-agent deadline covers the wait for a dispatch permit
            // plus the call itself, so a call queued behind maxParallel past
            // its deadline still resolves to a failure result.
            var agentTimeoutMs = timeoutFor(agent(entry.call().agentName()));
            var startedFlag = new AtomicBoolean();
            timeouts.put(entry.key(), agentTimeoutMs);
            started.put(entry.key(), startedFlag);
            futures.put(entry.key(),
                    CompletableFuture.supplyAsync(
                            () -> dispatchBounded(entry.call(), false,
                                    agentTimeoutMs, startedFlag),
                            vtExecutor)
                            .orTimeout(agentTimeoutMs, TimeUnit.MILLISECONDS));
        }
        try {
            for (var entry : futures.entrySet()) {
                try {
                    results.put(entry.getKey(), entry.getValue().join());
                } catch (Exception e) {
                    // Cancel siblings WHILE the executor is still live so the
                    // interrupt actually reaches blocked workers. Using
                    // ExecutorService.close() first would block until every
                    // task joins — then cancel(true) runs too late to do
                    // anything (Correctness Invariant #2 — terminal paths
                    // must complete/release/reset symmetrically).
                    futures.values().forEach(f -> f.cancel(true));
                    vtExecutor.shutdownNow();

                    var cause = e.getCause();
                    var actualTimeout =
                            timeouts.getOrDefault(entry.getKey(), parallelTimeoutMs);
                    var neverStarted = !started.get(entry.getKey()).get();
                    var msg = cause instanceof TimeoutException
                            ? neverStarted
                                    ? queuedMessage(actualTimeout)
                                    : "Agent timed out after " + actualTimeout + "ms"
                            : "Parallel call failed: " + e.getMessage();
                    logger.error("Parallel call to '{}' failed: {}", entry.getKey(), msg);
                    results.put(entry.getKey(), AgentResult.failure(
                            entry.getKey(), "", msg, Duration.ZERO));
                    // Drain the remaining futures: on first failure we want
                    // every agent to report a cancelled/failure result so
                    // callers see a symmetric map, not a half-filled one.
                    for (var remaining : futures.entrySet()) {
                        if (results.containsKey(remaining.getKey())) {
                            continue;
                        }
                        try {
                            results.put(remaining.getKey(),
                                    remaining.getValue().join());
                        } catch (Exception re) {
                            results.put(remaining.getKey(), AgentResult.failure(
                                    remaining.getKey(), "",
                                    "Sibling cancelled after peer failure",
                                    Duration.ZERO));
                        }
                    }
                    break;
                }
            }
        } finally {
            // close() awaits tasks that weren't interrupted (success path) and
            // is idempotent with shutdownNow() on the failure path — either
            // way the executor's threads are released before we return.
            vtExecutor.close();
        }

        logger.debug("Parallel fan-out complete: {} results", results.size());
        return results;
    }

    /**
     * Bounded counterpart of {@link #parallel}: every handle's dispatch waits
     * for the same fleet-wide permit, so the non-blocking fan-out honors
     * {@link #maxParallel()} exactly like the blocking one (Mode Parity).
     * Cancelling a handle that is still queued interrupts its wait and
     * releases nothing it did not acquire.
     */
    @Override
    public Map<String, AgentExecution> parallelCancellable(AgentCall... calls) {
        var results = new LinkedHashMap<String, AgentExecution>();
        var nameCount = new HashMap<String, Integer>();
        for (var agentCall : calls) {
            var name = agentCall.agentName();
            var count = nameCount.merge(name, 1, Integer::sum);
            var key = count == 1 ? name : name + "#" + count;
            var timeoutMs = timeoutFor(agent(name));
            var future = new CompletableFuture<AgentResult>();
            var worker = Thread.ofVirtual().unstarted(() -> {
                try {
                    future.complete(dispatchBounded(agentCall, false, timeoutMs,
                            new AtomicBoolean()));
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
            // CompletableFuture.cancel(true) does not interrupt the runner;
            // do it here so a cancelled handle stops waiting for a permit.
            future.whenComplete((r, t) -> {
                if (future.isCancelled()) {
                    worker.interrupt();
                }
            });
            worker.start();
            results.put(key, new AgentExecution.Running(name, agentCall.skill(),
                    java.time.Instant.now(), future));
        }
        return results;
    }

    /** Per-agent timeout if configured, otherwise the fleet default. */
    private long timeoutFor(AgentProxy proxy) {
        return proxy instanceof DefaultAgentProxy dap && !dap.limits().isDefaultTimeout()
                ? dap.limits().timeout().toMillis()
                : parallelTimeoutMs;
    }

    private String queuedMessage(long timeoutMs) {
        return "Agent queued for " + timeoutMs + "ms without a dispatch slot (maxParallel="
                + maxParallel + ")";
    }

    /**
     * Acquire one of the fleet's {@link #maxParallel()} dispatch permits, run
     * the call, and release the permit on every exit path. A call that cannot
     * get a permit within {@code timeoutMs} returns a failure result instead of
     * being dropped; an interrupt while queued (sibling failure, cancelled
     * handle) propagates as an exception and acquires nothing.
     */
    private AgentResult dispatchBounded(AgentCall agentCall, boolean firstRun,
                                        long timeoutMs, AtomicBoolean started) {
        boolean acquired;
        try {
            acquired = dispatchPermits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException(
                    "Interrupted while queued for a dispatch slot");
        }
        if (!acquired) {
            logger.warn("Sub-agent '{}' got no dispatch slot within {}ms (maxParallel={})",
                    agentCall.agentName(), timeoutMs, maxParallel);
            return AgentResult.failure(agentCall.agentName(), agentCall.skill(),
                    queuedMessage(timeoutMs), Duration.ofMillis(timeoutMs));
        }
        try {
            started.set(true);
            return dispatchOne(agentCall, firstRun);
        } finally {
            dispatchPermits.release();
        }
    }

    /**
     * Dispatch a single sub-agent call with JFR instrumentation. Used by both
     * the first-run warm-up and the parallel fan-out so a recording shows
     * which dispatches took the sequential path ({@code firstRun=true}) and
     * which took the parallel path ({@code firstRun=false}).
     */
    private AgentResult dispatchOne(AgentCall agentCall, boolean firstRun) {
        var event = new org.atmosphere.ai.jfr.SubAgentDispatchEvent();
        event.subAgent = agentCall.agentName();
        event.skill = agentCall.skill();
        event.firstRun = firstRun;
        event.begin();
        AgentResult result = null;
        try {
            result = agent(agentCall.agentName()).call(agentCall.skill(), agentCall.args());
            return result;
        } finally {
            event.success = result != null && result.success();
            event.commit();
        }
    }

    /**
     * Read the {@link #FIRST_RUN_SEQUENTIAL_PROPERTY} system property on every
     * invocation so tests can flip the toggle per case without restarting the
     * JVM. Defaults to {@code false} when the property is absent or blank.
     */
    private static boolean firstRunSequentialEnabled() {
        var raw = System.getProperty(FIRST_RUN_SEQUENTIAL_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return Boolean.parseBoolean(raw);
    }

    /**
     * Visible for testing. Clears the in-process record of warmed-up
     * sub-agents so the next {@link #parallel} call exercises the
     * sequential warm-up path again.
     */
    public static void resetFirstRunStateForTesting() {
        FIRST_RUN_COMPLETED.clear();
    }

    /** Internal pairing of an {@link AgentCall} with its stable result-map key. */
    private record KeyedCall(String key, AgentCall call) { }

    @Override
    public AgentResult pipeline(AgentCall... calls) {
        logger.debug("Pipeline execution of {} steps", calls.length);
        AgentResult last = null;
        for (var agentCall : calls) {
            var proxy = agent(agentCall.agentName());
            // Merge previous result into args so pipeline steps can chain
            var args = agentCall.args();
            if (last != null) {
                var merged = new LinkedHashMap<>(args);
                merged.put("_previous_result", last.text());
                args = Map.copyOf(merged);
            }
            last = proxy.call(agentCall.skill(), args);
            if (!last.success()) {
                logger.warn("Pipeline step '{}' failed, aborting", agentCall.agentName());
                return last;
            }
        }
        return last;
    }

    @Override
    public AgentResult route(AgentResult input, Consumer<RoutingSpec> spec) {
        logger.debug("Evaluating route for input from '{}'", input.agentName());
        var routing = new RoutingSpec();
        spec.accept(routing);
        var outcome = routing.evaluate(input, this);
        if (outcome.matched()) {
            logger.debug("Route matched (index={}), result from '{}'",
                    outcome.matchedIndex(), outcome.result().agentName());
        } else {
            logger.debug("No route matched for input from '{}'", input.agentName());
        }
        return outcome.result();
    }

    @Override
    public List<Evaluation> evaluate(AgentResult result, AgentCall originalCall) {
        return evaluators.stream()
                .map(e -> {
                    try {
                        var eval = e.evaluate(result, originalCall);
                        // Tag with evaluator name for journal/activity tracking
                        var meta = new LinkedHashMap<>(eval.metadata());
                        meta.put("evaluator", e.name());
                        return new Evaluation(eval.score(), eval.passed(),
                                eval.reason(), meta);
                    } catch (Exception ex) {
                        logger.warn("Evaluator '{}' failed for agent '{}'",
                                e.name(), result.agentName(), ex);
                        return Evaluation.fail(0.0,
                                "Evaluator error: " + ex.getMessage(),
                                Map.of("evaluator", e.name()));
                    }
                })
                .toList();
    }
}
