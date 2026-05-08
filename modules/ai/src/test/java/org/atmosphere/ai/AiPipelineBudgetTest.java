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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the framework-level budget circuit breaker — verifies the
 * pipeline installs {@link BudgetCapturingSession} only when an enforced
 * {@link AiBudget} is in scope, that token / step / wall-clock breaches
 * trip the abort, and that the breach signals through
 * {@link StreamingSession#error(Throwable)} carrying an
 * {@link AiBudgetExceededException}.
 */
class AiPipelineBudgetTest {

    @Test
    void unlimitedBudgetIsNotEnforced() {
        assertFalse(AiBudget.UNLIMITED.enforced());
        assertFalse(new AiBudget(0, 0, 0, 0, Duration.ZERO).enforced());
    }

    @Test
    void enforcedBudgetReturnsTrueForAnyPositiveLimit() {
        assertTrue(AiBudget.ofTokens(100).enforced());
        assertTrue(AiBudget.ofSteps(3).enforced());
        assertTrue(AiBudget.ofWallClock(Duration.ofMillis(50)).enforced());
    }

    @Test
    void budgetRejectsNegativeFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiBudget(-1, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AiBudget(0, 0, 0, 0, Duration.ofMillis(-1)));
    }

    @Test
    void totalTokenBudgetTripsOnBreachingUsageCall() {
        var runtime = streamingRuntime(session -> {
            session.send("first chunk");
            session.usage(TokenUsage.of(50L, 30L)); // total 80 — under 100
            session.send("second chunk");
            session.usage(TokenUsage.of(20L, 10L)); // total now 110 — over 100
            session.send("this should be dropped");
            session.complete();
        });
        var pipeline = newPipeline(runtime);
        var session = new CollectingSession("budget-1");

        pipeline.execute("c1", "go", session,
                Map.of(AiBudget.METADATA_KEY, AiBudget.ofTokens(100)));

        assertTrue(session.failed(), "Session must be marked failed");
        var failure = session.failure();
        assertNotNull(failure);
        var ex = assertInstanceOf(AiBudgetExceededException.class, failure);
        assertEquals(AiBudgetExceededException.Reason.TOTAL_TOKENS, ex.reason());
        assertEquals(110L, ex.observed());
        assertEquals(100L, ex.limit());
        // Sends after the trip are silently dropped, so the captured text
        // contains only what arrived before the second usage event.
        assertFalse(session.text().contains("dropped"),
                "Post-trip send() must not reach the wire (got: " + session.text() + ")");
    }

    @Test
    void inputAndOutputTokenLimitsTripIndependently() {
        var runtime = streamingRuntime(session -> {
            session.usage(TokenUsage.of(150L, 10L)); // input 150 > 100
            session.complete();
        });
        var session = new CollectingSession("budget-input");

        newPipeline(runtime).execute("c1", "go", session,
                Map.of(AiBudget.METADATA_KEY, new AiBudget(100, 0, 0, 0, null)));

        var ex = assertInstanceOf(AiBudgetExceededException.class, session.failure());
        assertEquals(AiBudgetExceededException.Reason.INPUT_TOKENS, ex.reason());
        assertEquals(150L, ex.observed());
    }

    @Test
    void stepBudgetTripsAfterMaxSteps() {
        var attempted = new AtomicInteger();
        var runtime = streamingRuntime(session -> {
            for (int i = 0; i < 10; i++) {
                attempted.incrementAndGet();
                session.usage(TokenUsage.of(1L, 1L));
                if (session.hasErrored()) {
                    break;
                }
            }
            if (!session.hasErrored()) {
                session.complete();
            }
        });
        var session = new CollectingSession("budget-steps");

        newPipeline(runtime).execute("c1", "go", session,
                Map.of(AiBudget.METADATA_KEY, AiBudget.ofSteps(3)));

        assertTrue(session.failed());
        var ex = assertInstanceOf(AiBudgetExceededException.class, session.failure());
        assertEquals(AiBudgetExceededException.Reason.STEPS, ex.reason());
        assertEquals(4L, ex.observed(),
                "step counter increments to 4 on the call that breaches the cap of 3");
        assertEquals(3L, ex.limit());
    }

    @Test
    void wallClockBudgetTripsOnNextCallAfterDeadline() throws Exception {
        var runtime = streamingRuntime(session -> {
            session.send("before");
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            session.send("after"); // should trip on this call
            session.complete();
        });
        var session = new CollectingSession("budget-wall");

        newPipeline(runtime).execute("c1", "go", session,
                Map.of(AiBudget.METADATA_KEY, AiBudget.ofWallClock(Duration.ofMillis(50))));

        assertTrue(session.failed());
        var ex = assertInstanceOf(AiBudgetExceededException.class, session.failure());
        assertEquals(AiBudgetExceededException.Reason.WALL_CLOCK, ex.reason());
        // Greater-than-or-equal because the scheduled deadline task can fire
        // exactly at the limit on a fast scheduler — observed == limit is a
        // legitimate trip outcome, not a flake.
        assertTrue(ex.observed() >= ex.limit(),
                "observed " + ex.observed() + " must meet/exceed limit " + ex.limit());
    }

    @Test
    void noBudgetMetadataMeansNoCircuitBreaker() {
        var runtime = streamingRuntime(session -> {
            // Emit far more tokens than any default budget would allow.
            session.usage(TokenUsage.of(1_000_000L, 1_000_000L));
            session.send("ok");
            session.complete();
        });
        var session = new CollectingSession("budget-off");

        newPipeline(runtime).execute("c1", "go", session); // no metadata → no budget

        assertFalse(session.failed());
        assertTrue(session.text().contains("ok"));
    }

    @Test
    void pipelineDefaultBudgetAppliesWhenCallerSuppliesNoMetadata() {
        var runtime = streamingRuntime(session -> {
            session.usage(TokenUsage.of(60L, 50L));
            session.complete();
        });
        var pipeline = newPipeline(runtime);
        pipeline.setDefaultBudget(AiBudget.ofTokens(100));

        var session = new CollectingSession("budget-default");
        pipeline.execute("c1", "go", session);

        assertTrue(session.failed());
        assertEquals(AiBudgetExceededException.Reason.TOTAL_TOKENS,
                ((AiBudgetExceededException) session.failure()).reason());
    }

    @Test
    void callerSuppliedBudgetWinsOverPipelineDefault() {
        var runtime = streamingRuntime(session -> {
            session.usage(TokenUsage.of(60L, 50L)); // 110 total
            session.complete();
        });
        var pipeline = newPipeline(runtime);
        pipeline.setDefaultBudget(AiBudget.ofTokens(50)); // would trip

        var session = new CollectingSession("budget-caller-wins");
        pipeline.execute("c1", "go", session,
                // Caller raises the cap to 200 — the call should succeed.
                Map.of(AiBudget.METADATA_KEY, AiBudget.ofTokens(200)));

        assertFalse(session.failed());
    }

    @Test
    void postTripErrorFromRuntimeIsSwallowedToPreserveOneTerminalFrame() {
        // Runtime trips the budget then errors anyway (e.g., HTTP timeout
        // racing with the budget breach). The pipeline must surface the
        // budget exception once, not twice.
        var runtime = streamingRuntime(session -> {
            session.usage(TokenUsage.of(200L, 0L)); // trips
            session.error(new RuntimeException("late upstream error"));
        });
        var session = new CollectingSession("budget-double");

        newPipeline(runtime).execute("c1", "go", session,
                Map.of(AiBudget.METADATA_KEY, AiBudget.ofTokens(100)));

        assertTrue(session.failed());
        // The first error wins — the budget exception, not the late upstream error.
        assertInstanceOf(AiBudgetExceededException.class, session.failure());
    }

    /**
     * Regression: wall-clock budget must trip even when the runtime hangs
     * without ever calling back into the session. Earlier the wall-clock
     * cap was sampled lazily at session-method boundaries, so a provider
     * that blocked silently after dispatch never fired the deadline. Now
     * the deadline is scheduled at construction and trips independently.
     */
    @Test
    void wallClockBudgetTripsWhenRuntimeMakesNoSessionCallbacks() throws Exception {
        var runtime = streamingRuntime(session -> {
            // Simulate a hung provider: block well past the wall-clock deadline
            // without any session.send / session.complete / session.usage calls.
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Even after the sleep, only call complete (no send/usage in between)
            // — the scheduled task should already have tripped before this lands.
            session.complete();
        });
        var session = new CollectingSession("budget-no-callbacks");

        long start = System.nanoTime();
        newPipeline(runtime).execute("c1", "go", session,
                Map.of(AiBudget.METADATA_KEY, AiBudget.ofWallClock(Duration.ofMillis(50))));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(session.failed(),
                "Wall-clock budget must trip even with no session callbacks. "
                        + "Elapsed " + elapsedMs + "ms");
        var ex = assertInstanceOf(AiBudgetExceededException.class, session.failure());
        assertEquals(AiBudgetExceededException.Reason.WALL_CLOCK, ex.reason());
        assertTrue(ex.observed() >= ex.limit(),
                "observed " + ex.observed() + " must meet/exceed limit " + ex.limit());
    }

    @Test
    void aiBudgetFromMetadataReturnsNullWhenAbsent() {
        assertNull(AiBudget.from((Map<String, Object>) null));
        assertNull(AiBudget.from(Map.of()));
        assertNull(AiBudget.from(Map.of("ai.budget", "not-a-budget")));
    }

    private static AiPipeline newPipeline(AgentRuntime runtime) {
        return new AiPipeline(runtime, "system", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);
    }

    /** Build a stub runtime whose {@code execute} routes to the supplied
     * stream-driving lambda — keeps tests focused on the budget plumbing
     * rather than runtime-specific gymnastics. */
    private static AgentRuntime streamingRuntime(StreamingDriver driver) {
        return new AgentRuntime() {
            @Override public String name() { return "budget-test-stub"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public Set<AiCapability> capabilities() {
                return Set.of(AiCapability.TEXT_STREAMING, AiCapability.BUDGET_ENFORCEMENT);
            }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                driver.drive(session);
            }
        };
    }

    @FunctionalInterface
    private interface StreamingDriver {
        void drive(StreamingSession session);
    }

    /** Sanity check: ensure the captured runtime context survives budget injection. */
    @Test
    void budgetMetadataReachesTheRuntimeAsContextMetadata() {
        var seen = new AtomicReference<AgentExecutionContext>();
        var runtime = new AgentRuntime() {
            @Override public String name() { return "ctx-stub"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public Set<AiCapability> capabilities() {
                return Set.of(AiCapability.TEXT_STREAMING);
            }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                seen.set(context);
                session.complete();
            }
        };
        var pipeline = newPipeline(runtime);
        pipeline.setDefaultBudget(AiBudget.ofTokens(500));

        var session = new CollectingSession("budget-ctx");
        pipeline.execute("c1", "go", session);

        var ctx = seen.get();
        assertNotNull(ctx);
        var observed = AiBudget.from(ctx);
        assertNotNull(observed, "Pipeline default budget must thread through context metadata");
        assertEquals(500L, observed.maxTotalTokens());
    }
}
