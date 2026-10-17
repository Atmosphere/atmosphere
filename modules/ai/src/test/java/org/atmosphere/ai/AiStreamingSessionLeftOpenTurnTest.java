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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @AfterEach
    void cleanUp() {
        AiStreamingSession.removeAllForResource(UUID);
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
    void theSweepEndsALeftOpenTurnThatKeepsWritingPastTheTtlAfterItsReturn() throws Exception {
        var client = new CollectingSession();
        var buffer = new RunEventReplayBuffer();
        var session = session(new RunEventCapturingSession(client, buffer));
        session.promptReturnedOpen();
        var ttlMs = 200L;
        var until = System.currentTimeMillis() + ttlMs + 100L;
        while (System.currentTimeMillis() < until) {
            session.send("tick");
            Thread.sleep(10);
        }
        session.send("tick");

        AiStreamingSession.sweepExpired(ttlMs);

        assertTrue(client.isClosed(), "a turn writing on past its lifetime is ended");
        var end = buffer.terminal().orElseThrow();
        assertEquals("cancelled", end.type());
        assertEquals("timeout", end.payload(), "it ends cancelled for its timeout");
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
