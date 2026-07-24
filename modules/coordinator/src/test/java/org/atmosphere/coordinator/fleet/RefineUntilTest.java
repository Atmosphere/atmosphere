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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.coordinator.evaluation.Evaluation;
import org.atmosphere.coordinator.evaluation.ResultEvaluator;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the evaluator-driven {@link AgentFleet#refineUntil} supervisor loop:
 * failing evaluators drive re-dispatches with feedback, a passing verdict returns
 * the passing result, and a never-passing evaluator is bounded by {@code maxTurns}
 * so the loop always terminates.
 */
class RefineUntilTest {

    /** Transport that records every dispatch's args and returns a per-attempt result. */
    private static final class RecordingTransport implements AgentTransport {
        private final List<Map<String, Object>> dispatches = new ArrayList<>();
        private final Runnable onDispatch;

        RecordingTransport() {
            this(() -> { });
        }

        RecordingTransport(Runnable onDispatch) {
            this.onDispatch = onDispatch;
        }

        @Override
        public AgentResult send(String agentName, String skill, Map<String, Object> args) {
            dispatches.add(Map.copyOf(args));
            var attempt = dispatches.size();
            onDispatch.run();
            return new AgentResult(agentName, skill, "attempt-" + attempt,
                    Map.of("attempt", attempt), Duration.ofMillis(1), true);
        }

        @Override
        public void stream(String agentName, String skill, Map<String, Object> args,
                           Consumer<String> onToken, Runnable onComplete) {
            onComplete.run();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        int dispatchCount() {
            return dispatches.size();
        }

        Map<String, Object> dispatchArgs(int oneBasedIndex) {
            return dispatches.get(oneBasedIndex - 1);
        }
    }

    /** Fails the first {@code failFirstN} evaluations, then passes. */
    private static final class FailFirstNEvaluator implements ResultEvaluator {
        private final int failFirstN;
        private final AtomicInteger calls = new AtomicInteger();

        FailFirstNEvaluator(int failFirstN) {
            this.failFirstN = failFirstN;
        }

        @Override
        public Evaluation evaluate(AgentResult result, AgentCall originalCall) {
            var n = calls.incrementAndGet();
            return n <= failFirstN
                    ? Evaluation.fail(0.1, "attempt " + n + " below threshold")
                    : Evaluation.pass(1.0, "attempt " + n + " accepted");
        }

        @Override
        public String name() {
            return "fail-first-" + failFirstN;
        }
    }

    /** Never passes; reports the score sequence handed to the constructor. */
    private static final class ScriptedScoreEvaluator implements ResultEvaluator {
        private final double[] scores;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedScoreEvaluator(double... scores) {
            this.scores = scores.clone();
        }

        @Override
        public Evaluation evaluate(AgentResult result, AgentCall originalCall) {
            var idx = calls.getAndIncrement();
            var score = idx < scores.length ? scores[idx] : 0.0;
            return Evaluation.fail(score, "score " + score);
        }
    }

    private static DefaultAgentFleet fleetWith(RecordingTransport transport,
                                               List<ResultEvaluator> evaluators,
                                               AgentLimits limits) {
        var proxy = new DefaultAgentProxy("writer", "1.0.0", 1, true,
                0, transport, List.of(), limits);
        return new DefaultAgentFleet(Map.of("writer", proxy), evaluators);
    }

    @Test
    void refinesExactlyKTimesThenReturnsPassingResult() {
        var k = 2;
        var transport = new RecordingTransport();
        var fleet = fleetWith(transport, List.of(new FailFirstNEvaluator(k)),
                AgentLimits.DEFAULT);

        var result = fleet.refineUntil(
                fleet.call("writer", "write", Map.of("topic", "markets")), 10);

        // K failures + 1 pass = K+1 dispatches, i.e. exactly K re-dispatches.
        assertEquals(k + 1, transport.dispatchCount(),
                "evaluator failing the first " + k + " attempts must drive exactly "
                        + k + " re-dispatches");
        assertTrue(result.success());
        assertEquals("attempt-" + (k + 1), result.text(),
                "the passing (final) attempt must be returned");

        // The first dispatch carries no feedback; every re-dispatch carries the
        // evaluator's failing reason under REFINE_FEEDBACK_KEY.
        assertFalse(transport.dispatchArgs(1).containsKey(AgentFleet.REFINE_FEEDBACK_KEY),
                "first dispatch must not carry refine feedback");
        assertTrue(transport.dispatchArgs(2).containsKey(AgentFleet.REFINE_FEEDBACK_KEY),
                "re-dispatch must carry evaluator feedback");
        assertTrue(transport.dispatchArgs(2).get(AgentFleet.REFINE_FEEDBACK_KEY)
                        .toString().contains("below threshold"),
                "feedback must relay the failing evaluation reason");
    }

    @Test
    void neverPassingEvaluatorStopsAtExplicitMaxTurns() {
        var maxTurns = 3;
        var transport = new RecordingTransport();
        var fleet = fleetWith(transport, List.of(new ScriptedScoreEvaluator(0.2, 0.2, 0.2)),
                AgentLimits.DEFAULT);

        var result = fleet.refineUntil(
                fleet.call("writer", "write", Map.of()), maxTurns);

        assertEquals(maxTurns, transport.dispatchCount(),
                "a never-passing evaluator must stop at maxTurns — no infinite loop");
        assertEquals("attempt-" + maxTurns, result.text(),
                "the last attempt is returned when no attempt passes");
    }

    @Test
    void budgetReadFromAgentConfiguredMaxTurns() {
        var transport = new RecordingTransport();
        // Never-passing evaluator; agent's @AgentRef(maxTurns) is 2.
        var fleet = fleetWith(transport, List.of(new ScriptedScoreEvaluator(0.3, 0.3, 0.3, 0.3)),
                new AgentLimits(Duration.ofSeconds(120), 2));

        var result = fleet.refineUntil(fleet.call("writer", "write", Map.of()));

        assertEquals(2, transport.dispatchCount(),
                "single-arg refineUntil must bound by the agent's configured maxTurns()");
        assertEquals("attempt-2", result.text());
    }

    @Test
    void unboundedBudgetIsClampedToSafetyCap() {
        var transport = new RecordingTransport();
        // Default limits => maxTurns == Integer.MAX_VALUE => clamp to the cap.
        var fleet = fleetWith(transport, List.of(new ScriptedScoreEvaluator()),
                AgentLimits.DEFAULT);

        var result = fleet.refineUntil(fleet.call("writer", "write", Map.of()));

        assertEquals(AgentFleet.DEFAULT_MAX_REFINE_TURNS, transport.dispatchCount(),
                "an unbounded per-agent budget must clamp to DEFAULT_MAX_REFINE_TURNS");
        assertTrue(result.text().startsWith("attempt-"));
    }

    @Test
    void returnsBestScoringAttemptWhenBudgetExhausted() {
        var transport = new RecordingTransport();
        // Non-monotonic scores: the best (0.9) is attempt 2, not the last.
        var fleet = fleetWith(transport, List.of(new ScriptedScoreEvaluator(0.4, 0.9, 0.2)),
                AgentLimits.DEFAULT);

        var result = fleet.refineUntil(fleet.call("writer", "write", Map.of()), 3);

        assertEquals(3, transport.dispatchCount());
        assertEquals("attempt-2", result.text(),
                "exhausted budget must return the highest-scoring attempt, not the last");
    }

    @Test
    void noEvaluatorsDispatchesOnce() {
        var transport = new RecordingTransport();
        var fleet = fleetWith(transport, List.of(), AgentLimits.DEFAULT);

        var result = fleet.refineUntil(fleet.call("writer", "write", Map.of()), 5);

        assertEquals(1, transport.dispatchCount(),
                "with no evaluators there is nothing to refine — dispatch exactly once");
        assertEquals("attempt-1", result.text());
    }

    @Test
    void cancellationStopsLoopAtTurnBoundary() {
        var transport = new RecordingTransport(
                () -> Thread.currentThread().interrupt());
        var fleet = fleetWith(transport, List.of(new ScriptedScoreEvaluator(0.2, 0.2, 0.2, 0.2, 0.2)),
                AgentLimits.DEFAULT);
        try {
            var result = fleet.refineUntil(fleet.call("writer", "write", Map.of()), 5);

            // The first dispatch interrupts the coordinating thread; the loop
            // observes it at the next turn boundary and returns the best attempt
            // so far without running the remaining budget.
            assertEquals(1, transport.dispatchCount(),
                    "an interrupt must stop the loop at the next turn boundary");
            assertEquals("attempt-1", result.text());
        } finally {
            // Clear the interrupt so it does not leak into sibling tests.
            Thread.interrupted();
        }
    }

    @Test
    void rejectsNonPositiveMaxTurns() {
        var transport = new RecordingTransport();
        var fleet = fleetWith(transport, List.of(), AgentLimits.DEFAULT);
        assertThrows(IllegalArgumentException.class,
                () -> fleet.refineUntil(fleet.call("writer", "write", Map.of()), 0));
    }

    @Test
    void nullCallReturnsFailureNotThrow() {
        var transport = new RecordingTransport();
        var fleet = fleetWith(transport, List.of(), AgentLimits.DEFAULT);
        var result = fleet.refineUntil(null);
        assertFalse(result.success());
        assertEquals(0, transport.dispatchCount());
    }
}
