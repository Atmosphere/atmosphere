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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoutingAiSupport#executeWithHandle}: the caller holds the selected
 * backend's own handle, a backend that throws fails over, and the session the
 * backend writes to forwards what the turn's watchdog and the tools need.
 */
class RoutingAiSupportHandleTest {

    /** A backend whose handle settles only when cancelled. */
    static final class HandleBackend implements AgentRuntime {
        final String name;
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<StreamingSession> session = new AtomicReference<>();
        volatile boolean fail;

        HandleBackend(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new AssertionError("the handle path must not fall back to execute()");
        }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
            calls.incrementAndGet();
            this.session.set(session);
            if (fail) {
                throw new IllegalStateException(name + " is down");
            }
            return new ExecutionHandle.Settable(cancels::incrementAndGet);
        }
    }

    static final class RecordingSession implements StreamingSession {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<AiEvent> events = new CopyOnWriteArrayList<>();
        final Object marker = new Object();

        @Override public String sessionId() { return "s"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { errors.add(t); }
        @Override public boolean isClosed() { return !errors.isEmpty(); }
        @Override public boolean hasErrored() { return !errors.isEmpty(); }
        @Override public void emit(AiEvent event) { events.add(event); }
        @Override public Map<Class<?>, Object> injectables() { return Map.of(Object.class, marker); }
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext("Hello", "", null, null, null, null, null, List.of(), null, null,
                List.of(), Map.of(), List.of(), null, null);
    }

    @Test
    void aCancelReachesTheSelectedBackendsHandle() {
        var primary = new HandleBackend("primary");
        var routing = new RoutingAiSupport(new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER),
                List.of(primary));

        var handle = routing.executeWithHandle(context(), new RecordingSession());
        assertFalse(handle.isDone(), "the backend's call is in flight");
        handle.cancel();

        assertEquals(1, primary.cancels.get());
        assertTrue(handle.isDone());
    }

    @Test
    void aBackendThatThrowsFailsOver() {
        var primary = new HandleBackend("primary");
        primary.fail = true;
        var secondary = new HandleBackend("secondary");
        var router = new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER, 1, Duration.ofMinutes(1));
        var routing = new RoutingAiSupport(router, List.of(primary, secondary));
        var session = new RecordingSession();

        var handle = routing.executeWithHandle(context(), session);
        handle.cancel();

        assertEquals(1, primary.calls.get());
        assertEquals(1, secondary.calls.get());
        assertEquals(1, secondary.cancels.get(), "the caller holds the fallback's handle");
        assertEquals(List.of(), session.errors, "a failover that started writes no error");
    }

    @Test
    void theRoutedSessionForwardsStateEventsAndInjectables() {
        var primary = new HandleBackend("primary");
        var routing = new RoutingAiSupport(new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER),
                List.of(primary));
        var session = new RecordingSession();
        routing.executeWithHandle(context(), session);
        var routed = primary.session.get();

        assertSame(session.marker, routed.injectables().get(Object.class),
                "a watchdog or tool injectable the turn carries reaches the backend");
        var progress = new AiEvent.Progress("thinking", null);
        routed.emit(progress);
        assertEquals(List.of(progress), session.events, "emit reaches the routed session");
        assertFalse(routed.hasErrored());
        routed.error(new IllegalStateException("boom"));
        assertTrue(routed.hasErrored(), "hasErrored reads the routed session's state");
    }
}
