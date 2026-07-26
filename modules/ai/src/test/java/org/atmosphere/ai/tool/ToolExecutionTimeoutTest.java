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
package org.atmosphere.ai.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-tool execution deadline at the shared
 * {@link ToolExecutionHelper} seam. Before it existed, the only bound on a tool
 * call was the human-in-the-loop {@code approvalTimeout}: a tool that never
 * returned parked the turn forever with no terminal path (Correctness
 * Invariant #3), and nothing ever cancelled it (Invariant #2).
 *
 * <p>The deadline value reaching the seam is the tool's own
 * {@link ToolDefinition#executionTimeout()} (0 = the framework default resolved
 * from {@link ToolExecutionHelper#TOOL_EXECUTION_TIMEOUT_PROPERTY} /
 * {@link ToolExecutionHelper#TOOL_EXECUTION_TIMEOUT_ENV}, negative =
 * unbounded), so these tests drive the package-private bounded overload
 * directly.</p>
 */
class ToolExecutionTimeoutTest {

    private static final String TOOL = "slow_tool";

    @AfterEach
    void clearOverrides() {
        System.clearProperty(ToolExecutionHelper.TOOL_EXECUTION_TIMEOUT_PROPERTY);
        // Belt-and-braces isolation: surefire reuses this thread across tests,
        // so a stray interrupt must never bleed into the next one. The helper
        // is asserted to do this itself in
        // aTimedOutCallLeavesNoStaleInterruptOnTheCallerThread.
        Thread.interrupted();
    }

    /** Run {@code executor} through the bounded seam with an explicit deadline. */
    private static String executeWithDeadline(String toolName, ToolExecutor executor,
                                              Map<String, Object> args, long timeoutSeconds) {
        return ToolExecutionHelper.executeAndFormat(toolName, executor, args, Map.of(),
                timeoutSeconds);
    }

    /**
     * The deadline must not cost the tool body its thread context. Enforcing it
     * by running the body on another thread would silently drop every
     * non-inheritable {@link ThreadLocal} the caller set up — Spring's
     * {@code SecurityContextHolder} and {@code RequestContextHolder} are the
     * ones that matter, since a tool doing its own authorization check would
     * then see a null authentication. That is a silent security regression, so
     * this is pinned rather than left to review.
     */
    @Test
    void callerThreadLocalsAreVisibleInsideTheToolBodyWithADeadlineActive() {
        var callerLocal = new ThreadLocal<String>();
        callerLocal.set("authenticated-principal");
        var callerThread = Thread.currentThread();
        var seenThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try {
            var result = executeWithDeadline(TOOL, args -> {
                seenThread.set(Thread.currentThread());
                var seen = callerLocal.get();
                return seen == null ? "THREAD-LOCAL-LOST" : seen;
            }, Map.of(), 30);

            assertEquals("authenticated-principal", result,
                    "a caller ThreadLocal must remain visible to the tool body");
            assertSame(callerThread, seenThread.get(),
                    "the tool body must run inline on the calling thread — no hop");
        } finally {
            callerLocal.remove();
        }
    }

    /**
     * Same guarantee through the public {@link ToolDefinition} seam every
     * runtime bridge actually uses, so the property cannot be lost by a caller
     * that never touches the package-private overload.
     */
    @Test
    void callerThreadLocalsSurviveTheToolDefinitionSeamToo() {
        var callerLocal = new ThreadLocal<String>();
        callerLocal.set("authenticated-principal");
        try {
            var tool = ToolDefinition.builder("threadlocal_tool", "Reads a caller ThreadLocal")
                    .executor(args -> {
                        var seen = callerLocal.get();
                        return seen == null ? "THREAD-LOCAL-LOST" : seen;
                    })
                    .executionTimeout(30)
                    .build();
            assertEquals("authenticated-principal",
                    ToolExecutionHelper.executeAndFormat(tool, Map.of()));
        } finally {
            callerLocal.remove();
        }
    }

    @Test
    void aTimedOutCallLeavesNoStaleInterruptOnTheCallerThread() {
        var result = executeWithDeadline(TOOL, args -> {
            Thread.sleep(30_000);
            return "unreachable";
        }, Map.of(), 1);

        assertTrue(result.contains("tool_timeout"), result);
        assertFalse(Thread.currentThread().isInterrupted(),
                "the watchdog's interrupt must be cleared before returning — a stale "
                        + "interrupt would leak into the next task on this thread");
        // Prove it concretely: a subsequent blocking call must not throw.
        assertDoesNotThrow(() -> Thread.sleep(1));
    }

    @Test
    void aToolThatSwallowsTheInterruptIsStillReportedAsTimedOut() {
        // Residual limitation, pinned so the behaviour is explicit: interruption
        // is cooperative, so a swallowing tool overruns in wall-clock terms —
        // but the deadline still means what it says on the result.
        var result = executeWithDeadline(TOOL, args -> {
            try {
                Thread.sleep(2_500);
            } catch (InterruptedException swallowed) {
                // Deliberately ignored — this is the case under test.
            }
            return "finished anyway";
        }, Map.of(), 1);

        assertTrue(result.contains("tool_timeout"),
                "a tool that outran its deadline must report a timeout even when it "
                        + "swallowed the interrupt and produced a value: " + result);
        assertFalse(Thread.currentThread().isInterrupted(),
                "and it must still not leave a stale interrupt behind");
    }

    @Test
    void hungToolProducesAToolErrorInsteadOfHangingTheTurn() {
        var released = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();

        var start = System.nanoTime();
        var result = executeWithDeadline(TOOL, args -> {
            try {
                // Far longer than the deadline: the call must not wait for it.
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // The watchdog's interrupt reaching the tool body. Do NOT
                // re-assert the flag here — the helper clears its own signal,
                // and a cooperative tool unwinds on interrupt exactly like this.
                interrupted.set(true);
            }
            return "never returned in time";
        }, Map.of(), 1);
        var elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(result.contains("\"error\":\"tool_timeout\""),
                "the model must receive a structured timeout error: " + result);
        assertTrue(result.contains(TOOL), "the error must name the tool: " + result);
        assertTrue(result.contains("\"timeoutSeconds\":1"),
                "the error must carry the machine-readable bound: " + result);
        assertTrue(elapsedMillis < 15_000L,
                "the caller must be released at the deadline, not at tool completion "
                        + "(waited " + elapsedMillis + "ms)");
        // Terminal path: the hung execution is genuinely signalled, not merely
        // un-awaited — the body observed the interrupt and unwound.
        assertTrue(interrupted.get(), "the timed-out tool must be interrupted, not left running");
        released.countDown();
    }

    @Test
    void fastToolIsUnaffectedByTheDeadline() {
        var result = executeWithDeadline("fast_tool",
                args -> "done:" + args.get("x"), Map.of("x", 1), 30);
        assertEquals("done:1", result);
    }

    @Test
    void toolFailureSurfacesIdenticallyWithAndWithoutADeadline() {
        var withDeadline = executeWithDeadline(TOOL, args -> {
            throw new IllegalStateException("boom");
        }, Map.of(), 30);
        var withoutDeadline = executeWithDeadline(TOOL, args -> {
            throw new IllegalStateException("boom");
        }, Map.of(), -1);
        assertEquals(withoutDeadline, withDeadline,
                "mode parity: the deadline path must report a tool failure the same way");
        assertTrue(withDeadline.contains("boom"), withDeadline);
    }

    /**
     * An interrupt the watchdog did <em>not</em> raise — a genuinely cancelled
     * turn — must not be swallowed by this seam: it is reported distinctly and
     * the flag is re-asserted so the cancellation keeps propagating.
     */
    @Test
    void anExternalCancellationIsRelayedRatherThanStranded() {
        var result = executeWithDeadline(TOOL, args -> {
            throw new InterruptedException("cancelled from outside");
        }, Map.of(), 30);

        assertTrue(result.contains("\"error\":\"tool_interrupted\""),
                "an external cancel must be named distinctly, not folded into a "
                        + "generic tool error: " + result);
        assertTrue(Thread.interrupted(),
                "the interrupt flag must be re-asserted so the cancel is not stranded");
    }

    @Test
    void negativeOverrideDisablesTheDeadline() {
        var result = executeWithDeadline(TOOL, args -> "inline", Map.of(), -1);
        assertEquals("inline", result);
    }

    @Test
    void perToolOverrideWinsOverTheGlobalSetting() {
        // The global knob applies only when the tool declares no bound of its
        // own (executionTimeout == 0).
        System.setProperty(ToolExecutionHelper.TOOL_EXECUTION_TIMEOUT_PROPERTY, "1");
        var withGlobalBound = executeWithDeadline(TOOL, args -> {
            Thread.sleep(30_000);
            return "unreachable";
        }, Map.of(), 0);
        assertTrue(withGlobalBound.contains("tool_timeout"),
                "an unbounded tool must inherit the global deadline: " + withGlobalBound);

        var withPerToolOptOut = executeWithDeadline(TOOL, args -> "inline", Map.of(), -1);
        assertEquals("inline", withPerToolOptOut,
                "a per-tool opt-out must beat the global setting");
    }

    @Test
    void malformedGlobalSettingFallsBackToTheDefault() {
        System.setProperty(ToolExecutionHelper.TOOL_EXECUTION_TIMEOUT_PROPERTY, "not-a-number");
        // Lenient parse (Invariant #4): the call runs under the default bound
        // rather than throwing out of the seam.
        assertEquals("ok", executeWithDeadline(TOOL, args -> "ok", Map.of(), 0));
    }

    @Test
    void defaultIsEnabledSoAHungToolCannotParkTheTurnForever() {
        assertTrue(ToolExecutionHelper.DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS > 0,
                "the deadline must be on by default — an opt-in bound is no bound");
    }

    /**
     * A tool built without touching {@code executionTimeout} must still be
     * bounded: the record component defaults to 0, which resolves to the
     * framework default rather than to "no bound".
     */
    @Test
    void aToolThatDeclaresNoBoundStillInheritsTheFrameworkDefault() {
        var tool = ToolDefinition.builder("unbounded_by_omission", "Declares no bound")
                .executor(args -> "ok")
                .build();
        assertEquals(0L, tool.executionTimeout(),
                "omitting the bound must mean 'inherit', not 'disable'");
        assertEquals("ok", ToolExecutionHelper.executeAndFormat(tool, Map.of()));
    }
}
