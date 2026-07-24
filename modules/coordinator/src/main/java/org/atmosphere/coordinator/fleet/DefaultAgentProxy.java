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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Default {@link AgentProxy} implementation that delegates to an {@link AgentTransport}.
 */
public final class DefaultAgentProxy implements AgentProxy {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentProxy.class);

    private final String name;
    private final String version;
    private final int weight;
    private final boolean local;
    private final RetryPolicy retryPolicy;
    private final AgentTransport transport;
    private final List<AgentActivityListener> activityListeners;
    private final AgentLimits limits;
    private final Map<String, Object> dispatchMetadata;

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, AgentTransport transport) {
        this(name, version, weight, local, 0, transport, List.of(), AgentLimits.DEFAULT);
    }

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, int maxRetries, AgentTransport transport) {
        this(name, version, weight, local, maxRetries, transport, List.of(), AgentLimits.DEFAULT);
    }

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, int maxRetries, AgentTransport transport,
                             List<AgentActivityListener> activityListeners) {
        this(name, version, weight, local, maxRetries, transport, activityListeners,
                AgentLimits.DEFAULT);
    }

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, int maxRetries, AgentTransport transport,
                             List<AgentActivityListener> activityListeners,
                             AgentLimits limits) {
        this(name, version, weight, local, RetryPolicy.fromMaxRetries(maxRetries),
                transport, activityListeners, limits);
    }

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, RetryPolicy retryPolicy, AgentTransport transport,
                             List<AgentActivityListener> activityListeners,
                             AgentLimits limits) {
        this(name, version, weight, local, retryPolicy, transport, activityListeners, limits,
                Map.of());
    }

    private DefaultAgentProxy(String name, String version, int weight,
                              boolean local, RetryPolicy retryPolicy, AgentTransport transport,
                              List<AgentActivityListener> activityListeners,
                              AgentLimits limits, Map<String, Object> dispatchMetadata) {
        this.name = name;
        this.version = version;
        this.weight = weight;
        this.local = local;
        this.retryPolicy = retryPolicy;
        this.transport = transport;
        this.activityListeners = List.copyOf(activityListeners);
        this.limits = limits;
        this.dispatchMetadata = Map.copyOf(dispatchMetadata);
    }

    @Override
    public AgentProxy withDispatchMetadata(Map<String, Object> md) {
        if (md == null || md.isEmpty()) {
            return this;
        }
        return new DefaultAgentProxy(name, version, weight, local, retryPolicy, transport,
                activityListeners, limits, md);
    }

    // Route through the 3-arg transport call when there is no dispatch metadata
    // (the common path — and the one existing transport stubs/mocks implement),
    // and only the metadata-carrying 4-arg overload when a parent run is set.
    private AgentResult dispatch(String skill, Map<String, Object> args) {
        return dispatchMetadata.isEmpty()
                ? transport.send(name, skill, args)
                : transport.send(name, skill, args, dispatchMetadata);
    }

    /**
     * Dispatch a single skill call bounded by {@link AgentLimits#timeout()}.
     * The synchronous {@link #call} / {@link DefaultAgentFleet#pipeline} paths
     * used to invoke {@link #dispatch} directly with no time bound, so a hanging
     * local sub-agent ({@code LocalAgentTransport.send} is fully synchronous)
     * blocked the coordinator thread forever — only
     * {@link DefaultAgentFleet#parallel} honored the limit (Mode Parity,
     * Correctness Invariant #7; Terminal Path, Invariant #2). This helper closes
     * that gap for every non-parallel mode by mirroring {@code parallel()}'s
     * proven interrupt mechanism: run the dispatch on a per-call
     * virtual-thread executor, bound it with {@code orTimeout}, and on a timeout
     * {@code cancel(true)} + {@code shutdownNow()} the executor <em>while it is
     * still live</em> so the interrupt actually reaches the blocked worker
     * ({@link CompletableFuture#cancel} alone does not interrupt). A timeout
     * yields a symmetric {@link AgentResult#failure} rather than propagating,
     * so the retry loop in {@link #call} treats it like any other failure.
     *
     * <p>The executor is created and owned by this call and torn down in a
     * {@code finally} with the non-blocking {@code shutdownNow()} (never
     * {@code close()}, which awaits termination and would re-introduce the very
     * hang this guards against — Ownership, Invariant #1). A non-timeout
     * dispatch failure is re-thrown unchanged to preserve the prior
     * propagation behavior.</p>
     */
    private AgentResult dispatchBounded(String skill, Map<String, Object> args, Instant start) {
        var timeoutMs = limits.timeout().toMillis();
        var vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // Capture any thread-affine dispatch context (e.g. the local
            // circular-dispatch chain) on THIS thread before hopping to the
            // worker, so the transport's runtime guards survive the bound. A
            // transport that returns null (no context to propagate, or a test
            // double) falls back to the unwrapped dispatch.
            Supplier<AgentResult> raw = () -> dispatch(skill, args);
            var wrapped = transport.withDispatchContext(raw);
            var body = wrapped != null ? wrapped : raw;
            var future = CompletableFuture
                    .supplyAsync(body, vtExecutor)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            try {
                // Await interruptibly (get, not join): when this call is itself
                // running on an outer executor that is shut down — e.g.
                // DefaultAgentFleet.parallel() cancelling siblings on a first
                // failure — the interrupt lands here and must propagate INTO this
                // per-call executor, or the inner dispatch would run to
                // completion and defeat the outer cancellation.
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                vtExecutor.shutdownNow();
                var elapsed = Duration.between(start, Instant.now());
                logger.debug("Agent '{}' skill '{}' interrupted while awaiting dispatch", name, skill);
                return AgentResult.failure(name, skill, "Agent call interrupted", elapsed);
            } catch (ExecutionException e) {
                var cause = e.getCause();
                if (cause instanceof TimeoutException) {
                    // Cancel + shutdownNow WHILE the executor is live so the
                    // interrupt reaches the blocked dispatch worker.
                    future.cancel(true);
                    vtExecutor.shutdownNow();
                    var elapsed = Duration.between(start, Instant.now());
                    logger.warn("Agent '{}' skill '{}' timed out after {}ms — failing the call",
                            name, skill, timeoutMs);
                    return AgentResult.failure(name, skill,
                            "Agent timed out after " + timeoutMs + "ms", elapsed);
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(
                        "Agent '" + name + "' dispatch failed", cause == null ? e : cause);
            }
        } finally {
            // Non-blocking cleanup on every terminal path (success, timeout,
            // failure). shutdownNow() does not await, so it never hangs.
            vtExecutor.shutdownNow();
        }
    }

    /** Returns the per-agent limits configured for this proxy. */
    public AgentLimits limits() { return limits; }

    @Override
    public String name() { return name; }

    @Override
    public String version() { return version; }

    @Override
    public boolean isAvailable() { return transport.isAvailable(); }

    @Override
    public int weight() { return weight; }

    @Override
    public boolean isLocal() { return local; }

    @Override
    public AgentResult call(String skill, Map<String, Object> args) {
        var start = Instant.now();
        emitActivity(new AgentActivity.Thinking(name, skill, start));

        var result = dispatchBounded(skill, args, start);
        var maxAttempts = retryPolicy.maxRetries();
        if (result.success() || maxAttempts <= 0) {
            emitTerminal(skill, result, start);
            return result;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                emitTerminal(skill, result, start);
                return result;
            }
            var delay = retryPolicy.delayForAttempt(attempt);
            logger.debug("Agent '{}' call failed, retry {}/{} after {}ms",
                    name, attempt, maxAttempts, delay.toMillis());
            emitActivity(new AgentActivity.Retrying(
                    name, skill, attempt, maxAttempts,
                    Instant.now().plus(delay)));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitTerminal(skill, result, start);
                return result;
            }
            emitActivity(new AgentActivity.Thinking(name, skill, Instant.now()));
            result = dispatchBounded(skill, args, start);
            if (result.success()) {
                emitTerminal(skill, result, start);
                return result;
            }
        }
        emitTerminal(skill, result, start);
        return result;
    }

    private void emitTerminal(String skill, AgentResult result, Instant start) {
        var elapsed = Duration.between(start, Instant.now());
        if (result.success()) {
            emitActivity(new AgentActivity.Completed(name, skill, elapsed));
        } else {
            emitActivity(new AgentActivity.Failed(name, skill, result.text(), elapsed));
        }
    }

    private void emitActivity(AgentActivity activity) {
        for (var listener : activityListeners) {
            try {
                listener.onActivity(activity);
            } catch (Exception e) {
                logger.trace("Activity listener failed for agent '{}'", name, e);
            }
        }
    }

    @Override
    public CompletableFuture<AgentResult> callAsync(String skill, Map<String, Object> args) {
        var future = new CompletableFuture<AgentResult>();
        Thread.startVirtualThread(() -> {
            try {
                future.complete(call(skill, args));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public AgentExecution callWithHandle(String skill, Map<String, Object> args) {
        var start = Instant.now();
        var future = callAsync(skill, args);
        return new AgentExecution.Running(name, skill, start, future);
    }

    @Override
    public void stream(String skill, Map<String, Object> args,
                       Consumer<String> onToken, Runnable onComplete) {
        if (dispatchMetadata.isEmpty()) {
            transport.stream(name, skill, args, onToken, onComplete);
        } else {
            transport.stream(name, skill, args, dispatchMetadata, onToken, onComplete);
        }
    }

    /**
     * Returns a new proxy with additional activity listeners appended.
     * Used by {@link DefaultAgentFleet#withActivityListener} to create
     * per-session fleet views.
     */
    DefaultAgentProxy withAdditionalListeners(List<AgentActivityListener> extra) {
        var combined = new java.util.ArrayList<>(this.activityListeners);
        combined.addAll(extra);
        return new DefaultAgentProxy(name, version, weight, local, retryPolicy,
                transport, combined, limits, dispatchMetadata);
    }
}
