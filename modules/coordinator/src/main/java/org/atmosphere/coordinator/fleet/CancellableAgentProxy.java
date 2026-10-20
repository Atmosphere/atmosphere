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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * An {@link AgentProxy} that stops calling its agent once {@code cancelled}
 * answers {@code true}: every later call answers a failed {@link AgentResult}
 * without dispatching. The view {@link AgentProxy#withCancellation} gives a
 * proxy that has no cancellation of its own.
 */
final class CancellableAgentProxy implements AgentProxy {

    private final AgentProxy delegate;
    private final BooleanSupplier cancelled;

    CancellableAgentProxy(AgentProxy delegate, BooleanSupplier cancelled) {
        this.delegate = delegate;
        this.cancelled = cancelled;
    }

    static AgentResult cancelledResult(String agentName, String skill) {
        return AgentResult.failure(agentName, skill,
                "The run was cancelled; agent '" + agentName + "' was not called.", Duration.ZERO);
    }

    @Override public String name() { return delegate.name(); }
    @Override public String version() { return delegate.version(); }
    @Override public boolean isAvailable() { return delegate.isAvailable(); }
    @Override public int weight() { return delegate.weight(); }
    @Override public boolean isLocal() { return delegate.isLocal(); }
    @Override public int maxTurns() { return delegate.maxTurns(); }

    @Override
    public AgentResult call(String skill, Map<String, Object> args) {
        return cancelled.getAsBoolean() ? cancelledResult(name(), skill) : delegate.call(skill, args);
    }

    @Override
    public CompletableFuture<AgentResult> callAsync(String skill, Map<String, Object> args) {
        return cancelled.getAsBoolean()
                ? CompletableFuture.completedFuture(cancelledResult(name(), skill))
                : delegate.callAsync(skill, args);
    }

    @Override
    public void stream(String skill, Map<String, Object> args,
                       Consumer<String> onToken, Runnable onComplete) {
        if (cancelled.getAsBoolean()) {
            onComplete.run();
            return;
        }
        delegate.stream(skill, args, onToken, onComplete);
    }

    @Override
    public AgentProxy withDispatchMetadata(Map<String, Object> dispatchMetadata) {
        var metadata = delegate.withDispatchMetadata(dispatchMetadata);
        return metadata == delegate ? this : new CancellableAgentProxy(metadata, cancelled);
    }

    @Override
    public AgentProxy withCancellation(BooleanSupplier other) {
        if (other == null) {
            return this;
        }
        return new CancellableAgentProxy(delegate,
                () -> cancelled.getAsBoolean() || other.getAsBoolean());
    }
}
