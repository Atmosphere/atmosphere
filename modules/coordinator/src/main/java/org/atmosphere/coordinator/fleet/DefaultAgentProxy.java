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
import java.util.function.Consumer;

/**
 * Default {@link AgentProxy} implementation that delegates to an {@link AgentTransport}.
 */
public final class DefaultAgentProxy implements AgentProxy {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentProxy.class);
    private static final long BASE_BACKOFF_MS = 100L;

    private final String name;
    private final String version;
    private final int weight;
    private final boolean local;
    private final int maxRetries;
    private final AgentTransport transport;
    private final List<AgentActivityListener> activityListeners;

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, AgentTransport transport) {
        this(name, version, weight, local, 0, transport, List.of());
    }

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, int maxRetries, AgentTransport transport) {
        this(name, version, weight, local, maxRetries, transport, List.of());
    }

    public DefaultAgentProxy(String name, String version, int weight,
                             boolean local, int maxRetries, AgentTransport transport,
                             List<AgentActivityListener> activityListeners) {
        this.name = name;
        this.version = version;
        this.weight = weight;
        this.local = local;
        this.maxRetries = maxRetries;
        this.transport = transport;
        this.activityListeners = List.copyOf(activityListeners);
    }

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

        var result = transport.send(name, skill, args);
        if (result.success() || maxRetries <= 0) {
            emitTerminal(skill, result, start);
            return result;
        }
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            var backoffMs = BASE_BACKOFF_MS * (1L << (attempt - 1));
            logger.debug("Agent '{}' call failed, retry {}/{} after {}ms",
                    name, attempt, maxRetries, backoffMs);
            emitActivity(new AgentActivity.Retrying(
                    name, skill, attempt, maxRetries,
                    Instant.now().plusMillis(backoffMs)));
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitTerminal(skill, result, start);
                return result;
            }
            emitActivity(new AgentActivity.Thinking(name, skill, Instant.now()));
            result = transport.send(name, skill, args);
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
    public void stream(String skill, Map<String, Object> args,
                       Consumer<String> onToken, Runnable onComplete) {
        transport.stream(name, skill, args, onToken, onComplete);
    }

    /**
     * Returns a new proxy with additional activity listeners appended.
     * Used by {@link DefaultAgentFleet#withActivityListener} to create
     * per-session fleet views.
     */
    DefaultAgentProxy withAdditionalListeners(List<AgentActivityListener> extra) {
        var combined = new java.util.ArrayList<>(this.activityListeners);
        combined.addAll(extra);
        return new DefaultAgentProxy(name, version, weight, local, maxRetries,
                transport, combined);
    }
}
