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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AiEvent;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test client for AI endpoints. Captures streaming text, events, and metadata
 * for assertion in JUnit 5 tests.
 *
 * <pre>{@code
 * var client = new AiTestClient(myAiSupport);
 * var response = client.prompt("What's the weather?");
 *
 * assertThat(response).hasToolCall("get_weather");
 * assertThat(response).completedWithin(Duration.ofSeconds(5));
 * }</pre>
 *
 * @see AiResponse
 * @see AiAssertions
 */
@SuppressWarnings({"deprecation", "removal"})
public class AiTestClient {

    private final AgentRuntime runtime;

    public AiTestClient(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Send a prompt and capture the full response.
     *
     * @param message the user prompt
     * @return the captured response for assertion
     */
    public AiResponse prompt(String message) {
        return prompt(message, "");
    }

    /**
     * Send a prompt with a system prompt and capture the full response.
     */
    public AiResponse prompt(String message, String systemPrompt) {
        var session = new CapturingSession();

        var start = Instant.now();
        var context = new AgentExecutionContext(
                message, systemPrompt, null, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null);
        runtime.execute(context, session);
        session.awaitCompletion(Duration.ofSeconds(30));
        var elapsed = Duration.between(start, Instant.now());

        return new AiResponse(
                session.fullText(),
                session.events(),
                session.metadata(),
                session.errors(),
                elapsed,
                session.isCompleted()
        );
    }

    /**
     * A streaming session that captures everything for testing.
     */
    private static class CapturingSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private final List<AiEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> metadata = new ConcurrentHashMap<>();
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean completed;

        @Override
        public String sessionId() {
            return "test-session";
        }

        @Override
        public void send(String chunk) {
            text.append(chunk);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            if (value != null) {
                metadata.put(key, value);
            }
        }

        @Override
        public void progress(String message) {
            metadata.put("_progress", message);
        }

        @Override
        public void complete() {
            completed = true;
            done.countDown();
        }

        @Override
        public void complete(String summary) {
            if (summary != null) {
                text.setLength(0);
                text.append(summary);
            }
            completed = true;
            done.countDown();
        }

        @Override
        public void error(Throwable t) {
            errors.add(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            done.countDown();
        }

        @Override
        public boolean isClosed() {
            return done.getCount() == 0;
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
            // Also map to legacy methods for text accumulation
            switch (event) {
                case AiEvent.TextDelta delta -> text.append(delta.text());
                case AiEvent.Complete c -> {
                    completed = true;
                    done.countDown();
                }
                case AiEvent.Error err -> {
                    errors.add(err.message());
                    done.countDown();
                }
                default -> { }
            }
        }

        void awaitCompletion(Duration timeout) {
            try {
                done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String fullText() {
            return text.toString();
        }

        List<AiEvent> events() {
            return List.copyOf(events);
        }

        Map<String, Object> metadata() {
            return Map.copyOf(metadata);
        }

        List<String> errors() {
            return List.copyOf(errors);
        }

        boolean isCompleted() {
            return completed;
        }
    }
}
