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

    /**
     * An approval nobody gave before the turn's limit ends the turn as expected:
     * the 2026-10-20 re-test (spring-boot-ai-tools) found it logged as
     * {@code ERROR Streaming session ... error} with an AiException stack.
     */
    @Test
    public void anApprovalThatTimedOutSendsTheSameErrorFrameWithAWarningNamingItAndNoStack() {
        var message = "Approval for tool 'reset_city_data' was not given before the turn's time limit; the turn ended.";

        var expired = captureTerminal(new ApprovalTimedOutException(message, "apr_6a9b86df51ed", "reset_city_data"));
        var failed = captureTerminal(new AiException(message));

        assertEquals(1, expired.frames().size(), "one terminal frame: " + expired.frames());
        var expiredFrame = org.atmosphere.ai.filter.AiStreamMessage.parse(expired.frames().get(0));
        var failedFrame = org.atmosphere.ai.filter.AiStreamMessage.parse(failed.frames().get(0));
        assertTrue(expiredFrame.isError(), expired.frames().get(0));
        assertEquals(failedFrame.type(), expiredFrame.type());
        assertEquals(message, expiredFrame.data());

        for (var event : expired.events()) {
            assertNotEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel(),
                    "a timed-out approval was logged as ERROR: " + event.getFormattedMessage());
            assertNull(event.getThrowableProxy(),
                    "a timed-out approval was logged with a stack trace: " + event.getFormattedMessage());
        }
        assertTrue(expired.events().stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("apr_6a9b86df51ed")
                        && e.getFormattedMessage().contains("reset_city_data")),
                "one warning naming the approval and its tool: " + expired.events());
        // Any other AiException is still an ERROR with its stack.
        assertTrue(failed.events().stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                && e.getThrowableProxy() != null), "a plain AiException must stay an ERROR with stack");
    }

    /**
     * A rate limit (a gateway refusal or a provider's 429) is an expected
     * refusal: the client gets the error frame, the log one warning and no stack.
     */
    @Test
    public void aRateLimitSendsTheErrorFrameWithOneWarningAndNoStack() {
        var message = "Rate limit exceeded for user alice on provider built-in";

        var limited = captureTerminal(new AiProviderException.RateLimited(message, null));

        assertEquals(1, limited.frames().size(), "one terminal frame: " + limited.frames());
        var frame = org.atmosphere.ai.filter.AiStreamMessage.parse(limited.frames().get(0));
        assertTrue(frame.isError(), limited.frames().get(0));
        assertEquals(message, frame.data());
        for (var event : limited.events()) {
            assertNotEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel(),
                    "a rate limit was logged as ERROR: " + event.getFormattedMessage());
            assertNull(event.getThrowableProxy(),
                    "a rate limit was logged with a stack trace: " + event.getFormattedMessage());
        }
        assertTrue(limited.events().stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains(message)),
                "one warning naming the refusal: " + limited.events());
    }

    /**
     * A turn that outlived its time limit is ended by the {@code @Prompt}
     * watchdog, which already logged the timeout: the client gets the error
     * frame, the log one warning and no stack, not an ERROR with the stack of
     * an exception the watchdog just created.
     */
    @Test
    public void aTimeoutSendsTheErrorFrameWithOneWarningAndNoStack() {
        var message = "Prompt processing timed out after 300ms";

        var timedOut = captureTerminal(new java.util.concurrent.TimeoutException(message));

        assertEquals(1, timedOut.frames().size(), "one terminal frame: " + timedOut.frames());
        var frame = org.atmosphere.ai.filter.AiStreamMessage.parse(timedOut.frames().get(0));
        assertTrue(frame.isError(), timedOut.frames().get(0));
        assertEquals(message, frame.data());
        for (var event : timedOut.events()) {
            assertNotEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel(),
                    "a timeout was logged as ERROR: " + event.getFormattedMessage());
            assertNull(event.getThrowableProxy(),
                    "a timeout was logged with a stack trace: " + event.getFormattedMessage());
        }
        assertEquals(1, timedOut.events().stream()
                        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                                && e.getFormattedMessage().contains(message)).count(),
                "one warning naming the timeout: " + timedOut.events());
    }

    /**
     * A turn that timed out after its page went away has no frame to be
     * written: the watchdog's warning already named the timeout, so the
     * session adds no second warning.
     */
    @Test
    public void aTimeoutAfterTheConnectionWentAwayAddsNoWarning() {
        var sessionId = "gone-timeout-" + System.nanoTime();
        var session = createSession(sessionId);
        DefaultStreamingSession.cleanupResource("uuid-" + sessionId);
        assertTrue(session.isClosed(), "the disconnect closed the session");

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DefaultStreamingSession.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
        try {
            session.error(new java.util.concurrent.TimeoutException("Prompt processing timed out after 300ms"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
        var events = List.copyOf(appender.list);
        assertTrue(events.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)),
                "no warning for a timeout the watchdog already logged: " + events);
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                        && e.getFormattedMessage().contains(sessionId)),
                "the timeout after the end is still logged at DEBUG: " + events);
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

    /**
     * A run that fails after its connection went away (a page reloaded: the
     * disconnect closed the session) still has its failure logged, though no
     * error frame can be written: one WARN line naming it, its stack at DEBUG.
     */
    @Test
    public void aRunFailureAfterTheConnectionWentAwayIsLogged() {
        var sessionId = "gone-" + System.nanoTime();
        var session = createSession(sessionId);
        DefaultStreamingSession.cleanupResource("uuid-" + sessionId);
        assertTrue(session.isClosed(), "the disconnect closed the session");

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DefaultStreamingSession.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
        try {
            session.error(new IllegalStateException("Request object no longer valid"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
        var events = List.copyOf(appender.list);
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains(sessionId)
                        && e.getFormattedMessage().contains("Request object no longer valid")),
                "the failure must be logged at WARN with the session and its message: " + events);
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                        && e.getThrowableProxy() != null),
                "its stack must be logged at DEBUG: " + events);
    }

    /**
     * A run goes on after its page reloaded: what it writes after the
     * connection went away is kept by the run's replay for the page that
     * reattaches, so the dropped write is no warning. A write after the
     * session's own terminal frame still is one.
     */
    @Test
    public void aWriteAfterTheConnectionWentAwayIsNoWarningButOneAfterTheEndIs() {
        var departed = "departed-" + System.nanoTime();
        var gone = createSession(departed);
        DefaultStreamingSession.cleanupResource("uuid-" + departed);
        var ended = createSession("ended-" + System.nanoTime());
        ended.complete();

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DefaultStreamingSession.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
        List<ch.qos.logback.classic.spi.ILoggingEvent> afterDeparture;
        try {
            gone.send("late text");
            gone.emit(new AiEvent.Progress("late progress", null));
            afterDeparture = List.copyOf(appender.list);
            appender.list.clear();
            ended.send("text after the end");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
        assertTrue(afterDeparture.stream().noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN),
                "a write after the connection went away is no warning: " + afterDeparture);
        assertTrue(afterDeparture.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                        && e.getFormattedMessage().contains(departed)),
                "it is still logged, at DEBUG: " + afterDeparture);
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN),
                "a write after the session's own end is a warning: " + appender.list);
    }

    /** An error that comes after the session already completed did not end it, so the session has not errored. */
    @Test
    public void anErrorAfterTheSessionCompletedDoesNotMarkItErrored() {
        var session = createSession("late-error-" + System.nanoTime());

        session.complete();
        session.error(new RunCancelledException(RunCancelledException.Reason.DISCONNECT));

        assertFalse(session.hasErrored(), "the session ended with complete; the late error lost the close");
    }

    @Test
    public void testResourceForSessionReturnsEmptyForUnknown() {
        assertTrue(DefaultStreamingSession.resourceForSession("nonexistent").isEmpty());
    }
}
