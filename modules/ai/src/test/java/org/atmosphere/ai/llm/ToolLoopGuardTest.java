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
import org.atmosphere.ai.AgentLifecycleListener;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopGuardTest {

    @Test
    void underCapDoesNotTrip() {
        var session = new RecordingSession();
        var guard = new ToolLoopGuard("built-in", ToolLoopPolicy.strict(3), session);

        guard.onModelStart("gpt-4o", 1, 0);
        guard.onModelStart("gpt-4o", 1, 0);
        guard.onModelStart("gpt-4o", 1, 0);

        assertFalse(guard.isTripped(), "guard must not trip at exactly the cap");
        assertEquals(3, guard.currentCount());
        assertNull(session.error.get(), "session.error must not have been called under cap");
    }

    @Test
    void overCapWithFailFiresSessionError() {
        var session = new RecordingSession();
        var guard = new ToolLoopGuard("built-in", ToolLoopPolicy.strict(3), session);

        guard.onModelStart("gpt-4o", 1, 0);
        guard.onModelStart("gpt-4o", 1, 0);
        guard.onModelStart("gpt-4o", 1, 0);
        // 4th call exceeds cap
        guard.onModelStart("gpt-4o", 1, 0);

        assertTrue(guard.isTripped());
        assertEquals(4, guard.currentCount());
        var err = session.error.get();
        assertNotSame(null, err);
        var exhausted = assertInstanceOf(ToolLoopPolicy.ToolLoopExhaustedException.class, err);
        assertEquals(3, exhausted.maxIterations());
    }

    @Test
    void tripIsIdempotent() {
        var session = new RecordingSession();
        var guard = new ToolLoopGuard("built-in", ToolLoopPolicy.strict(2), session);

        // 4 over-cap fires must produce exactly one session.error invocation.
        for (int i = 0; i < 6; i++) {
            guard.onModelStart("gpt-4o", 1, 0);
        }

        assertTrue(guard.isTripped());
        assertEquals(1, session.errorCallCount.get(),
                "session.error must be invoked exactly once even on repeated over-cap fires");
    }

    @Test
    void completeWithoutToolsModeDoesNotFireSessionError() {
        var session = new RecordingSession();
        var policy = new ToolLoopPolicy(2, ToolLoopPolicy.OnMaxIterations.COMPLETE_WITHOUT_TOOLS);
        var guard = new ToolLoopGuard("built-in", policy, session);

        guard.onModelStart("gpt-4o", 1, 0);
        guard.onModelStart("gpt-4o", 1, 0);
        guard.onModelStart("gpt-4o", 1, 0);
        guard.onModelStart("gpt-4o", 1, 0);

        assertTrue(guard.isTripped(), "guard records the trip for diagnostics");
        assertNull(session.error.get(),
                "COMPLETE_WITHOUT_TOOLS overflow must not call session.error — runtime falls through to native default");
    }

    @Test
    void installIfPresentReturnsSameContextWhenNoPolicy() {
        var ctx = baseContext(Map.of());
        var session = new RecordingSession();

        var result = ToolLoopGuard.installIfPresent("built-in", ctx, session);

        assertSame(ctx, result, "no policy attached → context must pass through unchanged");
    }

    @Test
    void installIfPresentAppendsGuardWhenPolicyAttached() {
        var ctx = baseContext(Map.of(
                ToolLoopPolicies.METADATA_KEY, ToolLoopPolicy.strict(3)));
        var session = new RecordingSession();

        var result = ToolLoopGuard.installIfPresent("built-in", ctx, session);

        assertNotSame(ctx, result);
        assertEquals(ctx.listeners().size() + 1, result.listeners().size());
        assertInstanceOf(ToolLoopGuard.class,
                result.listeners().get(result.listeners().size() - 1),
                "guard must be appended at the tail so existing listeners run first");
    }

    @Test
    void installIfPresentPreservesExistingListenersInOrder() {
        var existing = new ArrayList<AgentLifecycleListener>();
        var listenerA = new AgentLifecycleListener() { };
        var listenerB = new AgentLifecycleListener() { };
        existing.add(listenerA);
        existing.add(listenerB);
        var ctx = baseContext(Map.of(
                        ToolLoopPolicies.METADATA_KEY, ToolLoopPolicy.strict(3)))
                .withListeners(List.copyOf(existing));
        var session = new RecordingSession();

        var result = ToolLoopGuard.installIfPresent("built-in", ctx, session);

        assertEquals(3, result.listeners().size());
        assertSame(listenerA, result.listeners().get(0));
        assertSame(listenerB, result.listeners().get(1));
        assertInstanceOf(ToolLoopGuard.class, result.listeners().get(2));
    }

    @Test
    void installIfPresentRejectsTypeMismatchedPolicySlot() {
        var ctx = baseContext(Map.of(ToolLoopPolicies.METADATA_KEY, "not-a-policy"));
        var session = new RecordingSession();

        assertThrows(IllegalArgumentException.class,
                () -> ToolLoopGuard.installIfPresent("built-in", ctx, session));
    }

    @Test
    void constructorRejectsNullArguments() {
        var session = new RecordingSession();

        assertThrows(NullPointerException.class,
                () -> new ToolLoopGuard(null, ToolLoopPolicy.strict(3), session));
        assertThrows(NullPointerException.class,
                () -> new ToolLoopGuard("built-in", null, session));
        assertThrows(NullPointerException.class,
                () -> new ToolLoopGuard("built-in", ToolLoopPolicy.strict(3), null));
    }

    private static AgentExecutionContext baseContext(Map<String, Object> metadata) {
        return new AgentExecutionContext(
                "msg", null, null, null, "sid", null, null,
                List.of(), null, null, List.of(),
                new HashMap<>(metadata), List.of(), null, null,
                List.of(), List.of(), null, null);
    }

    private static final class RecordingSession implements StreamingSession {
        final java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger errorCallCount = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean(false);

        @Override public String sessionId() { return "recording"; }
        @Override public void send(String text) { /* unused */ }
        @Override public void sendMetadata(String key, Object value) { /* unused */ }
        @Override public void progress(String message) { /* unused */ }
        @Override public void complete() { closed.set(true); }
        @Override public void complete(String summary) { closed.set(true); }
        @Override public void error(Throwable t) {
            errorCallCount.incrementAndGet();
            error.compareAndSet(null, t);
            closed.set(true);
        }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public boolean hasErrored() { return error.get() != null; }
        @Override public void close() { closed.set(true); }
    }
}
