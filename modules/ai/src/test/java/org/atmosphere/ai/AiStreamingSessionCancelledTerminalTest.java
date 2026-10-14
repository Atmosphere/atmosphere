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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A model call that the framework cancels — the operator's admin
 * {@code cancel-inflight}, the idle-session sweep, the client's disconnect —
 * is cut short, and the runtime completes normally on the cancel. That
 * {@code complete} presented the cut answer as finished to the client and to
 * the run's replay buffer. The turn must end in an {@code error} that says the
 * run was cancelled, whichever path cancelled it.
 */
class AiStreamingSessionCancelledTerminalTest {

    private static final String UUID = "uuid-cancelled-terminal";

    @AfterEach
    void cleanUp() {
        AiStreamingSession.removeAllForResource(UUID);
    }

    @Test
    void anOperatorCancelEndsTheTurnInACancelledError() throws Exception {
        var turn = startTurn(new CompletingOnCancelRuntime());
        AiStreamingSession.cancelInflightForResource(UUID);
        turn.assertEndedInError(AiStreamingSession.CANCELLED_BY_OPERATOR);
    }

    @Test
    void theIdleSweepEndsTheTurnInACancelledError() throws Exception {
        var turn = startTurn(new CompletingOnCancelRuntime());
        turn.session.lastActivityMillis = 0L;
        assertEquals(1, AiStreamingSession.sweepExpired(1_000L));
        turn.assertEndedInError(AiStreamingSession.CANCELLED_BY_SWEEP);
    }

    @Test
    void aDisconnectEndsTheTurnInACancelledError() throws Exception {
        var turn = startTurn(new CompletingOnCancelRuntime());
        AiStreamingSession.removeAllForResource(UUID);
        turn.assertEndedInError(AiStreamingSession.CANCELLED_BY_DISCONNECT);
    }

    @Test
    void aRuntimeThatEndsACancelledCallWithoutATerminalFrameStillEndsInError() throws Exception {
        var runtime = new CompletingOnCancelRuntime();
        runtime.completeOnCancel = false;
        var turn = startTurn(runtime);
        AiStreamingSession.cancelInflightForResource(UUID);
        turn.assertEndedInError(AiStreamingSession.CANCELLED_BY_OPERATOR);
    }

    @Test
    void aCancelThatArrivesBeforeTheCallPublishesItsHandleStillEndsInError() throws Exception {
        var runtime = new CompletingOnCancelRuntime();
        runtime.holdBeforeReturning = new java.util.concurrent.CountDownLatch(1);
        var turn = startTurn(runtime, false);
        // The runtime is still inside executeWithHandle: no handle is published yet.
        assertEquals(null, turn.session.currentHandle());
        AiStreamingSession.cancelInflightForResource(UUID);
        runtime.holdBeforeReturning.countDown();
        turn.assertEndedInError(AiStreamingSession.CANCELLED_BY_OPERATOR);
    }

    private Turn startTurn(CompletingOnCancelRuntime runtime) throws Exception {
        return startTurn(runtime, true);
    }

    private Turn startTurn(CompletingOnCancelRuntime runtime, boolean awaitHandle) throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn(UUID);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        var client = new CollectingSession();
        var buffer = new RunEventReplayBuffer();
        var session = new AiStreamingSession(new RunEventCapturingSession(client, buffer), runtime,
                "", null, List.of(), resource);
        AiStreamingSession.registerActive(session);
        var streamed = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                session.stream("explain");
                streamed.complete(null);
            } catch (Throwable t) {
                streamed.completeExceptionally(t);
            }
        });
        runtime.inFlight.get(10, TimeUnit.SECONDS);
        // stream() publishes the handle right after executeWithHandle returns:
        // the cancel below must find the call in flight.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (awaitHandle && session.currentHandle() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return new Turn(session, client, buffer, streamed);
    }

    private record Turn(AiStreamingSession session, CollectingSession client,
                        RunEventReplayBuffer buffer, CompletableFuture<Void> streamed) {

        void assertEndedInError(String reason) throws Exception {
            streamed.get(10, TimeUnit.SECONDS);
            assertTrue(client.failed(), "the client gets an error frame, not complete: " + client.text());
            assertEquals(reason, client.failure().getMessage());
            var events = buffer.snapshot();
            assertEquals("partial", events.get(0).payload());
            assertEquals("error", events.get(events.size() - 1).type(), events.toString());
            assertEquals(reason, events.get(events.size() - 1).payload());
        }
    }

    /** Streams one token, holds the call open, and completes the session when cancelled, as runtime bridges do. */
    private static final class CompletingOnCancelRuntime implements AgentRuntime {
        final CompletableFuture<Void> inFlight = new CompletableFuture<>();
        volatile boolean completeOnCancel = true;
        volatile java.util.concurrent.CountDownLatch holdBeforeReturning;

        @Override public String name() { return "completing-on-cancel"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public java.util.Set<AiCapability> capabilities() {
            return java.util.Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new UnsupportedOperationException("dispatched through executeWithHandle");
        }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
            var handle = new ExecutionHandle.Settable(() -> {
                if (completeOnCancel) {
                    session.complete();
                }
            });
            session.send("partial");
            inFlight.complete(null);
            var hold = holdBeforeReturning;
            if (hold != null) {
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return handle;
        }
    }
}
