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
package org.atmosphere.checkpoint.workflow;

import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the durable hibernating {@link Workflow} primitive. Each
 * test covers one of the orchestrator's contracts: linear execution,
 * hibernate-and-resume, resume across a fresh {@link Workflow} instance
 * (simulating a JVM restart against the same persistent store), retry
 * on transient exceptions, retry-budget exhaustion, fail propagation,
 * and the "skip already-completed steps on resume" guarantee.
 */
class WorkflowTest {

    private InMemoryCheckpointStore freshStore() {
        var store = new InMemoryCheckpointStore();
        store.start();
        return store;
    }

    private String freshCoord() {
        return "coord-" + UUID.randomUUID();
    }

    @Test
    void linearExecutionAdvancesThroughEverySteps() {
        var store = freshStore();
        var calls = new AtomicInteger();
        var workflow = new Workflow<>("linear", freshCoord(),
                List.of(
                        step("a", s -> { calls.incrementAndGet(); return StepOutcome.advance(s + ":a"); }),
                        step("b", s -> { calls.incrementAndGet(); return StepOutcome.advance(s + ":b"); }),
                        step("c", s -> { calls.incrementAndGet(); return StepOutcome.done(s + ":c"); })),
                store);

        var result = workflow.run("start");
        var completed = assertInstanceOf(WorkflowResult.Completed.class, result);
        assertEquals("start:a:b:c", completed.finalState());
        assertEquals(3, calls.get());
    }

    @Test
    void hibernateReturnsImmediatelyAndPersistsState() {
        var store = freshStore();
        var workflow = new Workflow<>("hib", freshCoord(),
                List.of(
                        step("setup", s -> StepOutcome.advance(s + ":setup")),
                        step("wait-for-human",
                                s -> StepOutcome.hibernate(s + ":hibernated"))),
                store);

        var result = workflow.run("init");

        var hib = assertInstanceOf(WorkflowResult.Hibernated.class, result);
        assertEquals("init:setup:hibernated", hib.savedState());
        assertEquals("wait-for-human", hib.lastStepName());
    }

    @Test
    void resumeAfterHibernateContinuesAtNextStep() {
        var store = freshStore();
        var coord = freshCoord();

        // First run hibernates at step 2.
        var first = new Workflow<>("resume-test", coord,
                List.of(
                        step("one", s -> StepOutcome.advance(s + ":1")),
                        step("two", s -> StepOutcome.hibernate(s + ":2")),
                        step("three", s -> StepOutcome.done(s + ":3"))),
                store);
        var hibResult = first.run("");
        assertInstanceOf(WorkflowResult.Hibernated.class, hibResult);

        // Build a fresh Workflow instance pointing at the SAME store + coord.
        // This is what a JVM restart looks like for the orchestrator.
        var stepOneCalls = new AtomicInteger();
        var stepTwoCalls = new AtomicInteger();
        var stepThreeCalls = new AtomicInteger();
        var resumed = new Workflow<>("resume-test", coord,
                List.of(
                        step("one", s -> { stepOneCalls.incrementAndGet(); return StepOutcome.advance(s + ":REDONE"); }),
                        step("two", s -> { stepTwoCalls.incrementAndGet(); return StepOutcome.advance(s + ":REDONE"); }),
                        step("three", s -> { stepThreeCalls.incrementAndGet(); return StepOutcome.done(s + ":done"); })),
                store);
        var result = resumed.run(null);

        var completed = assertInstanceOf(WorkflowResult.Completed.class, result);
        // Step 1 and 2 already done; only step 3 runs on resume.
        assertEquals(0, stepOneCalls.get(), "step one must not re-run after resume");
        assertEquals(0, stepTwoCalls.get(), "step two must not re-run after resume");
        assertEquals(1, stepThreeCalls.get(), "step three should run exactly once");
        // Final state is "::1:2" (from the first run's persisted state) + ":done".
        assertEquals(":1:2:done", completed.finalState());
    }

    @Test
    void freshRunSkipsInitialStateWhenSnapshotExists() {
        var store = freshStore();
        var coord = freshCoord();

        // Plant a snapshot so the first call to run() resumes rather than starts.
        new Workflow<>("seeded", coord,
                List.of(step("seed",
                        s -> StepOutcome.advance(s + ":seeded"))),
                store).run("seed-init");

        // Now run a workflow with the same coordinationId but a different
        // initial state — the orchestrator should ignore the supplied
        // initialState because it found a checkpoint.
        var seeded = new Workflow<>("seeded", coord,
                List.of(
                        step("seed", s -> StepOutcome.advance(s + ":REDONE")),
                        step("next", s -> StepOutcome.done(s + ":next"))),
                store);
        var result = seeded.run("ignored-init");
        var completed = assertInstanceOf(WorkflowResult.Completed.class, result);
        // Prior run's state was "seed-init:seeded"; step 'next' appended ':next'.
        assertEquals("seed-init:seeded:next", completed.finalState());
    }

    @Test
    void retrySucceedsWithinBudget() {
        var store = freshStore();
        var attempts = new AtomicInteger();
        var workflow = new Workflow<>("retry-ok", freshCoord(),
                List.of(retryingStep("flaky", 2, Duration.ofMillis(5), s -> {
                    var n = attempts.incrementAndGet();
                    if (n < 3) throw new RuntimeException("transient #" + n);
                    return StepOutcome.done(s + ":ok-after-" + n);
                })),
                store);

        var result = workflow.run("x");
        var completed = assertInstanceOf(WorkflowResult.Completed.class, result);
        assertEquals("x:ok-after-3", completed.finalState());
        assertEquals(3, attempts.get());
    }

    @Test
    void retryExhaustedReturnsFailed() {
        var store = freshStore();
        var attempts = new AtomicInteger();
        var workflow = new Workflow<>("retry-out", freshCoord(),
                List.of(retryingStep("doomed", 2, Duration.ofMillis(5), s -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("permanent");
                })),
                store);

        var result = workflow.run("x");
        var failed = assertInstanceOf(WorkflowResult.Failed.class, result);
        assertEquals("doomed", failed.lastStepName());
        assertTrue(failed.reason().contains("permanent"),
                "reason should expose the underlying exception, got: " + failed.reason());
        // maxRetries=2 → 3 total attempts (initial + 2 retries).
        assertEquals(3, attempts.get());
    }

    @Test
    void explicitFailIsPropagated() {
        var store = freshStore();
        var workflow = new Workflow<>("explicit-fail", freshCoord(),
                List.of(
                        step("first", s -> StepOutcome.advance(s + ":1")),
                        step("guard", s -> StepOutcome.fail("validation-rejected"))),
                store);

        var result = workflow.run("");
        var failed = assertInstanceOf(WorkflowResult.Failed.class, result);
        assertEquals("guard", failed.lastStepName());
        assertEquals("validation-rejected", failed.reason());
    }

    @Test
    void emptyStepsRejected() {
        var store = freshStore();
        assertThrows(IllegalArgumentException.class,
                () -> new Workflow<>("empty", freshCoord(), List.of(), store));
    }

    @Test
    void duplicateStepNamesRejected() {
        var store = freshStore();
        assertThrows(IllegalArgumentException.class,
                () -> new Workflow<>("dup", freshCoord(),
                        List.of(
                                step("a", s -> StepOutcome.advance(s)),
                                step("a", s -> StepOutcome.done(s))),
                        store));
    }

    @Test
    void deleteAllSnapshotsRemovesEveryCheckpoint() {
        var store = freshStore();
        var coord = freshCoord();
        var workflow = new Workflow<>("cleanup", coord,
                List.of(
                        step("one", s -> StepOutcome.advance(s + ":1")),
                        step("two", s -> StepOutcome.done(s + ":2"))),
                store);
        workflow.run("init");

        var snapshotsBefore = store.list(org.atmosphere.checkpoint.CheckpointQuery.builder()
                .coordinationId(coord).build());
        assertTrue(snapshotsBefore.size() >= 2,
                "expected at least one snapshot per completed step");

        var deleted = workflow.deleteAllSnapshots();
        assertEquals(snapshotsBefore.size(), deleted);

        var snapshotsAfter = store.list(org.atmosphere.checkpoint.CheckpointQuery.builder()
                .coordinationId(coord).build());
        assertEquals(0, snapshotsAfter.size());
    }

    // --- Helpers -----------------------------------------------------------

    @FunctionalInterface
    interface Body<S> {
        StepOutcome<S> apply(S state) throws Exception;
    }

    private static <S> WorkflowStep<S> step(String name, Body<S> body) {
        return new WorkflowStep<>() {
            @Override public String name() { return name; }
            @Override public StepOutcome<S> execute(S state) throws Exception {
                return body.apply(state);
            }
        };
    }

    private static <S> WorkflowStep<S> retryingStep(String name, int retries,
                                                     Duration delay, Body<S> body) {
        return new WorkflowStep<>() {
            @Override public String name() { return name; }
            @Override public int maxRetries() { return retries; }
            @Override public Duration retryDelay() { return delay; }
            @Override public StepOutcome<S> execute(S state) throws Exception {
                return body.apply(state);
            }
        };
    }
}
