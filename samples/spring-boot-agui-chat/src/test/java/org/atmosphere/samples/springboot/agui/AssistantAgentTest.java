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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit test for {@link AssistantAgent#onPrompt}: with no LLM key configured it
 * must take the {@link DemoResponseProducer} path (word-by-word
 * {@link StreamingSession#send} + a terminal {@code TextComplete} +
 * {@link StreamingSession#complete}) and must NOT call
 * {@link StreamingSession#stream} (which would require a real pipeline).
 */
class AssistantAgentTest {

    @BeforeEach
    void noKey() {
        // mode=fake → AiConfig.get() non-null but apiKey() null: the documented
        // no-key demo contract. (configure() stores the singleton AiConfig.get reads.)
        AiConfig.configure("fake", "demo", null, null);
        assertNotNull(AiConfig.get(), "settings should be configured");
        assertTrue(AiConfig.get().apiKey() == null || AiConfig.get().apiKey().isBlank(),
                "fake mode must have no apiKey so the demo path is taken");
    }

    @Test
    void onPromptUsesDemoFallbackWhenNoKey() {
        var agent = new AssistantAgent();
        var session = new RecordingSession();

        agent.onPrompt("Hello!", session);

        // Demo path: never delegates to the real pipeline.
        assertFalse(session.streamCalled, "Demo mode must not call session.stream()");
        assertTrue(session.completed, "Demo mode must complete the session");

        // Word-by-word streaming produced at least one text token...
        assertFalse(session.sentTokens.isEmpty(), "Demo mode must stream text via send()");
        // ...and the concatenation contains the stable demo phrase the e2e lane asserts.
        var joined = String.join("", session.sentTokens);
        assertTrue(joined.contains(DemoResponseProducer.DEMO_PHRASE),
                "Demo response must contain the stable phrase '"
                        + DemoResponseProducer.DEMO_PHRASE + "', was: " + joined);

        // A terminal TextComplete must close the message before RUN_FINISHED.
        assertTrue(session.emittedTextComplete,
                "Demo mode must emit a terminal TextComplete (→ TEXT_MESSAGE_END)");
    }

    /**
     * Records exactly what the agent does to the session — no real pipeline,
     * no SSE wire. Mirrors the {@link StreamingSession} contract surface the
     * demo path touches.
     */
    private static final class RecordingSession implements StreamingSession {
        private final String id = UUID.randomUUID().toString();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        final List<String> sentTokens = new ArrayList<>();
        boolean streamCalled;
        boolean completed;
        boolean emittedTextComplete;

        @Override public String sessionId() { return id; }
        @Override public void send(String text) { sentTokens.add(text); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }

        @Override
        public void complete() {
            completed = true;
            closed.set(true);
        }

        @Override
        public void complete(String summary) {
            completed = true;
            closed.set(true);
        }

        @Override
        public void error(Throwable t) {
            closed.set(true);
            fail("Demo path should not error: " + t.getMessage());
        }

        @Override public boolean isClosed() { return closed.get(); }
        @Override public void sendContent(Content content) { }

        @Override
        public void emit(AiEvent event) {
            if (event instanceof AiEvent.TextComplete) {
                emittedTextComplete = true;
            } else if (event instanceof AiEvent.TextDelta delta) {
                sentTokens.add(delta.text());
            }
        }

        @Override
        public void stream(String message) {
            streamCalled = true;
            throw new AssertionError("Demo mode must not invoke the real pipeline via stream()");
        }
    }
}
