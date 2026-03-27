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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;

import java.util.LinkedHashMap;
import java.util.function.Predicate;

/**
 * Stub {@link AgentRuntime} for testing. Returns canned responses matched
 * by predicates against the user message. Has maximum priority so it wins
 * auto-detection in tests.
 *
 * <pre>{@code
 * var runtime = StubAgentRuntime.builder()
 *     .when("weather", "Sunny and 72F")
 *     .when(msg -> msg.contains("joke"), "Why did the chicken...")
 *     .defaultResponse("I don't understand")
 *     .build();
 * }</pre>
 */
public final class StubAgentRuntime implements AgentRuntime {

    private final LinkedHashMap<Predicate<String>, String> responses;
    private final String defaultResponse;

    private StubAgentRuntime(LinkedHashMap<Predicate<String>, String> responses,
                              String defaultResponse) {
        this.responses = responses;
        this.defaultResponse = defaultResponse;
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // no-op
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        var message = context.message();
        for (var entry : responses.entrySet()) {
            if (entry.getKey().test(message)) {
                session.send(entry.getValue());
                session.complete();
                return;
            }
        }
        session.send(defaultResponse);
        session.complete();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final LinkedHashMap<Predicate<String>, String> responses = new LinkedHashMap<>();
        private String defaultResponse = "";

        /** Match when message contains the given string. */
        public Builder when(String contains, String response) {
            return when(msg -> msg.contains(contains), response);
        }

        /** Match with custom predicate. */
        public Builder when(Predicate<String> predicate, String response) {
            responses.put(predicate, response);
            return this;
        }

        /** Default response when no predicate matches. */
        public Builder defaultResponse(String text) {
            this.defaultResponse = text;
            return this;
        }

        public StubAgentRuntime build() {
            return new StubAgentRuntime(new LinkedHashMap<>(responses), defaultResponse);
        }
    }
}
