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
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.batch.BatchExecutor;
import org.atmosphere.ai.batch.BatchItem;
import org.atmosphere.ai.batch.BatchJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Replays an {@link EvalDatasetStore} dataset against a live {@link AgentRuntime}
 * and scores every response — the "run the evals" half of the eval flywheel that
 * {@link EvalController} previously lacked: datasets could be curated and runs
 * could be <em>recorded</em>, but nothing in the control plane could <em>produce</em>
 * a run from a dataset.
 *
 * <p>For each {@link EvalCase} the runner executes the case prompt against the
 * target runtime, grades the response with the configured {@link CaseScorer}
 * (e.g. {@link LlmJudgeLiveScorer}), and persists one {@link EvalRun} row per
 * case plus a final aggregate row (id = the run id) summarizing pass rate.
 * Progress is observable through the existing run listing: per-case rows appear
 * as they complete.</p>
 *
 * <p>Correctness posture:</p>
 * <ul>
 *   <li><strong>Backpressure</strong> — case execution is bounded by a small
 *       fair semaphore over a per-run virtual-thread executor; a single run at a
 *       time (a second start is rejected, not queued).</li>
 *   <li><strong>Terminal paths</strong> — every case ends in exactly one row:
 *       scored, failed, timed out, or cancelled (per-case CAS guard), and the
 *       aggregate row is written on success, failure, and cancellation alike.</li>
 *   <li><strong>Ownership</strong> — the runner creates and shuts down its own
 *       executor and async thread; {@link #close()} interrupts an in-flight
 *       asynchronous run.</li>
 * </ul>
 */
public final class EvalRunner implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(EvalRunner.class);

    private static final Pattern SAFE_BASELINE = Pattern.compile("^[A-Za-z0-9_\\-.:]+$");
    private static final long DRIVER_GRACE_MS = 500;
    private static final long SHUTDOWN_WAIT_MS = 2_000;
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();

    /**
     * Grades a single replayed case. Unlike
     * {@link SampledLiveScorer.LiveScorer}, the full {@link EvalCase} is
     * available so reference-aware scorers can compare against the recorded
     * reference answer.
     */
    @FunctionalInterface
    public interface CaseScorer {

        SampledLiveScorer.Verdict score(EvalCase evalCase, String agentResponse);

        /** Adapt a prompt/response {@link SampledLiveScorer.LiveScorer} (reference ignored). */
        static CaseScorer fromLiveScorer(SampledLiveScorer.LiveScorer scorer) {
            Objects.requireNonNull(scorer, "scorer");
            return (evalCase, response) -> scorer.score(evalCase.prompt(), response);
        }
    }

    /**
     * A run request. The {@code baseline} groups the produced rows in the run
     * store (and the dashboard); {@code tag} optionally narrows the dataset to
     * cases carrying that tag; {@code judgeModel} optionally overrides the
     * judge model label handed to the scorer factory.
     */
    public record RunRequest(String baseline, String tag, String judgeModel) {

        public RunRequest {
            baseline = baseline == null || baseline.isBlank() ? "dataset" : baseline;
            if (!SAFE_BASELINE.matcher(baseline).matches()) {
                throw new IllegalArgumentException(
                        "baseline must match [A-Za-z0-9_\\-.:]+ (was: " + baseline + ")");
            }
            tag = tag != null ? tag : "";
            judgeModel = judgeModel != null ? judgeModel : "";
        }
    }

    /** Receipt for an asynchronously started run. */
    public record StartedRun(String runId, String baseline, int totalCases) { }

    /** Outcome of a completed run; also persisted as the aggregate {@link EvalRun} row. */
    public record RunSummary(String runId, String baseline, int totalCases, int passedCases,
                             int failedCases, double passRate, boolean passed, boolean completed) { }

    private final Supplier<AgentRuntime> targetSupplier;
    private final Function<String, CaseScorer> scorerFactory;
    private final EvalDatasetStore datasetStore;
    private final EvalRunStore runStore;
    private final int maxConcurrency;
    private final Duration perCaseTimeout;
    private final double passRateThreshold;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Thread> asyncThread = new AtomicReference<>();
    private volatile Supplier<BatchExecutor> batchExecutorSupplier;

    public EvalRunner(Supplier<AgentRuntime> targetSupplier,
                      Function<String, CaseScorer> scorerFactory,
                      EvalDatasetStore datasetStore, EvalRunStore runStore,
                      int maxConcurrency, Duration perCaseTimeout, double passRateThreshold) {
        this.targetSupplier = Objects.requireNonNull(targetSupplier, "targetSupplier");
        this.scorerFactory = Objects.requireNonNull(scorerFactory, "scorerFactory");
        this.datasetStore = Objects.requireNonNull(datasetStore, "datasetStore");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be > 0");
        }
        if (perCaseTimeout == null || perCaseTimeout.isZero() || perCaseTimeout.isNegative()) {
            throw new IllegalArgumentException("perCaseTimeout must be positive");
        }
        if (passRateThreshold < 0.0 || passRateThreshold > 1.0) {
            throw new IllegalArgumentException("passRateThreshold must be within [0.0, 1.0]");
        }
        this.maxConcurrency = maxConcurrency;
        this.perCaseTimeout = perCaseTimeout;
        this.passRateThreshold = passRateThreshold;
    }

    /**
     * Replay the dataset synchronously and return the aggregate summary.
     *
     * @throws IllegalStateException    when a run is already in progress, the
     *                                  target runtime is unavailable, or the
     *                                  scorer cannot be built
     * @throws IllegalArgumentException when the (tag-filtered) dataset is empty
     */
    public RunSummary run(RunRequest request) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("an eval run is already in progress");
        }
        try {
            return execute(prepare(request));
        } finally {
            running.set(false);
        }
    }

    /**
     * Start a run on a background virtual thread and return immediately.
     * Progress and the final aggregate are observable through the run store
     * (per-case rows land as cases complete; the aggregate row id is the
     * returned {@link StartedRun#runId()}).
     */
    public StartedRun start(RunRequest request) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("an eval run is already in progress");
        }
        Prepared prepared;
        try {
            prepared = prepare(request);
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        var thread = Thread.ofVirtual().name("eval-runner-" + prepared.runId()).unstarted(() -> {
            try {
                execute(prepared);
            } catch (RuntimeException e) {
                logger.warn("Eval run {} failed: {}", prepared.runId(), e.toString());
            } finally {
                running.set(false);
                asyncThread.set(null);
            }
        });
        asyncThread.set(thread);
        thread.start();
        return new StartedRun(prepared.runId(), prepared.baseline(), prepared.cases().size());
    }

    /** Whether a run is currently executing. */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Route dataset replays through the durable batch surface
     * ({@code atmosphere.ai.batch.enabled}): when the supplier yields an
     * executor at run start, the whole dataset is submitted as one batch job
     * (one item per case, the case prompt dispatched through an
     * {@link AiPipeline} over the target runtime) and scored from the
     * persisted per-item results. The supplier is resolved per run — runtime
     * truth, since the batch executor exists only while that surface is
     * enabled and registered; a {@code null} yield falls back to the direct
     * execution path. The runner never closes the supplied executor
     * (Invariant #1 — it did not create it).
     */
    public void setBatchExecutorSupplier(Supplier<BatchExecutor> batchExecutorSupplier) {
        this.batchExecutorSupplier = batchExecutorSupplier;
    }

    /**
     * Interrupt an in-flight asynchronous run. Unrecorded cases receive a
     * {@code cancelled} row and the aggregate row is still written (marked
     * incomplete) before the run thread exits.
     */
    @Override
    public void close() {
        var thread = asyncThread.getAndSet(null);
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(SHUTDOWN_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private record Prepared(String runId, String baseline, String tag, String judgeLabel,
                            AgentRuntime target, CaseScorer scorer, List<EvalCase> cases) { }

    private record CaseExecution(EvalCase evalCase, AtomicBoolean recorded, Future<?> future) { }

    private Prepared prepare(RunRequest request) {
        var target = targetSupplier.get();
        if (target == null || !target.isAvailable()) {
            throw new IllegalStateException("target agent runtime is not available");
        }
        var scorer = scorerFactory.apply(request.judgeModel());
        if (scorer == null) {
            throw new IllegalStateException(
                    "no case scorer available for judge model '" + request.judgeModel() + "'");
        }
        var cases = request.tag().isBlank()
                ? datasetStore.list()
                : datasetStore.list().stream()
                        .filter(c -> c.tags().contains(request.tag())).toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("eval dataset has no cases"
                    + (request.tag().isBlank() ? "" : " tagged '" + request.tag() + "'"));
        }
        var judgeLabel = request.judgeModel().isBlank() ? "judge" : request.judgeModel();
        var runId = request.baseline() + "-" + Instant.now().toEpochMilli()
                + "-" + RUN_SEQUENCE.incrementAndGet();
        return new Prepared(runId, request.baseline(), request.tag(), judgeLabel, target,
                scorer, cases);
    }

    private RunSummary execute(Prepared prepared) {
        var supplier = batchExecutorSupplier;
        var batchExecutor = supplier != null ? supplier.get() : null;
        if (batchExecutor != null) {
            return executeViaBatch(prepared, batchExecutor);
        }
        var total = prepared.cases().size();
        var passedCount = new AtomicInteger();
        var failedCount = new AtomicInteger();
        boolean completed;
        // Not try-with-resources deliberately: ExecutorService.close() waits
        // indefinitely, so a target runtime that ignores interruption would turn
        // a per-case timeout into a hung runner. Bounded shutdown below instead.
        var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("eval-case-", 0).factory());
        try {
            var gate = new Semaphore(maxConcurrency, true);
            var executions = new ArrayList<CaseExecution>(total);
            for (var evalCase : prepared.cases()) {
                var recorded = new AtomicBoolean();
                var future = executor.submit(() -> {
                    gate.acquire();
                    try {
                        scoreCase(prepared, evalCase, recorded, passedCount, failedCount);
                    } finally {
                        gate.release();
                    }
                    return null;
                });
                executions.add(new CaseExecution(evalCase, recorded, future));
            }
            // Overall deadline: enough for every semaphore batch plus grace. The
            // in-task session await enforces the precise per-case bound; this
            // driver-side net catches a target that blocks inside execute().
            var batches = (total + maxConcurrency - 1) / maxConcurrency + 1;
            var deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(perCaseTimeout.toMillis() * batches + DRIVER_GRACE_MS);
            var interrupted = false;
            for (var execution : executions) {
                if (interrupted) {
                    execution.future().cancel(true);
                    recordUnscored(prepared, execution, "cancelled", failedCount);
                    continue;
                }
                try {
                    var remaining = Math.max(1, deadline - System.nanoTime());
                    execution.future().get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException te) {
                    execution.future().cancel(true);
                    recordUnscored(prepared, execution,
                            "timeout: case did not finish within the run deadline", failedCount);
                } catch (ExecutionException ee) {
                    recordUnscored(prepared, execution,
                            "case execution failed: " + ee.getCause(), failedCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    execution.future().cancel(true);
                    recordUnscored(prepared, execution, "cancelled", failedCount);
                }
            }
            completed = !interrupted;
        } finally {
            shutdown(executor, prepared.runId());
        }
        return saveAggregate(prepared, total, passedCount.get(), failedCount.get(), completed);
    }

    /**
     * Replay the dataset through the durable batch surface: one batch job,
     * one item per case, every item dispatched through an {@link AiPipeline}
     * over the target runtime so pipeline-layer semantics apply. Scoring
     * happens after the job reaches a terminal state, from the persisted
     * per-item results. Semantics match the direct path — one row per case,
     * the per-case timeout enforced (by the batch executor), cancellation
     * recorded as failed rows, and the same aggregate row — except that an
     * overall-deadline overrun cancels the job and marks the aggregate
     * incomplete (documented Mode Parity divergence, Invariant #7).
     */
    private RunSummary executeViaBatch(Prepared prepared, BatchExecutor executor) {
        var total = prepared.cases().size();
        var pipeline = new AiPipeline(prepared.target(), "", null, null, null,
                List.of(), List.of(), List.of(), null, null);
        var items = new ArrayList<BatchExecutor.ItemRequest>(total);
        for (var evalCase : prepared.cases()) {
            items.add(new BatchExecutor.ItemRequest(evalCase.id(), evalCase.prompt()));
        }
        BatchJob job;
        try {
            job = executor.submit("eval:" + prepared.baseline(), pipeline, null, items,
                    "eval-runner", perCaseTimeout);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException(
                    "the batch surface rejected the eval dataset: " + e.getMessage(), e);
        }
        var batches = (total + executor.itemConcurrency() - 1) / executor.itemConcurrency() + 1;
        var overall = Duration.ofMillis(perCaseTimeout.toMillis() * batches + DRIVER_GRACE_MS);
        var interrupted = false;
        try {
            var polled = executor.awaitTerminal(job.id(), overall);
            if (polled.isEmpty() || !polled.get().status().terminal()) {
                executor.cancel(job.id());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            interrupted = true;
            executor.cancel(job.id());
        }
        var byId = new HashMap<String, EvalCase>(total);
        for (var evalCase : prepared.cases()) {
            byId.put(evalCase.id(), evalCase);
        }
        int passed = 0;
        int failed = 0;
        for (var item : executor.store().items(job.id())) {
            var evalCase = byId.get(item.customId());
            if (evalCase == null) {
                continue;
            }
            if (recordBatchOutcome(prepared, evalCase, item)) {
                passed++;
            } else {
                failed++;
            }
        }
        var jobCompleted = executor.store().job(job.id())
                .map(j -> j.status() == BatchJob.Status.COMPLETED).orElse(false);
        return saveAggregate(prepared, total, passed, failed, jobCompleted && !interrupted);
    }

    /** Score one persisted batch item into its per-case row; returns whether it passed. */
    private boolean recordBatchOutcome(Prepared prepared, EvalCase evalCase, BatchItem item) {
        var response = "";
        SampledLiveScorer.Verdict verdict = null;
        String failure = null;
        switch (item.status()) {
            case SUCCEEDED -> {
                response = item.output();
                try {
                    verdict = prepared.scorer().score(evalCase, response);
                    if (verdict == null) {
                        failure = "scorer returned no verdict";
                    }
                } catch (RuntimeException re) {
                    failure = "case failed: " + re;
                }
            }
            case FAILED -> failure = item.error().isEmpty() ? "item failed" : item.error();
            case CANCELLED -> failure = "cancelled";
            case PENDING -> failure = "item left pending by the batch surface";
        }
        var casePassed = failure == null && verdict.passed();
        save(new EvalRun(
                prepared.runId() + "." + evalCase.id(), prepared.baseline(), Instant.now(), "",
                evalCase.prompt(),
                failure == null ? response : "",
                failure == null ? verdict.passed() : Boolean.FALSE,
                failure == null ? Map.of("score", verdict.score()) : Map.of(),
                prepared.judgeLabel(), casePassed,
                failure == null ? verdict.notes() : failure));
        return casePassed;
    }

    private void scoreCase(Prepared prepared, EvalCase evalCase, AtomicBoolean recorded,
                           AtomicInteger passedCount, AtomicInteger failedCount) {
        var response = "";
        SampledLiveScorer.Verdict verdict = null;
        String failure = null;
        try {
            response = executeAgent(prepared.target(), evalCase);
            verdict = prepared.scorer().score(evalCase, response);
            if (verdict == null) {
                failure = "scorer returned no verdict";
            }
        } catch (TimeoutException te) {
            failure = te.getMessage();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            failure = "cancelled";
        } catch (RuntimeException re) {
            failure = "case failed: " + re;
        }
        if (!recorded.compareAndSet(false, true)) {
            return; // the driver already recorded a timeout/cancel row for this case
        }
        var casePassed = failure == null && verdict.passed();
        (casePassed ? passedCount : failedCount).incrementAndGet();
        var row = new EvalRun(
                prepared.runId() + "." + evalCase.id(), prepared.baseline(), Instant.now(), "",
                evalCase.prompt(),
                failure == null ? response : "",
                failure == null ? verdict.passed() : Boolean.FALSE,
                failure == null ? Map.of("score", verdict.score()) : Map.of(),
                prepared.judgeLabel(), casePassed,
                failure == null ? verdict.notes() : failure);
        save(row);
    }

    private void recordUnscored(Prepared prepared, CaseExecution execution, String reason,
                                AtomicInteger failedCount) {
        if (!execution.recorded().compareAndSet(false, true)) {
            return; // the case task recorded its own row first
        }
        failedCount.incrementAndGet();
        var evalCase = execution.evalCase();
        save(new EvalRun(prepared.runId() + "." + evalCase.id(), prepared.baseline(),
                Instant.now(), "", evalCase.prompt(), "", Boolean.FALSE, Map.of(),
                prepared.judgeLabel(), false, reason));
    }

    private RunSummary saveAggregate(Prepared prepared, int total, int passedCases,
                                     int failedCases, boolean completed) {
        var passRate = total == 0 ? 0.0 : (double) passedCases / total;
        var passed = completed && passRate >= passRateThreshold;
        var summary = new RunSummary(prepared.runId(), prepared.baseline(), total, passedCases,
                failedCases, passRate, passed, completed);
        var notes = "aggregate: " + passedCases + "/" + total + " passed (threshold="
                + passRateThreshold + (completed ? ")" : ", cancelled before completion)");
        save(new EvalRun(prepared.runId(), prepared.baseline(), Instant.now(), "",
                "aggregate" + (prepared.tag().isBlank() ? "" : " tag=" + prepared.tag()), "",
                passed,
                Map.of("passRate", passRate, "totalCases", (double) total,
                        "passedCases", (double) passedCases, "failedCases", (double) failedCases),
                prepared.judgeLabel(), passed, notes));
        return summary;
    }

    private void save(EvalRun row) {
        try {
            runStore.save(row);
        } catch (RuntimeException e) {
            logger.warn("Failed to persist eval run row {}: {}", row.id(), e.toString());
        }
    }

    private String executeAgent(AgentRuntime target, EvalCase evalCase)
            throws TimeoutException, InterruptedException {
        var session = new CapturingSession();
        target.execute(new AgentExecutionContext(
                evalCase.prompt(), "", null, null, "eval-runner", null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null, null), session);
        if (!session.await(perCaseTimeout)) {
            throw new TimeoutException(
                    "timeout: agent did not complete within " + perCaseTimeout);
        }
        var error = session.error();
        if (error != null) {
            throw new IllegalStateException("agent errored: " + error, error);
        }
        return session.fullText();
    }

    private void shutdown(ExecutorService executor, String runId) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("Eval run {}: some case workers ignored interruption; abandoning "
                        + "their virtual threads after {}ms", runId, SHUTDOWN_WAIT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Minimal text-capturing session mirroring the judge-side capture in LlmJudge. */
    private static final class CapturingSession implements StreamingSession {

        private final StringBuilder text = new StringBuilder();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Throwable error;

        @Override
        public String sessionId() {
            return "eval-runner";
        }

        @Override
        public void send(String chunk) {
            text.append(chunk);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            // Metadata frames carry no answer text; the runner only grades text.
        }

        @Override
        public void progress(String message) {
            // Progress frames are UI affordances; nothing to capture.
        }

        @Override
        public void complete() {
            done.countDown();
        }

        @Override
        public void complete(String summary) {
            if (summary != null) {
                text.setLength(0);
                text.append(summary);
            }
            done.countDown();
        }

        @Override
        public void error(Throwable t) {
            error = t;
            done.countDown();
        }

        @Override
        public boolean isClosed() {
            return done.getCount() == 0;
        }

        @Override
        public void emit(AiEvent event) {
            if (event instanceof AiEvent.TextDelta delta) {
                text.append(delta.text());
            } else if (event instanceof AiEvent.Complete) {
                done.countDown();
            } else if (event instanceof AiEvent.Error) {
                done.countDown();
            }
        }

        boolean await(Duration timeout) throws InterruptedException {
            return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        Throwable error() {
            return error;
        }

        // Reading after await() is safe: the CountDownLatch publishes every
        // append made before complete()/error() (same pattern as LlmJudge).
        String fullText() {
            return text.toString();
        }
    }
}
