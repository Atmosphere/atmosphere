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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mode parity with the {@code @AiEndpoint} path: a pipeline run its caller
 * cancels — the dispatching thread is interrupted, as a cancelled batch item's
 * is — ends with the one cancelled terminal, not with the {@code complete} the
 * runtime sends on the cancel (which presented the cut answer as finished), and
 * not with a second terminal after it.
 */
class AiPipelineCancelledTerminalTest {

    @Test
    void anInterruptedCallerEndsTheRunWithOneCancelledTerminal() throws Exception {
        var runtime = new CompletingOnCancelRuntime();
        var client = new TerminalRecordingSession();
        var pipeline = new AiPipeline(runtime, "", null, null, null, List.of(), List.of(), AiMetrics.NOOP);

        var done = new CompletableFuture<Void>();
        var caller = Thread.startVirtualThread(() -> {
            pipeline.execute("client-1", "explain", client);
            done.complete(null);
        });
        assertTrue(runtime.inFlight.await(10, TimeUnit.SECONDS));
        // The handle is published right after executeWithHandle returns.
        Thread.sleep(50);
        caller.interrupt();
        done.get(10, TimeUnit.SECONDS);

        assertTrue(runtime.cancelled.await(1, TimeUnit.SECONDS), "the interrupt cancels the model call");
        assertEquals(List.of("cancelled:caller"), client.terminals,
                "one cancelled terminal, no complete: " + client.terminals);
        assertTrue(client.hasErrored(), "a cancelled run must not be committed as a finished answer");
    }

    @Test
    void anInterruptBeforeTheHandleIsPublishedStillCancelsTheCall() throws Exception {
        var runtime = new CompletingOnCancelRuntime();
        runtime.holdBeforeReturning = new CountDownLatch(1);
        var client = new TerminalRecordingSession();
        var pipeline = new AiPipeline(runtime, "", null, null, null, List.of(), List.of(), AiMetrics.NOOP);

        var done = new CompletableFuture<Void>();
        var caller = Thread.startVirtualThread(() -> {
            pipeline.execute("client-2", "explain", client);
            done.complete(null);
        });
        assertTrue(runtime.inFlight.await(10, TimeUnit.SECONDS));
        // Still inside executeWithHandle: the hold swallows the interrupt the
        // way a runtime that is not interruptible would, and leaves it set.
        caller.interrupt();
        runtime.holdBeforeReturning.countDown();
        done.get(10, TimeUnit.SECONDS);

        assertEquals(List.of("cancelled:caller"), client.terminals, client.terminals.toString());
    }

    @Test
    void aRunNobodyCancelsCompletesAsBefore() throws Exception {
        var runtime = new CompletingOnCancelRuntime();
        runtime.completeAtOnce = true;
        var client = new TerminalRecordingSession();
        var pipeline = new AiPipeline(runtime, "", null, null, null, List.of(), List.of(), AiMetrics.NOOP);

        pipeline.execute("client-3", "explain", client);

        assertEquals(List.of("complete"), client.terminals);
    }

    /** Records every terminal call it receives, in order. */
    static final class TerminalRecordingSession implements StreamingSession {
        final List<String> terminals = new CopyOnWriteArrayList<>();
        private volatile boolean errored;

        @Override public String sessionId() { return "recording"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { terminals.add("complete"); }
        @Override public void complete(String summary) { terminals.add("complete"); }

        @Override
        public void error(Throwable t) {
            errored = true;
            terminals.add(t instanceof RunCancelledException cancelled
                    ? "cancelled:" + cancelled.reason().wire() : "error:" + t.getMessage());
        }

        @Override public boolean isClosed() { return !terminals.isEmpty(); }
        @Override public boolean hasErrored() { return errored; }
    }

    /** Streams one token and holds the call open; a cancel completes the session, as runtime bridges do. */
    static final class CompletingOnCancelRuntime implements AgentRuntime {
        final CountDownLatch inFlight = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        volatile CountDownLatch holdBeforeReturning;
        volatile boolean completeAtOnce;

        @Override public String name() { return "completing-on-cancel"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new UnsupportedOperationException("dispatched through executeWithHandle");
        }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
            var handle = new ExecutionHandle.Settable(() -> {
                session.complete();
                cancelled.countDown();
            });
            session.send("partial");
            if (completeAtOnce) {
                session.complete();
                handle.complete();
                return handle;
            }
            inFlight.countDown();
            var hold = holdBeforeReturning;
            if (hold != null) {
                var interrupted = false;
                while (true) {
                    try {
                        hold.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return handle;
        }
    }
}
