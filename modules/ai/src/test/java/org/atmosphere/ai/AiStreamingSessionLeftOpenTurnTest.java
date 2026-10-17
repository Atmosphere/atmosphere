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

import org.atmosphere.ai.resume.RunEventCapturingSession;
import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A turn whose {@code @Prompt} method returned with its session still open is
 * ended by the session's own terminal frame, or cut short: the end is decided
 * once — metrics included — whatever cancel came first, and the idle sweep
 * bounds how long such a turn may run on after the return, not only how long
 * it may stay silent.
 */
class AiStreamingSessionLeftOpenTurnTest {

    private static final String UUID = "uuid-left-open-turn";
    private static final String OTHER_UUID = "uuid-left-open-turn-reattached";
    /** A TTL far above the age of anything a test does not age itself. */
    private static final long TTL_MS = 60_000L;

    /** The executions of the runs a test registered, completed after it. */
    private final List<ExecutionHandle.Settable> runs = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        AiStreamingSession.removeAllForResource(UUID);
        AiStreamingSession.removeAllForResource(OTHER_UUID);
        runs.forEach(ExecutionHandle.Settable::complete);
        RunRegistryHolder.reset();
    }

    /**
     * The no-argument cancel (the caller's own, which only cuts a model call
     * short) came first, then the prompter went for good while the method
     * still ran: the method returning open ends the turn as cancelled for the
     * disconnect. Keeping the caller's reason left it open.
     */
    @Test
    void aDisconnectAfterACallerCancelEndsTheTurnWhenThePromptReturnsOpen() {
        var client = new CollectingSession();
        var session = session(client);

        session.cancelInflight();
        session.cancelInflight(RunCancelledException.Reason.DISCONNECT);
        session.promptReturnedOpen();

        assertTrue(client.isClosed(), "the turn ends when the method returns");
        assertTrue(client.failure() instanceof RunCancelledException cancelled
                        && cancelled.reason() == RunCancelledException.Reason.DISCONNECT,
                "it ends cancelled for the disconnect: " + client.failure());
    }

    /**
     * A turn left open and cancelled, whose writer then completes it: the
     * metrics session ends once, not once per terminal call.
     */
    @Test
    void aLeftOpenTurnCancelledThenCompletedEndsItsMetricsSessionOnce() {
        var ended = new AtomicInteger();
        var metrics = new AiMetrics() {
            @Override
            public void recordStreamingTextUsage(String model, int promptTokens, int completionTokens) {
            }

            @Override
            public void recordLatency(String model, java.time.Duration ttft, java.time.Duration total) {
            }

            @Override
            public void recordCost(String model, java.math.BigDecimal cost) {
            }

            @Override
            public void recordToolCall(String model, String toolName, java.time.Duration duration,
                                       boolean success) {
            }

            @Override
            public void recordError(String model, String errorType) {
            }

            @Override
            public void sessionEnded(String model) {
                ended.incrementAndGet();
            }
        };
        var client = new CollectingSession();
        var session = session(new TracingCapturingSession(client, metrics, "m"));
        session.send("partial");
        session.promptReturnedOpen();

        session.cancelInflight(RunCancelledException.Reason.OPERATOR);
        session.complete();

        assertEquals(1, ended.get(), "the metrics session ends once");
        assertTrue(client.failure() instanceof RunCancelledException, "the turn ended cancelled");
    }

    /**
     * A turn left open whose writer never ends it but keeps writing, so it is
     * never idle: the sweep ends it once it has run on past the session TTL
     * after its {@code @Prompt} method returned, with the cancelled frame of a
     * timeout.
     */
    @Test
    void theSweepEndsALeftOpenTurnThatKeepsWritingPastTheTtlAfterItsReturn() {
        var client = new CollectingSession();
        var buffer = new RunEventReplayBuffer();
        var session = session(new RunEventCapturingSession(client, buffer));
        session.promptReturnedOpen();
        var ttlMs = 60_000L;
        // Returned longer ago than the TTL, and it wrote just now: never idle.
        session.promptReturnedAtMillis = System.currentTimeMillis() - (ttlMs + 5_000L);
        session.send("tick");

        AiStreamingSession.sweepExpired(ttlMs);

        assertTrue(client.isClosed(), "a turn writing on past its lifetime is ended");
        var end = buffer.terminal().orElseThrow();
        assertEquals("cancelled", end.type());
        assertEquals("timeout", end.payload(), "it ends cancelled for its timeout");
    }

    /**
     * A turn left open whose connection was lost, its run detached for the
     * grace, and that no client reattached to: the lost connection took its
     * session out of the active sessions, so only its run reaches it. The
     * idle sweep still ends it, with the cancelled frame of the sweep.
     */
    @Test
    void theSweepReachesALeftOpenTurnWhoseConnectionWasLostThroughItsRunOnly() {
        var client = new CollectingSession();
        var buffer = new RunEventReplayBuffer();
        var session = leftOpenRun(client, buffer);
        var runId = session.runId().orElseThrow();
        assertEquals(List.of(runId), AiStreamingSession.removeAllForResource(UUID, Duration.ofSeconds(30)),
                "the lost connection detaches the run for the grace");
        assertFalse(AiStreamingSession.resourceHasActiveSessions(UUID), "no active session is left for it");
        session.lastActivityMillis = System.currentTimeMillis() - (TTL_MS + 5_000L);

        AiStreamingSession.sweepExpired(TTL_MS);

        assertTrue(client.isClosed(), "the idle sweep ends the turn");
        var end = buffer.terminal().orElseThrow();
        assertEquals("cancelled", end.type());
        assertEquals("sweep", end.payload());
    }

    /**
     * The same turn, never idle but still open past the session TTL after its
     * {@code @Prompt} returned, is ended through its run with the cancelled
     * frame of a timeout.
     */
    @Test
    void theSweepEndsALeftOpenTurnPastItsLifetimeThroughItsRunAfterALostConnection() {
        var client = new CollectingSession();
        var buffer = new RunEventReplayBuffer();
        var session = leftOpenRun(client, buffer);
        AiStreamingSession.removeAllForResource(UUID, Duration.ofSeconds(30));
        session.promptReturnedAtMillis = System.currentTimeMillis() - (TTL_MS + 5_000L);
        session.send("tick");

        AiStreamingSession.sweepExpired(TTL_MS);

        assertTrue(client.isClosed(), "a turn writing on past its lifetime is ended");
        var end = buffer.terminal().orElseThrow();
        assertEquals("cancelled", end.type());
        assertEquals("timeout", end.payload());
    }

    /**
     * A turn its run's client reattached to on another connection is
     * registered under that connection; when the turn ends on its own, that
     * registration goes with it.
     */
    @Test
    void aTurnEndingDropsTheRegistrationOfTheConnectionItsClientReattachedOn() {
        var session = leftOpenRun(new CollectingSession(), new RunEventReplayBuffer());
        AiStreamingSession.reattached(session.runId().orElseThrow(), OTHER_UUID);
        assertTrue(AiStreamingSession.resourceHasActiveSessions(OTHER_UUID), "registered under the reattach");

        session.complete();

        assertFalse(AiStreamingSession.resourceHasActiveSessions(OTHER_UUID),
                "the ended turn is registered under the connection it was reattached on no more");
        assertFalse(AiStreamingSession.resourceHasActiveSessions(UUID));
    }

    /**
     * A session whose run left the live runs while the session stays open —
     * the run of a {@code @Prompt} returning open past the cap ends at the
     * return, its session left to its own terminal frame — is reached through
     * its run no more after the next sweep, so the run map holds the sessions
     * of live runs only.
     */
    @Test
    void theSweepForgetsTheSessionOfARunNoLongerLive() {
        var run = new ExecutionHandle.Settable(null);
        runs.add(run);
        var handle = RunRegistryHolder.get().register("/left-open", "alice", UUID, run,
                new RunEventReplayBuffer(), null);
        var session = session(new CollectingSession());
        session.setRunId(handle.runId());
        assertTrue(AiStreamingSession.reachableThroughRun(handle.runId()));
        run.complete();

        AiStreamingSession.sweepExpired(TTL_MS);

        assertFalse(session.isClosed(), "the session is not idle: the sweep leaves it open");
        assertFalse(AiStreamingSession.reachableThroughRun(handle.runId()),
                "the session of a run no longer live is dropped from the run map");
    }

    /**
     * A session of a run registered in the run registry, whose run ends when
     * the session sent its terminal frame, as the {@code @AiEndpoint} handler
     * ends it, and whose {@code @Prompt} method returned with it open.
     */
    private AiStreamingSession leftOpenRun(CollectingSession client, RunEventReplayBuffer buffer) {
        var run = new ExecutionHandle.Settable(null);
        runs.add(run);
        var handle = RunRegistryHolder.get().register("/left-open", "alice", UUID, run, buffer, null);
        buffer.whenTerminated().thenRun(run::complete);
        var session = session(new RunEventCapturingSession(client, buffer));
        session.setRunId(handle.runId());
        session.promptReturnedOpen();
        return session;
    }

    private static AiStreamingSession session(StreamingSession delegate) {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn(UUID);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        var session = new AiStreamingSession(delegate, mock(AgentRuntime.class), "", null, List.of(), resource);
        AiStreamingSession.registerActive(session);
        return session;
    }
}
