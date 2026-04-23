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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link AgentFleet} wrapper that runs {@link FleetInterceptor}s before
 * every dispatch. The chain short-circuits on the first non-Proceed
 * decision: Rewrite replaces the call, Deny synthesizes a failed
 * {@link AgentResult} and skips the transport hop.
 *
 * <p>Goal 2 per-dispatch enforcement lives here — a
 * {@code ScopeFleetInterceptor} can deny {@code call("research",
 * "write_code", …)} the same way {@code PolicyAdmissionGate} denies off-
 * scope user prompts. Same governance surface, agent-to-agent edge.</p>
 *
 * <p>Dispatches denied here are not invisible — they can be observed
 * through the wrapped fleet's activity listener / journal if those are
 * installed ahead of this wrapper. Order of wrapping matters; compose
 * {@code journal(intercepting(base))} to record denies, or
 * {@code intercepting(journal(base))} to deny before journaling.</p>
 */
public final class InterceptingAgentFleet implements AgentFleet {

    private final AgentFleet delegate;
    private final List<FleetInterceptor> interceptors;

    public InterceptingAgentFleet(AgentFleet delegate, List<FleetInterceptor> interceptors) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(interceptors, "interceptors");
        if (interceptors.isEmpty()) {
            throw new IllegalArgumentException("interceptors must be non-empty");
        }
        for (var interceptor : interceptors) {
            if (interceptor == null) {
                throw new IllegalArgumentException("interceptors must not contain null");
            }
        }
        this.interceptors = List.copyOf(interceptors);
    }

    @Override
    public AgentProxy agent(String name) {
        return new InterceptingAgentProxy(delegate.agent(name));
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
        var effective = new ArrayList<AgentCall>(calls.length);
        var synthetic = new LinkedHashMap<String, AgentResult>();
        for (var call : calls) {
            var decision = runChain(call);
            switch (decision) {
                case FleetInterceptor.Decision.Proceed ignored -> effective.add(call);
                case FleetInterceptor.Decision.Rewrite rewrite -> effective.add(rewrite.modifiedCall());
                case FleetInterceptor.Decision.Deny deny -> synthetic.put(call.agentName(),
                        AgentResult.failure(call.agentName(), call.skill(),
                                "fleet-interceptor denied: " + deny.reason(),
                                Duration.ZERO));
            }
        }
        if (effective.isEmpty()) {
            return Map.copyOf(synthetic);
        }
        var delegateResults = delegate.parallel(effective.toArray(AgentCall[]::new));
        var merged = new LinkedHashMap<String, AgentResult>(synthetic);
        merged.putAll(delegateResults);
        return Map.copyOf(merged);
    }

    @Override
    public AgentFleet withActivityListener(AgentActivityListener listener) {
        return new InterceptingAgentFleet(delegate.withActivityListener(listener), interceptors);
    }

    @Override
    public AgentResult route(AgentResult result, java.util.function.Consumer<RoutingSpec> spec) {
        // The route DSL runs in terms of AgentCall execution on `this` — so
        // interceptors apply naturally when the routing body calls fleet.agent(...).
        return delegate.route(result, spec);
    }

    @Override
    public AgentResult pipeline(AgentCall... calls) {
        // Pipeline is semantically a sequence of interceptor-gated calls;
        // route each through .agent(...).call(...) so denies short-circuit.
        AgentResult last = null;
        for (var call : calls) {
            last = agent(call.agentName()).call(call.skill(), call.args());
            if (!last.success()) {
                return last;
            }
        }
        if (last == null) {
            throw new IllegalArgumentException("pipeline requires at least one call");
        }
        return last;
    }

    public AgentFleet unwrap() {
        return delegate;
    }

    public List<FleetInterceptor> interceptors() {
        return interceptors;
    }

    private FleetInterceptor.Decision runChain(AgentCall original) {
        var current = original;
        for (var interceptor : interceptors) {
            var decision = interceptor.before(current);
            if (decision instanceof FleetInterceptor.Decision.Rewrite rewrite) {
                current = rewrite.modifiedCall();
                continue;
            }
            if (!(decision instanceof FleetInterceptor.Decision.Proceed)) {
                return decision;
            }
        }
        // If at least one rewrite happened, surface it so callers see the final form.
        if (current != original) {
            return FleetInterceptor.Decision.rewrite(current);
        }
        return FleetInterceptor.Decision.proceed();
    }

    /**
     * Proxy that wraps the delegate's proxy so {@code .call(skill, args)}
     * runs the interceptor chain before the real dispatch.
     */
    private final class InterceptingAgentProxy implements AgentProxy {
        private final AgentProxy delegateProxy;

        InterceptingAgentProxy(AgentProxy delegateProxy) {
            this.delegateProxy = delegateProxy;
        }

        @Override public String name() { return delegateProxy.name(); }
        @Override public String version() { return delegateProxy.version(); }
        @Override public int weight() { return delegateProxy.weight(); }
        @Override public boolean isAvailable() { return delegateProxy.isAvailable(); }
        @Override public boolean isLocal() { return delegateProxy.isLocal(); }

        @Override
        public AgentResult call(String skill, Map<String, Object> args) {
            var proposed = new AgentCall(delegateProxy.name(), skill, args);
            var decision = runChain(proposed);
            return switch (decision) {
                case FleetInterceptor.Decision.Proceed ignored ->
                        delegateProxy.call(skill, args);
                case FleetInterceptor.Decision.Rewrite rewrite ->
                        delegateProxy.call(rewrite.modifiedCall().skill(),
                                rewrite.modifiedCall().args());
                case FleetInterceptor.Decision.Deny deny ->
                        AgentResult.failure(delegateProxy.name(), skill,
                                "fleet-interceptor denied: " + deny.reason(),
                                Duration.ZERO);
            };
        }

        @Override
        public java.util.concurrent.CompletableFuture<AgentResult> callAsync(
                String skill, Map<String, Object> args) {
            var proposed = new AgentCall(delegateProxy.name(), skill, args);
            var decision = runChain(proposed);
            return switch (decision) {
                case FleetInterceptor.Decision.Proceed ignored ->
                        delegateProxy.callAsync(skill, args);
                case FleetInterceptor.Decision.Rewrite rewrite ->
                        delegateProxy.callAsync(rewrite.modifiedCall().skill(),
                                rewrite.modifiedCall().args());
                case FleetInterceptor.Decision.Deny deny ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                AgentResult.failure(delegateProxy.name(), skill,
                                        "fleet-interceptor denied: " + deny.reason(),
                                        Duration.ZERO));
            };
        }

        @Override
        public void stream(String skill, Map<String, Object> args,
                            java.util.function.Consumer<String> onToken,
                            Runnable onComplete) {
            var proposed = new AgentCall(delegateProxy.name(), skill, args);
            var decision = runChain(proposed);
            switch (decision) {
                case FleetInterceptor.Decision.Proceed ignored ->
                        delegateProxy.stream(skill, args, onToken, onComplete);
                case FleetInterceptor.Decision.Rewrite rewrite ->
                        delegateProxy.stream(rewrite.modifiedCall().skill(),
                                rewrite.modifiedCall().args(), onToken, onComplete);
                case FleetInterceptor.Decision.Deny deny -> {
                    // Surface the denial on the stream so consumers see the gate
                    // decision, then complete cleanly.
                    onToken.accept("[dispatch denied: " + deny.reason() + "]");
                    onComplete.run();
                }
            }
        }
    }
}
