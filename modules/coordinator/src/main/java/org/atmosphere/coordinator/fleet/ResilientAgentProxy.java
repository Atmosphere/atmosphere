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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Decorator that wraps an {@link AgentProxy} with {@link CircuitBreaker}
 * protection. When the circuit is open, calls fail fast without hitting
 * the transport. Emits {@link AgentActivity.CircuitOpen} events so
 * dashboards can distinguish "agent is trying and failing" from
 * "system stopped calling this agent."
 *
 * <p>Follows the same decorator pattern as {@code JournalingAgentFleet}.</p>
 */
public final class ResilientAgentProxy implements AgentProxy {

    private static final Logger logger = LoggerFactory.getLogger(ResilientAgentProxy.class);

    private final AgentProxy delegate;
    private final CircuitBreaker circuitBreaker;
    private final List<AgentActivityListener> activityListeners;

    public ResilientAgentProxy(AgentProxy delegate, CircuitBreaker circuitBreaker) {
        this(delegate, circuitBreaker, List.of());
    }

    public ResilientAgentProxy(AgentProxy delegate, CircuitBreaker circuitBreaker,
                               List<AgentActivityListener> activityListeners) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.activityListeners = List.copyOf(activityListeners);
    }

    /** Returns the underlying circuit breaker for health reporting. */
    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public String name() { return delegate.name(); }

    @Override
    public String version() { return delegate.version(); }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable() && circuitBreaker.state() != CircuitBreaker.State.OPEN;
    }

    @Override
    public int weight() { return delegate.weight(); }

    @Override
    public boolean isLocal() { return delegate.isLocal(); }

    @Override
    public AgentResult call(String skill, Map<String, Object> args) {
        if (!circuitBreaker.allowRequest()) {
            var cooldownUntil = Instant.now().plusSeconds(30);
            emitActivity(new AgentActivity.CircuitOpen(
                    name(), "Circuit open after repeated failures", cooldownUntil));
            logger.debug("Circuit open for agent '{}', fast-failing", name());
            return AgentResult.failure(name(), skill,
                    "Circuit breaker open", Duration.ZERO);
        }
        var result = delegate.call(skill, args);
        if (result.success()) {
            circuitBreaker.recordSuccess();
        } else {
            circuitBreaker.recordFailure();
        }
        return result;
    }

    @Override
    public CompletableFuture<AgentResult> callAsync(String skill,
                                                     Map<String, Object> args) {
        if (!circuitBreaker.allowRequest()) {
            emitActivity(new AgentActivity.CircuitOpen(
                    name(), "Circuit open after repeated failures",
                    Instant.now().plusSeconds(30)));
            return CompletableFuture.completedFuture(
                    AgentResult.failure(name(), skill,
                            "Circuit breaker open", Duration.ZERO));
        }
        return delegate.callAsync(skill, args)
                .whenComplete((result, error) -> {
                    if (result != null && result.success()) {
                        circuitBreaker.recordSuccess();
                    } else {
                        circuitBreaker.recordFailure();
                    }
                });
    }

    @Override
    public void stream(String skill, Map<String, Object> args,
                       Consumer<String> onToken, Runnable onComplete) {
        if (!circuitBreaker.allowRequest()) {
            emitActivity(new AgentActivity.CircuitOpen(
                    name(), "Circuit open after repeated failures",
                    Instant.now().plusSeconds(30)));
            onComplete.run();
            return;
        }
        delegate.stream(skill, args, onToken, onComplete);
    }

    private void emitActivity(AgentActivity activity) {
        for (var listener : activityListeners) {
            try {
                listener.onActivity(activity);
            } catch (Exception e) {
                logger.trace("Activity listener failed for agent '{}'", name(), e);
            }
        }
    }
}
