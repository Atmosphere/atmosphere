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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DefaultStreamingSessionTest {

    private DefaultStreamingSession createSession(String sessionId) {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.uuid()).thenReturn("uuid-" + sessionId);
        return new DefaultStreamingSession(sessionId, resource);
    }

    @Test
    public void testSessionRegistersResourceOnCreate() {
        var session = createSession("reg-test");

        var result = DefaultStreamingSession.resourceForSession("reg-test");
        assertTrue(result.isPresent());

        // Cleanup
        session.complete();
    }

    @Test
    public void testSessionDeregistersOnComplete() {
        var session = createSession("dereg-complete");
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-complete").isPresent());

        session.complete();
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-complete").isEmpty());
    }

    @Test
    public void testSessionDeregistersOnCompleteWithSummary() {
        var session = createSession("dereg-summary");
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-summary").isPresent());

        session.complete("done");
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-summary").isEmpty());
    }

    @Test
    public void testSessionDeregistersOnError() {
        var session = createSession("dereg-error");
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-error").isPresent());

        session.error(new RuntimeException("test error"));
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-error").isEmpty());
    }

    @Test
    public void testDeliveryIsKnownWhileTheTerminalFrameIsBroadcast() {
        // A broadcast filter that defers a frame past the terminal one reads the
        // session's delivery while the terminal frame runs through it (filters
        // run inside broadcast); the session deregisters only afterwards.
        for (var terminal : List.<Consumer<DefaultStreamingSession>>of(
                StreamingSession::complete,
                s -> s.complete("summary"),
                s -> s.error(new RuntimeException("boom")),
                s -> s.emit(new AiEvent.Complete(null, Map.of())))) {
            var resource = mock(AtmosphereResource.class);
            var broadcaster = mock(Broadcaster.class);
            when(resource.getBroadcaster()).thenReturn(broadcaster);
            var sessionId = "terminal-delivery-" + System.nanoTime();
            var seen = new ArrayList<Optional<DefaultStreamingSession.Delivery>>();
            when(broadcaster.broadcast(any(), anySet())).thenAnswer(inv -> {
                seen.add(DefaultStreamingSession.deliveryForSession(sessionId));
                return null;
            });
            var session = new DefaultStreamingSession(sessionId, resource);

            terminal.accept(session);

            assertEquals(1, seen.size());
            assertTrue(seen.get(0).isPresent(), "the delivery must be known during the terminal broadcast");
            assertSame(resource, seen.get(0).get().resource());
            assertFalse(seen.get(0).get().toRoom());
            assertTrue(DefaultStreamingSession.deliveryForSession(sessionId).isEmpty(),
                    "the session must deregister once its terminal frame went out");
        }
    }

    @Test
    public void aCancelledRunEndsWithOneCancelledFrameNamingTheReason() {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        var frames = new ArrayList<String>();
        when(broadcaster.broadcast(any(), anySet())).thenAnswer(inv -> {
            frames.add(String.valueOf(((org.atmosphere.cpr.RawMessage) inv.getArgument(0)).message()));
            return null;
        });
        var session = new DefaultStreamingSession("cancelled-frame-" + System.nanoTime(), resource);

        session.error(new RunCancelledException(RunCancelledException.Reason.OPERATOR));
        session.complete();

        assertEquals(1, frames.size(), "one terminal frame, nothing after it: " + frames);
        var frame = org.atmosphere.ai.filter.AiStreamMessage.parse(frames.get(0));
        assertTrue(frame.isCancelled(), frames.get(0));
        assertEquals("operator", frame.data());
    }

    /**
     * A policy denial is an expected outcome: the client gets the same error frame as
     * before, but the server log gets one line, not an ERROR with a stack trace. The
     * 2026-10-16 sweep found every expected DENY of spring-boot-ms-governance-chat
     * logged as {@code ERROR Streaming session ... error} plus a SecurityException stack.
     */
    @Test
    public void aDeniedTurnSendsTheSameErrorFrameWithoutAnErrorLogOrStack() {
        var reason = "Denied by policy 'block-destructive': matched DROP TABLE";

        var denied = captureTerminal(new RequestDeniedException(reason));
        var failed = captureTerminal(new SecurityException(reason));

        assertEquals(1, denied.frames().size(), "one terminal frame: " + denied.frames());
        var deniedFrame = org.atmosphere.ai.filter.AiStreamMessage.parse(denied.frames().get(0));
        var failedFrame = org.atmosphere.ai.filter.AiStreamMessage.parse(failed.frames().get(0));
        assertTrue(deniedFrame.isError(), denied.frames().get(0));
        assertEquals(reason, deniedFrame.data());
        assertEquals(failedFrame.type(), deniedFrame.type(), "a denial must render like any error frame");
        assertEquals(failedFrame.data(), deniedFrame.data(), "a denial must render like any error frame");

        for (var event : denied.events()) {
            assertNotEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel(),
                    "a policy denial was logged as ERROR: " + event.getFormattedMessage());
            assertNull(event.getThrowableProxy(),
                    "a policy denial was logged with a stack trace: " + event.getFormattedMessage());
        }
        assertTrue(denied.events().stream().anyMatch(e -> e.getFormattedMessage().contains(reason)),
                "the denial should still be logged with its reason: " + denied.events());
        // A real failure is still reported as an error with its stack.
        assertTrue(failed.events().stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                && e.getThrowableProxy() != null), "a plain SecurityException must stay an ERROR with stack");
    }

    private record Terminal(List<String> frames, List<ch.qos.logback.classic.spi.ILoggingEvent> events) {
    }

    private static Terminal captureTerminal(Throwable failure) {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        var frames = new ArrayList<String>();
        when(broadcaster.broadcast(any(), anySet())).thenAnswer(inv -> {
            frames.add(String.valueOf(((org.atmosphere.cpr.RawMessage) inv.getArgument(0)).message()));
            return null;
        });
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DefaultStreamingSession.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
        try {
            var session = new DefaultStreamingSession("denied-" + System.nanoTime(), resource);
            session.error(failure);
            assertTrue(session.hasErrored(), "a denied turn still ends the session in error");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
        return new Terminal(frames, List.copyOf(appender.list));
    }

    @Test
    public void testResourceForSessionReturnsEmptyForUnknown() {
        assertTrue(DefaultStreamingSession.resourceForSession("nonexistent").isEmpty());
    }
}
