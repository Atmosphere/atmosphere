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
package org.atmosphere.ai.lineage;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link LineageCapturingSession} — verifies the audit
 * row produced for a {@code @Prompt} invocation captures every lineage fact
 * (tool calls, RAG sources, tokens, cost, terminal reason) at the right
 * shape and exactly once per terminal callback.
 */
class LineageCapturingSessionTest {

    @Test
    void completeProducesEntryWithOkTerminalReason() {
        var recorder = new InMemoryLineageRecorder();
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "user-1", "agent-1", "conv-1", "How do I reset my password?");

        session.send("Try the reset link.");
        session.complete();

        var entries = recorder.recent(10);
        assertEquals(1, entries.size());
        var entry = entries.get(0);
        assertEquals("user-1", entry.userId());
        assertEquals("agent-1", entry.agentId());
        assertEquals("conv-1", entry.conversationId());
        assertEquals("How do I reset my password?", entry.userPrompt());
        assertEquals("OK", entry.terminalReason());
        assertTrue(entry.requestId().startsWith("lin_"));
    }

    @Test
    void errorProducesEntryWithErrorTerminalReason() {
        var recorder = new InMemoryLineageRecorder();
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "u", "a", "c", "prompt");

        session.error(new RuntimeException("boom"));

        assertEquals("ERROR", recorder.recent(1).get(0).terminalReason());
    }

    @Test
    void securityExceptionClassifiesAsDenied() {
        var recorder = new InMemoryLineageRecorder();
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "u", "a", "c", "prompt");

        session.error(new SecurityException("blocked"));

        assertEquals("DENIED", recorder.recent(1).get(0).terminalReason());
    }

    @Test
    void terminalCallbackIsIdempotent() {
        var recorder = new InMemoryLineageRecorder();
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "u", "a", "c", "prompt");

        session.complete();
        session.complete();
        session.error(new RuntimeException("late"));

        assertEquals(1, recorder.recent(10).size(),
                "terminal callbacks must produce exactly one lineage entry");
    }

    @Test
    void toolStartAndResultPairsCapturedInOrder() {
        var recorder = new InMemoryLineageRecorder();
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "u", "a", "c", "prompt");

        session.emit(new AiEvent.ToolStart("get_weather", Map.of("city", "Montreal")));
        session.emit(new AiEvent.ToolResult("get_weather", "{\"temp\":-5}"));
        session.emit(new AiEvent.ToolStart("send_email", Map.of("to", "alice@example.com")));
        session.emit(new AiEvent.ToolResult("send_email", "{\"id\":\"msg-1\"}"));
        session.complete();

        var entry = recorder.recent(1).get(0);
        assertEquals(2, entry.toolCalls().size());
        assertEquals("get_weather", entry.toolCalls().get(0).name());
        assertEquals("{\"temp\":-5}", entry.toolCalls().get(0).resultSummary());
        assertEquals("send_email", entry.toolCalls().get(1).name());
        assertEquals("{\"id\":\"msg-1\"}", entry.toolCalls().get(1).resultSummary());
    }

    @Test
    void usageCallbackPopulatesTokenField() {
        var recorder = new InMemoryLineageRecorder();
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "u", "a", "c", "prompt");

        session.usage(TokenUsage.of(120, 480, 600));
        session.complete();

        var entry = recorder.recent(1).get(0);
        assertTrue(entry.tokens().isPresent());
        assertEquals(120, entry.tokens().get().input());
        assertEquals(480, entry.tokens().get().output());
    }

    @Test
    void costMetadataPopulatesCostField() {
        var recorder = new InMemoryLineageRecorder();
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "u", "a", "c", "prompt");

        session.sendMetadata("ai.cost.usd", new BigDecimal("0.0042"));
        session.complete();

        var entry = recorder.recent(1).get(0);
        assertTrue(entry.cost().isPresent());
        assertEquals(0, new BigDecimal("0.0042").compareTo(entry.cost().get()));
    }

    @Test
    void ragSourceMetadataAccumulates() {
        var recorder = new InMemoryLineageRecorder();
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "u", "a", "c", "prompt");

        session.sendMetadata("rag.source", "doc://kb/article-1");
        session.sendMetadata("rag.source", "doc://kb/article-2");
        session.complete();

        assertEquals(2, recorder.recent(1).get(0).ragSources().size());
    }

    @Test
    void promptTruncatedAtMaxChars() {
        var recorder = new InMemoryLineageRecorder();
        var longPrompt = "x".repeat(LineageEntry.MAX_PROMPT_CHARS + 100);
        var session = new LineageCapturingSession(new CollectingSession(),
                recorder, "u", "a", "c", longPrompt);

        session.complete();

        var entry = recorder.recent(1).get(0);
        assertEquals(LineageEntry.MAX_PROMPT_CHARS, entry.userPrompt().length(),
                "prompt over MAX_PROMPT_CHARS must be truncated");
        assertTrue(entry.userPrompt().endsWith("…"),
                "truncation must end with the ellipsis sentinel");
    }

    @Test
    void inMemoryRecorderIsBoundedByCapacity() {
        var recorder = new InMemoryLineageRecorder(3);
        for (int i = 0; i < 10; i++) {
            new LineageCapturingSession(new CollectingSession(),
                    recorder, "u", "a", "c", "p" + i).complete();
        }
        assertEquals(3, recorder.size(),
                "InMemoryLineageRecorder must respect capacity (Invariant #3 backpressure)");
    }

    @Test
    void recorderHolderInstallsAndResets() {
        try {
            var recorder = new InMemoryLineageRecorder();
            LineageRecorderHolder.install(recorder);
            assertSame(recorder, LineageRecorderHolder.get());
        } finally {
            LineageRecorderHolder.reset();
        }
        assertSame(LineageRecorder.NOOP, LineageRecorderHolder.get());
    }

    @Test
    void recorderHolderInstallNullDefaultsToNoop() {
        try {
            LineageRecorderHolder.install(null);
            assertSame(LineageRecorder.NOOP, LineageRecorderHolder.get());
        } finally {
            LineageRecorderHolder.reset();
        }
    }
}
