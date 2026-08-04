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
package org.atmosphere.samples.springboot.agui;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the sample's demo strategy and prompt dispatch.
 *
 * <p>{@link DemoResponseProducer#generateFor} is the strategy the sample
 * installs on the framework's {@code DemoAgentRuntime} at startup — each
 * keyword branch must return the canned text containing the stable
 * {@link DemoResponseProducer#DEMO_PHRASE} the e2e demo lane asserts.
 * {@link AssistantAgent#onPrompt} must always delegate to
 * {@link StreamingSession#stream} so demo and real-key requests both flow
 * through the real pipeline.</p>
 */
class AssistantAgentTest {

    @AfterEach
    void resetStrategy() {
        // The installer mutates DemoAgentRuntime's process-wide strategy;
        // restore the built-in default so no state leaks across tests.
        DemoAgentRuntime.setResponseStrategy(null);
    }

    @Test
    void installedStrategyDrivesDemoRuntime() {
        // Exercise the real @PostConstruct installation path, not the static
        // generateFor helper: the installed strategy must drive the framework's
        // DemoAgentRuntime end-to-end.
        new DemoResponseProducer().installDemoStrategy();

        var runtime = new DemoAgentRuntime();
        var session = new RecordingSession();
        runtime.execute(context("hello"), session);

        var full = String.join("", session.sentDeltas);
        assertTrue(full.contains(DemoResponseProducer.DEMO_PHRASE),
                "installed strategy must stream the stable phrase, got: " + full);
        assertTrue(session.sentDeltas.size() > 1, "response must stream word-by-word");
        assertTrue(session.completed, "runtime must complete the session");
    }

    @Test
    void weatherBranchReturnsCannedWeatherText() {
        var response = DemoResponseProducer.generateFor(context("What's the weather in Paris?"));
        assertTrue(response.contains("get_weather"),
                "weather branch must mention the get_weather tool, was: " + response);
        assertTrue(response.contains(DemoResponseProducer.DEMO_PHRASE),
                "weather branch must contain the stable phrase, was: " + response);
    }

    @Test
    void timeBranchReturnsCannedTimeText() {
        var response = DemoResponseProducer.generateFor(context("What time is it in Tokyo?"));
        assertTrue(response.contains("get_time"),
                "time branch must mention the get_time tool, was: " + response);
        assertTrue(response.contains(DemoResponseProducer.DEMO_PHRASE),
                "time branch must contain the stable phrase, was: " + response);
    }

    @Test
    void helloBranchReturnsCannedGreeting() {
        for (var greeting : List.of("Hello!", "hi there")) {
            var response = DemoResponseProducer.generateFor(context(greeting));
            assertTrue(response.startsWith("Hello!"),
                    "greeting branch must return the canned greeting, was: " + response);
            assertTrue(response.contains(DemoResponseProducer.DEMO_PHRASE),
                    "greeting branch must contain the stable phrase, was: " + response);
        }
    }

    @Test
    void defaultBranchReturnsCannedFallback() {
        var response = DemoResponseProducer.generateFor(context("Tell me about Atmosphere"));
        assertTrue(response.contains("TEXT_MESSAGE_CONTENT"),
                "default branch must explain the frame-by-frame streaming, was: " + response);
        assertTrue(response.contains(DemoResponseProducer.DEMO_PHRASE),
                "default branch must contain the stable phrase, was: " + response);
    }

    @Test
    void onPromptDelegatesToSessionStream() {
        var agent = new AssistantAgent();
        var session = new RecordingSession();

        agent.onPrompt("Hello!", session);

        assertEquals(List.of("Hello!"), session.streamedMessages,
                "@Prompt must delegate the user message to session.stream()");
    }

    private static AgentExecutionContext context(String message) {
        return new AgentExecutionContext(message, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Records stream() dispatches (the surface {@code onPrompt} touches) and
     * send() deltas plus completion (the surfaces {@code DemoAgentRuntime}
     * drives when the installed strategy streams a demo response).
     */
    private static final class RecordingSession implements StreamingSession {
        private final String id = UUID.randomUUID().toString();
        final List<String> streamedMessages = new ArrayList<>();
        final List<String> sentDeltas = new ArrayList<>();
        boolean completed;

        @Override public String sessionId() { return id; }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { completed = true; }
        @Override public void complete(String summary) { completed = true; }
        @Override public void error(Throwable t) { throw new AssertionError("unexpected session error", t); }
        @Override public boolean isClosed() { return completed; }
        @Override public void emit(AiEvent event) { }
        @Override public void sendContent(Content content) { }

        @Override
        public void send(String text) {
            sentDeltas.add(text);
        }

        @Override
        public void stream(String message) {
            streamedMessages.add(message);
        }
    }
}
