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
package org.atmosphere.coordinator.test;

import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.fleet.RoutingSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Stub {@link AgentFleet} for testing coordinator {@code @Prompt} methods
 * without any infrastructure.
 *
 * <pre>{@code
 * var fleet = StubAgentFleet.builder()
 *     .agent("weather", "Sunny, 72F in Madrid")
 *     .agent("activities", "Visit Retiro Park")
 *     .build();
 *
 * coordinator.onPrompt("What to do in Madrid?", fleet, session);
 * }</pre>
 */
public final class StubAgentFleet implements AgentFleet {

    private final DefaultAgentFleet delegate;

    private StubAgentFleet(DefaultAgentFleet delegate) {
        this.delegate = delegate;
    }

    @Override
    public AgentProxy agent(String name) {
        return delegate.agent(name);
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
        return delegate.parallel(calls);
    }

    @Override
    public AgentResult pipeline(AgentCall... calls) {
        return delegate.pipeline(calls);
    }

    @Override
    public AgentResult route(AgentResult input, Consumer<RoutingSpec> spec) {
        return delegate.route(input, spec);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final LinkedHashMap<String, StubAgentTransport> transports = new LinkedHashMap<>();

        /** Add an agent with a custom transport. */
        public Builder agent(String name, StubAgentTransport transport) {
            transports.put(name, transport);
            return this;
        }

        /** Add an agent that always returns the given text. */
        public Builder agent(String name, String cannedResponse) {
            return agent(name, StubAgentTransport.builder()
                    .defaultResponse(cannedResponse).build());
        }

        public StubAgentFleet build() {
            var proxies = new LinkedHashMap<String, AgentProxy>();
            for (var entry : transports.entrySet()) {
                proxies.put(entry.getKey(), new DefaultAgentProxy(
                        entry.getKey(), "1.0.0", 1, true, entry.getValue()));
            }
            return new StubAgentFleet(new DefaultAgentFleet(proxies));
        }
    }
}
