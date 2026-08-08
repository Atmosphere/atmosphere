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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.batch.BatchExecutor;
import org.atmosphere.ai.batch.BatchJob;
import org.atmosphere.ai.batch.InMemoryBatchJobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eval dataset runner as the batch surface's first production consumer:
 * with a batch executor supplied, {@code POST /api/admin/evals/run} traffic
 * (via {@link EvalRunner#run}) replays the dataset through
 * {@link BatchExecutor} — one job, one item per case — and scores the
 * persisted per-item results.
 */
class EvalRunnerBatchTest {

    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(5);

    private InMemoryBatchJobStore batchStore;
    private BatchExecutor batchExecutor;

    @AfterEach
    void tearDown() {
        if (batchExecutor != null) {
            batchExecutor.close();
        }
        if (batchStore != null) {
            batchStore.close();
        }
    }

    private BatchExecutor batchExecutor() {
        batchStore = new InMemoryBatchJobStore(10);
        batchExecutor = new BatchExecutor(batchStore, 4, 100, 2, CASE_TIMEOUT);
        return batchExecutor;
    }

    private static AgentRuntime echoRuntime(Function<String, String> responder) {
        return new AgentRuntime() {
            @Override public String name() { return "fake-target"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                var response = responder.apply(context.message());
                if (response == null) {
                    session.error(new IllegalStateException("target blew up"));
                    return;
                }
                session.send(response);
                session.complete();
            }
        };
    }

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

    private static EvalCase caseOf(String id, String prompt, String reference) {
        return new EvalCase(id, prompt, reference, "manual", List.of(), Instant.now());
    }

    @Test
    void datasetReplayRunsThroughTheBatchExecutorAndScoresItsResults() {
        var dataset = datasetOf(
                caseOf("c1", "Q-one", "alpha"),
                caseOf("c2", "Q-two", "beta"),
                caseOf("c3", "Q-three", "gamma"));
        var runs = new InMemoryEvalRunStore();
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> switch (prompt) {
                    case "Q-one" -> "the answer is alpha";
                    case "Q-two" -> "the answer is beta";
                    default -> "the answer is delta";
                }),
                model -> referenceContainsScorer(),
                dataset, runs, 2, CASE_TIMEOUT, 0.5);
        var executor = batchExecutor();
        runner.setBatchExecutorSupplier(() -> executor);

        var summary = runner.run(new EvalRunner.RunRequest("batch-smoke", null, null));

        assertEquals(3, summary.totalCases());
        assertEquals(2, summary.passedCases());
        assertEquals(1, summary.failedCases());
        assertTrue(summary.passed());
        assertTrue(summary.completed());

        // One row per case plus the aggregate, exactly like the direct path.
        assertEquals(4, runs.list().size());
        assertTrue(runs.findById(summary.runId() + ".c1").orElseThrow().passed());
        assertTrue(runs.findById(summary.runId()).isPresent());

        // The run really went through the batch machinery: one terminal job,
        // one item per case, outputs persisted in the job store.
        var jobs = batchStore.jobs(10);
        assertEquals(1, jobs.size());
        assertEquals(BatchJob.Status.COMPLETED, jobs.get(0).status());
        assertEquals("eval:batch-smoke", jobs.get(0).agent());
        assertEquals("eval-runner", jobs.get(0).submitter());
        assertEquals(3, jobs.get(0).totalItems());
        assertEquals(3, jobs.get(0).succeededItems());
        // Dataset listing order is store-defined; correlate by custom_id.
        var c1Item = batchStore.items(jobs.get(0).id()).stream()
                .filter(item -> "c1".equals(item.customId())).findFirst().orElseThrow();
        assertEquals("the answer is alpha", c1Item.output());
    }

    @Test
    void failedBatchItemsBecomeFailedCaseRowsWithoutKillingTheRun() {
        var dataset = datasetOf(
                caseOf("ok", "Q-good", "alpha"),
                caseOf("bad", "Q-bad", "beta"));
        var runs = new InMemoryEvalRunStore();
        var runner = new EvalRunner(
                () -> echoRuntime(prompt ->
                        "Q-good".equals(prompt) ? "the answer is alpha" : null),
                model -> referenceContainsScorer(),
                dataset, runs, 2, CASE_TIMEOUT, 1.0);
        var executor = batchExecutor();
        runner.setBatchExecutorSupplier(() -> executor);

        var summary = runner.run(new EvalRunner.RunRequest("batch-fail", null, null));

        assertEquals(2, summary.totalCases());
        assertEquals(1, summary.passedCases());
        assertEquals(1, summary.failedCases());
        assertTrue(summary.completed(), "per-item failure must not kill the run");

        var failedRow = runs.findById(summary.runId() + ".bad").orElseThrow();
        assertEquals(Boolean.FALSE, failedRow.verdict());
        assertEquals("item failed with an internal error", failedRow.notes());
    }

    @Test
    void nullSupplierYieldFallsBackToTheDirectPath() {
        var dataset = datasetOf(caseOf("c1", "Q-one", "alpha"));
        var runs = new InMemoryEvalRunStore();
        var runner = new EvalRunner(
                () -> echoRuntime(prompt -> "the answer is alpha"),
                model -> referenceContainsScorer(),
                dataset, runs, 2, CASE_TIMEOUT, 0.5);
        // Runtime truth: the surface is disabled, the supplier yields null,
        // and the run still completes on the direct path.
        runner.setBatchExecutorSupplier(() -> null);

        var summary = runner.run(new EvalRunner.RunRequest("direct", null, null));
        assertEquals(1, summary.passedCases());
        assertTrue(summary.passed());
    }
}
