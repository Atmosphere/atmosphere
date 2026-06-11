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
package org.atmosphere.ai;

import org.atmosphere.ai.devinspector.InMemoryDevInspectorRecorder;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevInspectorCapturingSessionTest {

    @Test
    void recordsSuccessfulTurnAndForwardsToDelegate() {
        var recorder = new InMemoryDevInspectorRecorder();
        var fake = new FakeSession();
        var session = new DevInspectorCapturingSession(fake, recorder, "gpt", "my prompt");

        session.send("hello ");
        session.send("world");
        session.toolCallDelta("t1", "{\"city\":\"Paris\"}");
        session.usage(TokenUsage.of(5, 3, 8, "gpt"));
        session.complete();

        var entry = recorder.recent(1).get(0);
        assertEquals("my prompt", entry.promptPreview());
        assertEquals("hello world", entry.responsePreview());
        assertEquals("gpt", entry.model());
        assertEquals(5, entry.tokensIn());
        assertEquals(3, entry.tokensOut());
        assertEquals("OK", entry.status());
        assertTrue(entry.toolCalls().stream().anyMatch(c -> c.contains("Paris")));

        // Decorator transparency: everything still reaches the delegate.
        assertEquals("hello world", fake.sent.toString());
        assertTrue(fake.completed.get());
        assertEquals(8, fake.usage.get().total());
    }

    @Test
    void recordsErrorTurn() {
        var recorder = new InMemoryDevInspectorRecorder();
        var session = new DevInspectorCapturingSession(new FakeSession(), recorder, "m", "p");
        session.error(new IllegalStateException("boom"));

        var entry = recorder.recent(1).get(0);
        assertEquals("ERROR", entry.status());
        assertEquals("boom", entry.error());
    }

    @Test
    void recordsAtMostOncePerTurn() {
        var recorder = new InMemoryDevInspectorRecorder();
        var session = new DevInspectorCapturingSession(new FakeSession(), recorder, "m", "p");
        session.complete();
        session.error(new RuntimeException("late"));
        assertEquals(1, recorder.size(), "a turn records exactly once (first terminal wins)");
        assertEquals("OK", recorder.recent(1).get(0).status());
    }

    /** Minimal delegate capturing what the decorator forwards. */
    private static final class FakeSession implements StreamingSession {
        final StringBuilder sent = new StringBuilder();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<TokenUsage> usage = new AtomicReference<>();

        @Override public String sessionId() {
            return "fake";
        }

        @Override public void send(String text) {
            sent.append(text);
        }

        @Override public void usage(TokenUsage u) {
            usage.set(u);
        }

        @Override public void sendMetadata(String key, Object value) {
        }

        @Override public void progress(String message) {
        }

        @Override public void complete() {
            completed.set(true);
        }

        @Override public void complete(String summary) {
            completed.set(true);
        }

        @Override public void error(Throwable t) {
        }

        @Override public boolean isClosed() {
            return completed.get();
        }
    }
}
