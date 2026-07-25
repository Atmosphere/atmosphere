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
package org.atmosphere.admin.evals;

import org.atmosphere.admin.ControlAuthorizer;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalRunnerTest {

    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(5);

    // --- helpers ----------------------------------------------------------

    private static AgentRuntime echoRuntime(Function<String, String> responder) {
        return new AgentRuntime() {
            @Override public String name() { return "fake-target"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send(responder.apply(context.message()));
                session.complete();
            }
        };
    }

    /** Scorer that passes when the response contains the case's reference. */
    private static EvalRunner.CaseScorer referenceContainsScorer() {
        return (evalCase, response) -> new SampledLiveScorer.Verdict(
                response.contains(evalCase.reference()), 1.0, "contains-check");
    }

    private static InMemoryEvalDatasetStore datasetOf(EvalCase... cases) {
        var store = new InMemoryEvalDatasetStore();
        for (var c : cases) {
            store.save(c);
        }
        return store;
    }

    private static EvalCase caseOf(String id, String prompt, String reference, String... tags) {
        return new EvalCase(id, prompt, reference, "manual", List.of(tags), Instant.now());
    }

    private static EvalRun awaitRow(EvalRunStore store, String id, Duration timeout)
            throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var row = store.findById(id);
            if (row.isPresent()) {
                return row.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run row " + id + " did not appear within " + timeout);
    }

    // --- replay + persistence --------------------------------------------

    @Test
    void replaysEveryCaseAndPersistsPerCaseRowsPlusAggregate() {
        var dataset = datasetOf(
                caseOf("c1", "Q-one", "alpha"),
                caseOf("c2", "Q-two", "beta"),
                caseOf("c3", "Q-three", "gamma"));
        var runs = new InMemoryEvalRunStore();
        // Target answers correctly for c1/c2, wrongly for c3.
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> switch (prompt) {
                    case "Q-one" -> "the answer is alpha";
                    case "Q-two" -> "the answer is beta";
                    default -> "the answer is delta";
                }),
                model -> referenceContainsScorer(),
                dataset, runs, 2, CASE_TIMEOUT, 0.5);

        var summary = runner.run(new EvalRunner.RunRequest("smoke", null, null));

        assertEquals(3, summary.totalCases());
        assertEquals(2, summary.passedCases());
        assertEquals(1, summary.failedCases());
        assertEquals(2.0 / 3.0, summary.passRate(), 1e-9);
        assertTrue(summary.passed(), "pass rate 0.67 must clear the 0.5 threshold");
        assertTrue(summary.completed());

        // one row per case + the aggregate row (id = runId)
        assertEquals(4, runs.list().size());
        for (var caseId : List.of("c1", "c2", "c3")) {
            assertTrue(runs.findById(summary.runId() + "." + caseId).isPresent(),
                    "per-case row must be persisted for " + caseId);
        }
        var aggregate = runs.findById(summary.runId()).orElseThrow();
        assertTrue(aggregate.passed());
        assertEquals(2.0 / 3.0, aggregate.scores().get("passRate"), 1e-9);
        assertEquals(3.0, aggregate.scores().get("totalCases"), 1e-9);
        assertFalse(runs.findById(summary.runId() + ".c3").orElseThrow().passed(),
                "the wrong answer must produce a failed row");
    }

    @Test
    void aggregateFailsWhenPassRateBelowThreshold() {
        var dataset = datasetOf(caseOf("c1", "Q", "expected"));
        var runs = new InMemoryEvalRunStore();
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> "unrelated"),
                model -> referenceContainsScorer(),
                dataset, runs, 1, CASE_TIMEOUT, 0.75);

        var summary = runner.run(new EvalRunner.RunRequest("smoke", null, null));

        assertEquals(0.0, summary.passRate(), 1e-9);
        assertFalse(summary.passed());
        assertFalse(runs.findById(summary.runId()).orElseThrow().passed());
    }

    @Test
    void tagFilterReplaysOnlyMatchingCases() {
        var dataset = datasetOf(
                caseOf("tagged", "Q-tagged", "alpha", "golden"),
                caseOf("untagged", "Q-untagged", "beta"));
        var runs = new InMemoryEvalRunStore();
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> "alpha beta"),
                model -> referenceContainsScorer(),
                dataset, runs, 1, CASE_TIMEOUT, 0.5);

        var summary = runner.run(new EvalRunner.RunRequest("smoke", "golden", null));

        assertEquals(1, summary.totalCases());
        assertTrue(runs.findById(summary.runId() + ".tagged").isPresent());
        assertTrue(runs.findById(summary.runId() + ".untagged").isEmpty());
    }

    // --- terminal paths ---------------------------------------------------

    @Test
    void perCaseTimeoutProducesFailedRowNotHungRunner() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            var dataset = datasetOf(
                    caseOf("fast", "Q-fast", "alpha"),
                    caseOf("slow", "Q-slow", "beta"));
            var runs = new InMemoryEvalRunStore();
            AgentRuntime target = new AgentRuntime() {
                @Override public String name() { return "half-hung"; }
                @Override public boolean isAvailable() { return true; }
                @Override public int priority() { return 0; }
                @Override public void configure(AiConfig.LlmSettings settings) { }

                @Override
                public void execute(AgentExecutionContext context, StreamingSession session) {
                    if (context.message().contains("slow")) {
                        return; // never completes the session
                    }
                    session.send("alpha");
                    session.complete();
                }
            };
            var runner = new EvalRunner(() -> target, model -> referenceContainsScorer(),
                    dataset, runs, 2, Duration.ofMillis(200), 0.5);

            var summary = runner.run(new EvalRunner.RunRequest("smoke", null, null));

            assertEquals(1, summary.passedCases());
            assertEquals(1, summary.failedCases());
            var slowRow = runs.findById(summary.runId() + ".slow").orElseThrow();
            assertFalse(slowRow.passed());
            assertTrue(slowRow.notes().contains("timeout"),
                    "timeout must be recorded, was: " + slowRow.notes());
            assertNotNull(runs.findById(summary.runId()).orElse(null),
                    "aggregate must still be written after a timeout");
        });
    }

    @Test
    void scorerFailureProducesFailedRowAndAggregateStillLands() {
        var dataset = datasetOf(caseOf("c1", "Q", "expected"));
        var runs = new InMemoryEvalRunStore();
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> "expected"),
                model -> (evalCase, response) -> {
                    throw new IllegalStateException("judge unavailable");
                },
                dataset, runs, 1, CASE_TIMEOUT, 0.5);

        var summary = runner.run(new EvalRunner.RunRequest("smoke", null, null));

        assertEquals(0, summary.passedCases());
        assertEquals(1, summary.failedCases());
        var row = runs.findById(summary.runId() + ".c1").orElseThrow();
        assertTrue(row.notes().contains("judge unavailable"));
        assertTrue(runs.findById(summary.runId()).isPresent());
    }

    @Test
    void closeCancelsInFlightRunAndStillWritesAggregate() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            var dataset = datasetOf(caseOf("blocked", "Q-blocked", "alpha"));
            var runs = new InMemoryEvalRunStore();
            var blocker = new CountDownLatch(1);
            AgentRuntime target = new AgentRuntime() {
                @Override public String name() { return "blocking"; }
                @Override public boolean isAvailable() { return true; }
                @Override public int priority() { return 0; }
                @Override public void configure(AiConfig.LlmSettings settings) { }

                @Override
                public void execute(AgentExecutionContext context, StreamingSession session) {
                    try {
                        blocker.await();
                        session.send("alpha");
                        session.complete();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        session.error(e);
                    }
                }
            };
            var runner = new EvalRunner(() -> target, model -> referenceContainsScorer(),
                    dataset, runs, 1, Duration.ofSeconds(30), 0.5);

            var started = runner.start(new EvalRunner.RunRequest("smoke", null, null));
            runner.close(); // interrupts the in-flight run

            var aggregate = awaitRow(runs, started.runId(), Duration.ofSeconds(5));
            assertFalse(aggregate.passed(), "a cancelled run must not report success");
            assertTrue(aggregate.notes().contains("cancelled"),
                    "aggregate must record the cancellation, was: " + aggregate.notes());
            var caseRow = runs.findById(started.runId() + ".blocked").orElseThrow();
            assertFalse(caseRow.passed());
        });
    }

    // --- guards -----------------------------------------------------------

    @Test
    void secondConcurrentRunIsRejected() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            var dataset = datasetOf(caseOf("c1", "Q", "alpha"));
            var runs = new InMemoryEvalRunStore();
            var blocker = new CountDownLatch(1);
            var runner = new EvalRunner(
                    () -> echoRuntime(prompt -> {
                        try {
                            blocker.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "alpha";
                    }),
                    model -> referenceContainsScorer(),
                    dataset, runs, 1, Duration.ofSeconds(30), 0.5);

            var started = runner.start(new EvalRunner.RunRequest("smoke", null, null));
            assertTrue(runner.isRunning());
            assertThrows(IllegalStateException.class,
                    () -> runner.run(new EvalRunner.RunRequest("smoke", null, null)),
                    "a second run must be rejected while one is in flight");

            blocker.countDown();
            var aggregate = awaitRow(runs, started.runId(), Duration.ofSeconds(5));
            assertTrue(aggregate.passed());
        });
    }

    @Test
    void emptyDatasetIsRejected() {
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> "x"), model -> referenceContainsScorer(),
                new InMemoryEvalDatasetStore(), new InMemoryEvalRunStore(),
                1, CASE_TIMEOUT, 0.5);
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(new EvalRunner.RunRequest("smoke", null, null)));
    }

    @Test
    void unavailableTargetRuntimeIsRejected() {
        var dataset = datasetOf(caseOf("c1", "Q", "alpha"));
        AgentRuntime unavailable = new AgentRuntime() {
            @Override public String name() { return "down"; }
            @Override public boolean isAvailable() { return false; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public void execute(AgentExecutionContext context, StreamingSession session) { }
        };
        var runner = new EvalRunner(() -> unavailable, model -> referenceContainsScorer(),
                dataset, new InMemoryEvalRunStore(), 1, CASE_TIMEOUT, 0.5);
        assertThrows(IllegalStateException.class,
                () -> runner.run(new EvalRunner.RunRequest("smoke", null, null)));
    }

    // --- controller surface (authz + delegation) --------------------------

    @Test
    void controllerStartRunRequiresWriteGrant() {
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> "x"), model -> referenceContainsScorer(),
                datasetOf(caseOf("c1", "Q", "x")), new InMemoryEvalRunStore(),
                1, CASE_TIMEOUT, 0.5);
        var controller = new EvalController(new InMemoryEvalRunStore(),
                new InMemoryEvalDatasetStore(), CoordinationJournal.NOOP, null, runner,
                ControlAuthorizer.DENY_ALL, null);
        assertTrue(controller.runnerEnabled());
        assertThrows(SecurityException.class, () -> controller.startRun(
                new EvalRunner.RunRequest("smoke", null, null), "intruder"));
    }

    @Test
    void controllerStartRunDelegatesToRunner() throws InterruptedException {
        var runs = new InMemoryEvalRunStore();
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> "alpha"), model -> referenceContainsScorer(),
                datasetOf(caseOf("c1", "Q", "alpha")), runs, 1, CASE_TIMEOUT, 0.5);
        var controller = new EvalController(runs, new InMemoryEvalDatasetStore(),
                CoordinationJournal.NOOP, null, runner,
                (action, target, principal) -> true, null);

        var started = controller.startRun(new EvalRunner.RunRequest("smoke", null, null), "alex");

        assertEquals(1, started.totalCases());
        var aggregate = awaitRow(runs, started.runId(), Duration.ofSeconds(5));
        assertTrue(aggregate.passed());
    }

    @Test
    void controllerWithoutRunnerReportsDisabled() {
        var controller = new EvalController(new InMemoryEvalRunStore(),
                new InMemoryEvalDatasetStore(), CoordinationJournal.NOOP, null,
                (action, target, principal) -> true, null);
        assertFalse(controller.runnerEnabled());
        assertThrows(IllegalStateException.class, () -> controller.startRun(
                new EvalRunner.RunRequest("smoke", null, null), "alex"));
    }
}
