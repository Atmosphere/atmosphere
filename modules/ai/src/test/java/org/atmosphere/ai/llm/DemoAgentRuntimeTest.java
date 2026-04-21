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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoAgentRuntimeTest {

    @BeforeEach
    void resetConfig() {
        // No-key state for the default test scenario.
        AiConfig.configure("remote", "demo-model", null, null);
        AgentRuntimeResolver.reset();
        DemoAgentRuntime.setResponseStrategy(null);
    }

    @AfterEach
    void tearDown() {
        AiConfig.configure("remote", "demo-model", null, null);
        AgentRuntimeResolver.reset();
        DemoAgentRuntime.setResponseStrategy(null);
    }

    @Test
    void isAvailableWhenNoApiKeyConfigured() {
        var runtime = new DemoAgentRuntime();
        assertTrue(runtime.isAvailable(),
                "Demo runtime must advertise availability when AiConfig has no key");
    }

    @Test
    void isNotAvailableWhenApiKeyConfigured() {
        AiConfig.configure("remote", "gpt-4o-mini", "sk-real-key",
                "https://api.openai.com/v1");

        var runtime = new DemoAgentRuntime();
        assertFalse(runtime.isAvailable(),
                "Demo runtime must step aside when a real key is configured");
    }

    @Test
    void isNotAvailableWhenApiKeyIsBlank() {
        AiConfig.configure("remote", "gpt-4o-mini", "   ",
                "https://api.openai.com/v1");

        var runtime = new DemoAgentRuntime();
        assertTrue(runtime.isAvailable(),
                "Blank keys count as no-key — demo should take over");
    }

    @Test
    void priorityIsMaxSoDemoWinsWhenAvailable() {
        assertEquals(Integer.MAX_VALUE, new DemoAgentRuntime().priority());
    }

    @Test
    void runtimeIsDiscoverableViaServiceLoader() {
        var found = StreamSupport.stream(ServiceLoader.load(AgentRuntime.class).spliterator(), false)
                .anyMatch(r -> r instanceof DemoAgentRuntime);
        assertTrue(found,
                "DemoAgentRuntime must be registered under META-INF/services/org.atmosphere.ai.AgentRuntime");
    }

    @Test
    void demoWinsResolutionWhenNoKey() {
        var runtime = AgentRuntimeResolver.resolve();
        assertEquals("demo", runtime.name(),
                "With no API key, the demo runtime must be the top pick");
    }

    @Test
    void demoStepsAsideWhenKeyIsConfigured() {
        AiConfig.configure("remote", "gpt-4o-mini", "sk-real-key",
                "https://api.openai.com/v1");
        AgentRuntimeResolver.reset();

        var runtime = AgentRuntimeResolver.resolve();
        assertFalse(runtime instanceof DemoAgentRuntime,
                "A configured key must let a real runtime take over; got: " + runtime.name());
    }

    @Test
    void executeStreamsResponseThroughSession() {
        var session = new RecordingSession();
        var ctx = new AgentExecutionContext(
                "How do I center a div?", "You are helpful", "demo-model",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null, null,
                List.of(), List.of(), null, null);

        new DemoAgentRuntime().execute(ctx, session);

        assertTrue(session.progressMessages.stream().anyMatch(m -> m.contains("Demo mode")),
                "Demo runtime must emit a progress frame so the UI shows the demo banner");
        assertFalse(session.textFrames.isEmpty(),
                "Demo runtime must emit at least one streaming-text frame");
        assertTrue(session.completed.get(),
                "Demo runtime must complete the session so stats populate");
        assertNotNull(session.completionSummary,
                "Demo runtime must provide a completion summary for the frontend's stats panel");
        assertTrue(session.textFrames.stream().reduce("", String::concat)
                        .contains("center a div"),
                "Default strategy should echo the user's prompt back");
    }

    @Test
    void customStrategyOverridesDefaultResponse() {
        DemoAgentRuntime.setResponseStrategy(ctx -> "Room-specific hello");
        var session = new RecordingSession();
        var ctx = new AgentExecutionContext(
                "anything", "system", "model",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null, null,
                List.of(), List.of(), null, null);

        new DemoAgentRuntime().execute(ctx, session);

        var streamed = session.textFrames.stream().reduce("", String::concat);
        assertEquals("Room-specific hello", streamed,
                "Installed strategy must replace the default echo response");
    }

    /** Minimal StreamingSession recorder — no pipeline, no broadcaster. */
    private static final class RecordingSession implements StreamingSession {
        final List<String> textFrames = new ArrayList<>();
        final List<String> progressMessages = new ArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        volatile String completionSummary;

        @Override public String sessionId() { return "recording"; }
        @Override public void send(String text) { textFrames.add(text); }
        @Override public void sendMetadata(String key, Object value) { /* unused */ }
        @Override public void progress(String message) { progressMessages.add(message); }
        @Override public void complete() { completed.set(true); }
        @Override public void complete(String summary) {
            completed.set(true);
            completionSummary = summary;
        }
        @Override public void error(Throwable t) { /* unused */ }
        @Override public boolean isClosed() { return false; }
        @Override public boolean hasErrored() { return false; }
        @Override public void close() { /* no-op */ }
    }
}
