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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link AgentRuntime#generate(AgentExecutionContext, Duration)}
 * enforcing its caller-supplied bound.
 *
 * <p>The previous implementation ran {@code execute()} to completion and only
 * then called {@code collector.await(timeout)}, so for a <em>blocking</em>
 * runtime the bound was a no-op: the await could not start until the call it
 * was meant to bound had already returned. The guardrail-admission path
 * (moderation / injection / scope classifiers) and the coordinator's result
 * evaluator sit on request-serving threads and pass short bounds precisely to
 * avoid that stall.</p>
 */
class AgentRuntimeGenerateTimeoutTest {

    private static final Duration BOUND = Duration.ofMillis(300);

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "classify this", "you are a classifier", null,
                null, "timeout-test", null, "timeout-test",
                List.of(), null, null,
                List.of(), Map.of(), List.of(),
                String.class, null);
    }

    /**
     * A deliberately-slow <em>blocking</em> runtime: {@code execute} does not
     * return until released. This is the shape that made the old bound inert.
     */
    private static final class SlowBlockingRuntime implements AgentRuntime {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean completedNormally = new AtomicBoolean();

        @Override public String name() { return "slow-blocking"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext ctx, StreamingSession session) {
            entered.countDown();
            try {
                // Simulates a provider HTTP call with its own (much longer)
                // timeout — 30s here stands in for the 120s x retries case.
                if (release.await(30, TimeUnit.SECONDS)) {
                    completedNormally.set(true);
                    session.complete("late answer");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.set(true);
            }
        }
    }

    /** A runtime that publishes a real cancellable handle and never completes. */
    private static final class HandleRuntime implements AgentRuntime {
        final AtomicInteger cancels = new AtomicInteger();
        final CountDownLatch published = new CountDownLatch(1);
        volatile ExecutionHandle.Settable handle;

        @Override public String name() { return "handle-runtime"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext ctx, StreamingSession session) {
            executeWithHandle(ctx, session);
        }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext ctx, StreamingSession session) {
            // Async runtime: returns immediately, streams (never) in background.
            var settable = new ExecutionHandle.Settable(cancels::incrementAndGet);
            this.handle = settable;
            published.countDown();
            return settable;
        }
    }

    @Test
    void generateReturnsWithinTheBoundForABlockingRuntime() throws Exception {
        var runtime = new SlowBlockingRuntime();

        var start = System.nanoTime();
        var result = runtime.generate(context(), BOUND);
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals("", result, "a timed-out generate yields the documented empty string");
        assertTrue(elapsed.toMillis() < 5_000,
                "generate must return near its bound, not the runtime's own timeout; took "
                        + elapsed.toMillis() + "ms");
        assertTrue(runtime.entered.await(5, TimeUnit.SECONDS),
                "the runtime must actually have been dispatched");
        assertFalse(runtime.completedNormally.get(),
                "the slow call must not have completed within the bound");

        runtime.release.countDown();
    }

    @Test
    void timedOutGenerateAbandonsTheBlockingCarrier() throws Exception {
        var runtime = new SlowBlockingRuntime();

        runtime.generate(context(), BOUND);

        // A blocking runtime never publishes a handle, so interrupting the
        // carrier is the only way to abandon it (Invariant #2).
        var deadline = System.currentTimeMillis() + 5_000;
        while (!runtime.interrupted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(runtime.interrupted.get(),
                "the carrier running the abandoned call must be interrupted, not left parked");

        runtime.release.countDown();
    }

    @Test
    void timedOutGenerateCancelsThePublishedHandle() throws Exception {
        var runtime = new HandleRuntime();

        var result = runtime.generate(context(), BOUND);

        assertEquals("", result);
        assertTrue(runtime.published.await(5, TimeUnit.SECONDS));
        var deadline = System.currentTimeMillis() + 5_000;
        while (runtime.cancels.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, runtime.cancels.get(),
                "the runtime's native cancel primitive must fire so the upstream call is "
                        + "abandoned and its tokens refunded");
        assertTrue(runtime.handle.isDone(), "the cancelled handle must be terminal");
    }

    @Test
    void fastRuntimeIsNotPenalisedByTheBound() {
        var runtime = new AgentRuntime() {
            @Override public String name() { return "fast"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override
            public void execute(AgentExecutionContext ctx, StreamingSession session) {
                session.send("NONE");
                session.complete();
            }
        };

        var start = System.nanoTime();
        var result = runtime.generate(context(), Duration.ofSeconds(30));
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals("NONE", result, "a completed run must return its collected text unchanged");
        assertTrue(elapsed.toMillis() < 5_000,
                "a fast runtime must return immediately, not wait out the bound");
    }

    @Test
    void runtimeFailureUnparksTheCallerImmediately() {
        var runtime = new AgentRuntime() {
            @Override public String name() { return "throwing"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override
            public void execute(AgentExecutionContext ctx, StreamingSession session) {
                throw new IllegalStateException("provider exploded");
            }
        };

        var start = System.nanoTime();
        var thrown = assertThrows(IllegalStateException.class,
                () -> runtime.generate(context(), Duration.ofSeconds(30)),
                "a runtime that throws inside execute must surface that failure to the caller — "
                        + "dispatching on a carrier is an implementation detail, and callers such as "
                        + "the scope/moderation guardrails branch on the throw (an empty return reads "
                        + "as 'admit')");
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals("provider exploded", thrown.getMessage());
        assertTrue(elapsed.toMillis() < 5_000,
                "a failed dispatch must unpark the caller rather than sit out the full bound; took "
                        + elapsed.toMillis() + "ms");
    }

    @Test
    void generateResultHonoursTheSameBound() {
        var runtime = new SlowBlockingRuntime();

        var start = System.nanoTime();
        var result = runtime.generateResult(context(), BOUND);
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Mode parity (Invariant #7): the typed variant must bound identically.
        assertEquals("", result.text());
        assertTrue(elapsed.toMillis() < 5_000,
                "generateResult must enforce the same bound as generate; took "
                        + elapsed.toMillis() + "ms");

        runtime.release.countDown();
    }
}
