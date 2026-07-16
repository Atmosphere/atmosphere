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
package org.atmosphere.checkpoint.temporal;

import io.temporal.activity.Activity;
import io.temporal.testing.TestWorkflowEnvironment;
import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.workflow.DurableExecutionProvider;
import org.atmosphere.checkpoint.workflow.InMemoryDurableExecutionProvider;
import org.atmosphere.checkpoint.workflow.StepOutcome;
import org.atmosphere.checkpoint.workflow.Workflow;
import org.atmosphere.checkpoint.workflow.WorkflowResult;
import org.atmosphere.checkpoint.workflow.WorkflowStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code Workflow.run()} end-to-end through the Temporal adapter on
 * the Temporal test service, pinning engine-parity with the in-tree step
 * engine: identical results, identical snapshot trail, identical
 * hibernate/resume and retry semantics.
 */
class TemporalDurableExecutionProviderTest {

    private static final String TASK_QUEUE = "atmosphere-test-queue";

    private TestWorkflowEnvironment env;

    @BeforeEach
    void startTemporalTestService() {
        env = TestWorkflowEnvironment.newInstance();
        var worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(AtmosphereTemporalWorkflowImpl.class);
        worker.registerActivitiesImplementations(new StepExecutionActivitiesImpl());
        env.start();
        TemporalRuntime.installForTesting(env.getWorkflowClient(), TASK_QUEUE);
    }

    @AfterEach
    void stopTemporalTestService() {
        TemporalRuntime.reset();
        env.close();
    }

    private interface Body {
        StepOutcome<String> apply(String state) throws Exception;
    }

    private static WorkflowStep<String> step(String name, Body body) {
        return step(name, 0, body);
    }

    private static WorkflowStep<String> step(String name, int maxRetries, Body body) {
        return new WorkflowStep<>() {
            @Override public String name() {
                return name;
            }

            @Override public StepOutcome<String> execute(String state) throws Exception {
                return body.apply(state);
            }

            @Override public int maxRetries() {
                return maxRetries;
            }

            @Override public Duration retryDelay() {
                return Duration.ofMillis(10);
            }
        };
    }

    private static InMemoryCheckpointStore newStore() {
        var store = new InMemoryCheckpointStore();
        store.start();
        return store;
    }

    @Test
    void resolveSelectsTheTemporalProviderWhenConnected() {
        var provider = DurableExecutionProvider.resolve();
        assertEquals("temporal", provider.name(),
                "with a reachable Temporal backend, resolve() must prefer it over the in-tree reference");
    }

    @Test
    void runExecutesStepsInsideTemporalActivities() {
        var ranInActivity = new AtomicBoolean();
        var workflow = new Workflow<>("t-linear", "coord-t-linear", List.of(
                step("ingest", s -> {
                    Activity.getExecutionContext(); // throws outside a Temporal activity
                    ranInActivity.set(true);
                    return StepOutcome.advance(s + "-ingested");
                }),
                step("publish", s -> StepOutcome.done(s + "-published"))), newStore());

        var result = workflow.run("doc");

        var completed = assertInstanceOf(WorkflowResult.Completed.class, result);
        assertEquals("doc-ingested-published", completed.finalState());
        assertTrue(ranInActivity.get(),
                "steps must execute inside a Temporal activity, not on the in-tree engine");
    }

    @Test
    void hibernateReturnsImmediatelyAndResumeSkipsCompletedSteps() {
        var store = newStore();
        var ingestRuns = new AtomicInteger();
        var publishRuns = new AtomicInteger();
        List<WorkflowStep<String>> steps = List.of(
                step("ingest", s -> {
                    ingestRuns.incrementAndGet();
                    return StepOutcome.advance(s + ":ingested");
                }),
                step("review", StepOutcome::hibernate),
                step("publish", s -> {
                    publishRuns.incrementAndGet();
                    return StepOutcome.done(s + ":published");
                }));

        var first = new Workflow<>("t-hitl", "coord-t-hitl", steps, store).run("doc-42");
        var hibernated = assertInstanceOf(WorkflowResult.Hibernated.class, first);
        assertEquals("review", hibernated.lastStepName());
        assertEquals("doc-42:ingested", hibernated.savedState());
        assertEquals(0, publishRuns.get(), "publish must not run before the resume");

        var second = new Workflow<>("t-hitl", "coord-t-hitl", steps, store).run(null);
        var completed = assertInstanceOf(WorkflowResult.Completed.class, second);
        assertEquals("doc-42:ingested:published", completed.finalState());
        assertEquals(1, ingestRuns.get(), "resume must not re-run completed steps");
        assertEquals(1, publishRuns.get());
    }

    @Test
    void temporalHonorsThePerStepRetryBudget() {
        var attempts = new AtomicInteger();
        var workflow = new Workflow<>("t-retry", "coord-t-retry", List.of(
                step("flaky", 2, s -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("transient");
                    }
                    return StepOutcome.done(s + "-eventually");
                })), newStore());

        var result = workflow.run("doc");

        var completed = assertInstanceOf(WorkflowResult.Completed.class, result);
        assertEquals("doc-eventually", completed.finalState());
        assertEquals(3, attempts.get(), "maxRetries=2 must allow exactly three attempts");
    }

    @Test
    void exhaustedRetriesSurfaceAsFailedNotAsAThrow() {
        var attempts = new AtomicInteger();
        var workflow = new Workflow<>("t-exhaust", "coord-t-exhaust", List.of(
                step("doomed", 1, s -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("permanent");
                })), newStore());

        var result = workflow.run("doc");

        var failed = assertInstanceOf(WorkflowResult.Failed.class, result);
        assertEquals("doomed", failed.lastStepName());
        assertTrue(failed.reason().startsWith("unhandled exception:"),
                "exhausted retries must map to the in-tree engine's failure shape, got: " + failed.reason());
        assertEquals(2, attempts.get(), "maxRetries=1 must allow exactly two attempts");
    }

    @Test
    void explicitFailPropagatesTheReasonVerbatim() {
        var workflow = new Workflow<>("t-fail", "coord-t-fail", List.of(
                step("gate", s -> StepOutcome.fail("policy denied"))), newStore());

        var result = workflow.run("doc");

        var failed = assertInstanceOf(WorkflowResult.Failed.class, result);
        assertEquals("gate", failed.lastStepName());
        assertEquals("policy denied", failed.reason());
    }

    @Test
    void snapshotTrailMatchesTheInTreeEngine() {
        List<WorkflowStep<String>> steps = List.of(
                step("ingest", s -> StepOutcome.advance(s + ":ingested")),
                step("review", StepOutcome::hibernate),
                step("publish", s -> StepOutcome.done(s + ":published")));

        var temporalStore = newStore();
        var viaTemporal = new Workflow<>("t-parity", "coord-parity", steps, temporalStore);
        viaTemporal.run("doc");
        viaTemporal.run(null);

        var localStore = newStore();
        var viaLocal = new Workflow<>("t-parity", "coord-parity", steps, localStore);
        var local = new InMemoryDurableExecutionProvider();
        local.run(viaLocal, "doc");
        local.run(viaLocal, null);

        assertEquals(trail(localStore), trail(temporalStore),
                "both engines must leave the identical snapshot trail (Mode Parity)");
    }

    private static List<String> trail(InMemoryCheckpointStore store) {
        return store.list(CheckpointQuery.builder().coordinationId("coord-parity").build()).stream()
                .map(s -> s.metadata().getOrDefault(Workflow.META_LAST_STEP, "?")
                        + "/" + s.metadata().getOrDefault(Workflow.META_DONE, "?"))
                .sorted()
                .collect(Collectors.toList());
    }

    @Test
    void unreachableBackendFallsBackToTheInTreeEngine() {
        TemporalRuntime.reset();
        System.setProperty(TemporalRuntime.TARGET_PROPERTY, "127.0.0.1:1");
        System.setProperty(TemporalRuntime.CONNECT_TIMEOUT_PROPERTY, "200");
        try {
            var provider = DurableExecutionProvider.resolve();
            assertEquals("in-memory", provider.name(),
                    "an unreachable Temporal backend must never be selected (Runtime Truth, #5)");

            var ranInActivity = new AtomicBoolean();
            var workflow = new Workflow<>("t-fallback", "coord-t-fallback", List.of(
                    step("only", s -> {
                        try {
                            Activity.getExecutionContext();
                            ranInActivity.set(true);
                        } catch (RuntimeException expectedOutsideActivity) {
                            // not running inside a Temporal activity — expected
                        }
                        return StepOutcome.done(s + "-locally");
                    })), newStore());

            var result = workflow.run("doc");
            var completed = assertInstanceOf(WorkflowResult.Completed.class, result);
            assertEquals("doc-locally", completed.finalState());
            assertFalse(ranInActivity.get(), "fallback must run on the in-tree engine");
        } finally {
            System.clearProperty(TemporalRuntime.TARGET_PROPERTY);
            System.clearProperty(TemporalRuntime.CONNECT_TIMEOUT_PROPERTY);
            TemporalRuntime.reset();
        }
    }
}
