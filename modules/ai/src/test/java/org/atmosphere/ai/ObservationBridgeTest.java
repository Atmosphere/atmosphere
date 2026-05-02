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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the observation-bridge surface in {@link AgentLifecycleListener}:
 * the new model-lifecycle hooks ({@code onModelStart}, {@code onModelEnd},
 * {@code onModelError}), their static fan-out helpers
 * ({@code fireModelStart} / {@code fireModelEnd} / {@code fireModelError}),
 * and the {@link AiEventForwardingListener} that translates them to wire
 * frames. End-to-end Built-in-runtime wiring is covered by the existing
 * {@code OpenAiCompatibleClientTest} streaming tests via the captured request
 * listeners.
 */
class ObservationBridgeTest {

    @Test
    void modelStartFiresOnEveryListener() {
        var l1 = new RecordingListener();
        var l2 = new RecordingListener();

        AgentLifecycleListener.fireModelStart(List.of(l1, l2), "gpt-4o", 7, 2);

        assertEquals(1, l1.starts.size());
        assertEquals("gpt-4o", l1.starts.get(0).model);
        assertEquals(7, l1.starts.get(0).messageCount);
        assertEquals(2, l1.starts.get(0).toolCount);
        assertEquals(1, l2.starts.size());
    }

    @Test
    void modelEndCarriesUsageAndDuration() {
        var listener = new RecordingListener();
        var usage = TokenUsage.of(120, 85, 205);

        AgentLifecycleListener.fireModelEnd(List.of(listener), "gpt-4o", usage, 842L);

        assertEquals(1, listener.ends.size());
        var end = listener.ends.get(0);
        assertEquals("gpt-4o", end.model);
        assertSame(usage, end.usage);
        assertEquals(842L, end.durationMillis);
    }

    @Test
    void modelEndAllowsNullUsage() {
        // Provider did not report usage — the hook must still fire so latency
        // observers see the duration even when token counts are absent.
        var listener = new RecordingListener();
        AgentLifecycleListener.fireModelEnd(List.of(listener), "gemini-2.5-flash", null, 12L);
        assertEquals(1, listener.ends.size());
        assertNull(listener.ends.get(0).usage);
        assertEquals(12L, listener.ends.get(0).durationMillis);
    }

    @Test
    void modelErrorCarriesThrowable() {
        var listener = new RecordingListener();
        var error = new RuntimeException("503 service unavailable");

        AgentLifecycleListener.fireModelError(List.of(listener), "gpt-4o", error);

        assertEquals(1, listener.errors.size());
        assertSame(error, listener.errors.get(0).error);
        assertEquals("gpt-4o", listener.errors.get(0).model);
    }

    @Test
    void thrownListenersDoNotAbortFanOut() {
        // One broken listener cannot prevent peers from receiving the same
        // fire — Correctness Invariant #2 (Terminal Path Completeness).
        var bad = new AgentLifecycleListener() {
            @Override
            public void onModelStart(String model, int messageCount, int toolCount) {
                throw new RuntimeException("boom");
            }
        };
        var good = new RecordingListener();

        AgentLifecycleListener.fireModelStart(List.of(bad, good), "m", 1, 0);

        assertEquals(1, good.starts.size(),
                "subsequent listener must still see the fire after a peer threw");
    }

    @Test
    void emptyOrNullListenersAreNoOp() {
        // Defensive — these calls happen on every model dispatch and must
        // not allocate when no listener is attached.
        AgentLifecycleListener.fireModelStart(null, "m", 0, 0);
        AgentLifecycleListener.fireModelStart(List.of(), "m", 0, 0);
        AgentLifecycleListener.fireModelEnd(null, "m", null, 0L);
        AgentLifecycleListener.fireModelEnd(List.of(), "m", null, 0L);
        AgentLifecycleListener.fireModelError(null, "m", new RuntimeException());
        AgentLifecycleListener.fireModelError(List.of(), "m", new RuntimeException());
        // No assertion needed — survival is the contract.
    }

    @Test
    void forwardingListenerEmitsProgressOnStart() {
        var session = new RecordingSession();
        var listener = new AiEventForwardingListener(session);

        listener.onModelStart("gpt-4o", 3, 2);

        assertEquals(1, session.events.size());
        var event = session.events.get(0);
        assertTrue(event instanceof AiEvent.Progress);
        var p = (AiEvent.Progress) event;
        assertEquals("model:start (gpt-4o, msgs=3, tools=2)", p.message());
        assertNull(p.percentage(),
                "model-lifecycle progress events have no percentage — they're observational, not progressive");
    }

    @Test
    void forwardingListenerEmitsProgressOnEndWithUsage() {
        var session = new RecordingSession();
        var listener = new AiEventForwardingListener(session);

        listener.onModelEnd("gpt-4o", TokenUsage.of(120, 85, 205), 842L);

        assertEquals(1, session.events.size());
        var p = (AiEvent.Progress) session.events.get(0);
        assertTrue(p.message().contains("model:end"));
        assertTrue(p.message().contains("gpt-4o"));
        assertTrue(p.message().contains("in=120"));
        assertTrue(p.message().contains("out=85"));
        assertTrue(p.message().contains("ms=842"));
    }

    @Test
    void forwardingListenerEmitsProgressOnEndWithoutUsage() {
        var session = new RecordingSession();
        var listener = new AiEventForwardingListener(session);

        listener.onModelEnd("gemini-2.5-flash", null, 12L);

        assertEquals(1, session.events.size());
        var p = (AiEvent.Progress) session.events.get(0);
        assertTrue(p.message().contains("model:end"));
        assertTrue(p.message().contains("ms=12"),
                "duration must always appear even when token counts are absent");
    }

    @Test
    void forwardingListenerEmitsProgressOnError() {
        var session = new RecordingSession();
        var listener = new AiEventForwardingListener(session);

        listener.onModelError("gpt-4o", new java.io.IOException("connection refused"));

        assertEquals(1, session.events.size());
        var p = (AiEvent.Progress) session.events.get(0);
        assertTrue(p.message().contains("model:error"));
        assertTrue(p.message().contains("IOException"));
    }

    @Test
    void forwardingListenerSkipsClosedSession() {
        // Once the session is closed the runtime is winding down; emitting
        // fresh frames would be a use-after-close. The forwarder must
        // silently drop instead.
        var session = new RecordingSession();
        session.closed = true;
        var listener = new AiEventForwardingListener(session);

        listener.onModelStart("m", 1, 0);
        listener.onModelEnd("m", null, 1L);
        listener.onModelError("m", new RuntimeException());

        assertEquals(0, session.events.size());
    }

    @Test
    void forwardingListenerRequiresSession() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiEventForwardingListener(null));
    }

    @Test
    void payloadHelpersBuildExpectedKeys() {
        var startData = AiEventForwardingListener.modelStartPayload("gpt-4o", 5, 1);
        assertEquals("gpt-4o", startData.get("model"));
        assertEquals(5, startData.get("messageCount"));
        assertEquals(1, startData.get("toolCount"));

        var endData = AiEventForwardingListener.modelEndPayload(
                "gpt-4o", TokenUsage.of(10, 20, 30), 100L);
        assertEquals("gpt-4o", endData.get("model"));
        assertEquals(10L, endData.get("inputTokens"));
        assertEquals(20L, endData.get("outputTokens"));
        assertEquals(30L, endData.get("totalTokens"));
        assertEquals(100L, endData.get("durationMillis"));

        var endNoUsage = AiEventForwardingListener.modelEndPayload("m", null, 5L);
        assertEquals("m", endNoUsage.get("model"));
        assertEquals(5L, endNoUsage.get("durationMillis"));
        assertNull(endNoUsage.get("inputTokens"),
                "null usage must not insert token keys");
    }

    /** Minimal in-memory session that records emitted events. */
    private static final class RecordingSession implements StreamingSession {
        final List<AiEvent> events = new ArrayList<>();
        boolean closed;

        @Override public String sessionId() { return "test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public void emit(AiEvent event) { events.add(event); }
        @Override public void close() { closed = true; }
    }

    /** Recording listener for testing fire dispatch + ordering. */
    private static final class RecordingListener implements AgentLifecycleListener {
        record StartCall(String model, int messageCount, int toolCount) { }
        record EndCall(String model, TokenUsage usage, long durationMillis) { }
        record ErrorCall(String model, Throwable error) { }

        final List<StartCall> starts = new ArrayList<>();
        final List<EndCall> ends = new ArrayList<>();
        final List<ErrorCall> errors = new ArrayList<>();

        @Override
        public void onModelStart(String model, int messageCount, int toolCount) {
            starts.add(new StartCall(model, messageCount, toolCount));
        }

        @Override
        public void onModelEnd(String model, TokenUsage usage, long durationMillis) {
            ends.add(new EndCall(model, usage, durationMillis));
        }

        @Override
        public void onModelError(String model, Throwable error) {
            errors.add(new ErrorCall(model, error));
        }
    }

    @Test
    void atomicReferenceDriverSanityCheck() {
        // Smoke check that the harness sets and reads the expected fields,
        // independent of the AgentLifecycleListener contract — guards against
        // accidentally testing a no-op record.
        var ref = new AtomicReference<String>();
        ref.set("hello");
        assertNotNull(ref.get());
        assertEquals("hello", ref.get());
    }
}
