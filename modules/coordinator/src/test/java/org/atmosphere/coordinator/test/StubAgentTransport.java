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

import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.transport.AgentTransport;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Stub {@link AgentTransport} for testing. Uses builder pattern with predicate
 * matching to return canned responses.
 *
 * <pre>{@code
 * var transport = StubAgentTransport.builder()
 *     .when("weather", "Sunny, 72F")
 *     .when("news", "No news today")
 *     .defaultResponse("I don't know")
 *     .build();
 * }</pre>
 */
public final class StubAgentTransport implements AgentTransport {

    private final LinkedHashMap<Predicate<String>, AgentResult> canned;
    private final AgentResult defaultResult;
    private final boolean available;

    private StubAgentTransport(LinkedHashMap<Predicate<String>, AgentResult> canned,
                                AgentResult defaultResult, boolean available) {
        this.canned = canned;
        this.defaultResult = defaultResult;
        this.available = available;
    }

    @Override
    public AgentResult send(String agentName, String skill, Map<String, Object> args) {
        var inputText = args.values().isEmpty()
                ? skill : String.valueOf(args.values().iterator().next());
        for (var entry : canned.entrySet()) {
            if (entry.getKey().test(inputText) || entry.getKey().test(skill)) {
                return entry.getValue();
            }
        }
        return defaultResult;
    }

    @Override
    public void stream(String agentName, String skill, Map<String, Object> args,
                       Consumer<String> onToken, Runnable onComplete) {
        var result = send(agentName, skill, args);
        if (result.text() != null) {
            onToken.accept(result.text());
        }
        onComplete.run();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final LinkedHashMap<Predicate<String>, AgentResult> canned = new LinkedHashMap<>();
        private AgentResult defaultResult = new AgentResult(
                "stub", "stub", "", Map.of(), Duration.ZERO, true);
        private boolean available = true;

        /** Match when input or skill contains the given string. */
        public Builder when(String contains, String responseText) {
            return when(
                    s -> s.contains(contains),
                    new AgentResult("stub", "stub", responseText, Map.of(), Duration.ZERO, true));
        }

        /** Match with custom predicate. */
        public Builder when(Predicate<String> predicate, AgentResult result) {
            canned.put(predicate, result);
            return this;
        }

        /** Default response when no predicate matches. */
        public Builder defaultResponse(String text) {
            this.defaultResult = new AgentResult(
                    "stub", "stub", text, Map.of(), Duration.ZERO, true);
            return this;
        }

        /** Mark this transport as unavailable. */
        public Builder unavailable() {
            this.available = false;
            return this;
        }

        public StubAgentTransport build() {
            return new StubAgentTransport(new LinkedHashMap<>(canned), defaultResult, available);
        }
    }
}
