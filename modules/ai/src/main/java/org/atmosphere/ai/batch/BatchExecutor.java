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
package org.atmosphere.ai.batch;

import org.atmosphere.ai.AiBudgetExceededException;
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes {@link BatchJob}s: every item dispatches through
 * {@link AiPipeline#execute(String, String, StreamingSession)} — the same
 * entry the OpenAI-compatible serving, channel, A2A, and AG-UI surfaces use —
 * so guardrails, governance policies, budgets, cost accounting, and metrics
 * apply to each batch item exactly as to an interactive turn (Correctness
 * Invariant #7, Mode Parity).
 *
 * <p>Correctness posture:</p>
 * <ul>
 *   <li><strong>Backpressure</strong> — submissions past the open-job or
 *       per-job item bound are rejected with
 *       {@link RejectedExecutionException} (the HTTP surface maps that to a
 *       429); item execution is gated by one fair semaphore shared across
 *       jobs over a virtual-thread-per-task executor (the
 *       {@code EvalRunner} convention).</li>
 *   <li><strong>Terminal paths</strong> — every item ends terminal exactly
 *       once (the store's {@code PENDING → terminal} transition is the CAS),
 *       and the job runner's {@code finally}-adjacent catch guarantees a
 *       terminal job status on success, per-item failure, job failure,
 *       cancel, and shutdown alike. Jobs left in flight by a previous
 *       process are marked {@code FAILED("interrupted by server restart")}
 *       by the recovery sweep at construction — in-flight work is never
 *       resumed (an LLM dispatch cannot be resumed mid-call), it is failed
 *       with a clear status (Invariant #2).</li>
 *   <li><strong>Ownership</strong> — the executor creates and shuts down its
 *       own item executor and job-runner threads; it never closes the store
 *       it was handed (Invariant #1 — the creator does).</li>
 * </ul>
 */
public final class BatchExecutor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BatchExecutor.class);

    /** Error recorded on jobs recovered after a process restart. */
    static final String RESTART_ERROR = "interrupted by server restart";

    /** Error recorded on jobs interrupted by an orderly shutdown. */
    static final String SHUTDOWN_ERROR = "interrupted by shutdown";

    private static final long DRIVER_GRACE_MS = 500;
    private static final long SHUTDOWN_WAIT_MS = 2_000;
    private static final long CANCEL_WAIT_MS = 5_000;

    /**
     * One requested batch item: the caller's correlation id plus the user
     * message to dispatch.
     */
    public record ItemRequest(String customId, String input) {

        public ItemRequest {
            customId = customId != null ? customId : "";
            Objects.requireNonNull(input, "input");
            if (input.isBlank()) {
                throw new IllegalArgumentException("item input must not be blank");
            }
        }
    }

    private final BatchJobStore store;
    private final int maxOpenJobs;
    private final int maxItemsPerJob;
    private final int itemConcurrency;
    private final Duration defaultItemTimeout;
    private final Semaphore itemGate;
    private final ExecutorService itemExecutor;
    private final ConcurrentHashMap<String, JobRun> active = new ConcurrentHashMap<>();
    private final ReentrantLock submitLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    private static final class JobRun {
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        volatile Thread runner;
    }

    public BatchExecutor(BatchJobStore store, int maxOpenJobs, int maxItemsPerJob,
                         int itemConcurrency, Duration defaultItemTimeout) {
        this.store = Objects.requireNonNull(store, "store");
        if (maxOpenJobs <= 0 || maxItemsPerJob <= 0 || itemConcurrency <= 0) {
            throw new IllegalArgumentException(
                    "maxOpenJobs, maxItemsPerJob and itemConcurrency must be > 0");
        }
        if (defaultItemTimeout == null || defaultItemTimeout.isZero()
                || defaultItemTimeout.isNegative()) {
            throw new IllegalArgumentException("defaultItemTimeout must be positive");
        }
        this.maxOpenJobs = maxOpenJobs;
        this.maxItemsPerJob = maxItemsPerJob;
        this.itemConcurrency = itemConcurrency;
        this.defaultItemTimeout = defaultItemTimeout;
        this.itemGate = new Semaphore(itemConcurrency, true);
        this.itemExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ai-batch-item-", 0).factory());
        // Restart recovery: a durable store may still hold jobs a previous
        // process left QUEUED / RUNNING. They can never be resumed, so they
        // are failed with a clear, pollable status (Invariant #2).
        var recovered = store.failInFlight(RESTART_ERROR);
        if (recovered > 0) {
            logger.warn("Batch store '{}' held {} job(s) left in flight by a previous run — "
                    + "marked failed ({})", store.name(), recovered, RESTART_ERROR);
        }
    }

    /** The store backing this executor. Callers must never close it (Invariant #1). */
    public BatchJobStore store() {
        return store;
    }

    /** The configured cap on concurrently executing items. */
    public int itemConcurrency() {
        return itemConcurrency;
    }

    /**
     * Submit a batch job with the default per-item timeout.
     *
     * @see #submit(String, AiPipeline, AiConversationMemory, List, String, Duration)
     */
    public BatchJob submit(String agent, AiPipeline pipeline, AiConversationMemory memory,
                           List<ItemRequest> items, String submitter) {
        return submit(agent, pipeline, memory, items, submitter, null);
    }

    /**
     * Persist and start a batch job. Items execute on virtual threads behind
     * the shared fairness gate; the returned snapshot is {@code QUEUED} and
     * the job is pollable through {@link #store()} immediately.
     *
     * @param agent       display name recorded on the job
     * @param pipeline    the governed pipeline every item dispatches through
     * @param memory      the pipeline's conversation memory, or {@code null};
     *                    items dispatch under unique per-item conversation
     *                    keys that are cleared afterwards, so batch traffic
     *                    never accumulates conversation state (Invariant #3 —
     *                    the OpenAI-compatible surface's per-request-key rule)
     * @param items       the batch items (1..maxItemsPerJob)
     * @param submitter   submitter label for observability; may be {@code null}
     * @param itemTimeout per-item wall-clock bound, or {@code null} for the
     *                    executor default
     * @throws RejectedExecutionException when the executor is closed, the
     *                                    open-job cap is reached, or the item
     *                                    count exceeds the per-job bound
     *                                    (Invariant #3 — mapped to 429 by the
     *                                    HTTP surface)
     * @throws IllegalArgumentException   when {@code items} is empty
     */
    public BatchJob submit(String agent, AiPipeline pipeline, AiConversationMemory memory,
                           List<ItemRequest> items, String submitter, Duration itemTimeout) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a batch job requires at least one item");
        }
        if (closed.get()) {
            throw new RejectedExecutionException("the batch executor is closed");
        }
        if (items.size() > maxItemsPerJob) {
            throw new RejectedExecutionException("batch has " + items.size()
                    + " items, exceeding the per-job limit of " + maxItemsPerJob);
        }
        submitLock.lock();
        try {
            if (store.countOpen() >= maxOpenJobs) {
                throw new RejectedExecutionException("the server is already handling the maximum "
                        + "of " + maxOpenJobs + " open batch jobs");
            }
            var id = "batch-" + UUID.randomUUID().toString().replace("-", "");
            var now = Instant.now();
            var itemRows = new ArrayList<BatchItem>(items.size());
            for (int i = 0; i < items.size(); i++) {
                var request = items.get(i);
                var customId = request.customId().isBlank()
                        ? Integer.toString(i) : request.customId();
                itemRows.add(new BatchItem(i, customId, request.input(),
                        BatchItem.Status.PENDING, "", ""));
            }
            var job = new BatchJob(id, agent, submitter, BatchJob.Status.QUEUED, now, now,
                    itemRows.size(), 0, 0, 0, "");
            store.createJob(job, itemRows);
            var run = new JobRun();
            active.put(id, run);
            var timeout = itemTimeout != null ? itemTimeout : defaultItemTimeout;
            var runner = Thread.ofVirtual().name("ai-batch-job-" + id)
                    .unstarted(() -> runJob(id, pipeline, memory, itemRows, run, timeout));
            run.runner = runner;
            runner.start();
            return job;
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Request cancellation. In-flight items are interrupted and unstarted
     * items are swept to {@code CANCELLED}; the job's terminal transition is
     * performed by its runner (waited on here for a bounded interval, so the
     * returned snapshot is terminal in all but pathological cases).
     *
     * @return the job snapshot after the cancel took effect, or empty when
     *         the id is unknown
     */
    public Optional<BatchJob> cancel(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        var current = store.job(jobId);
        if (current.isEmpty() || current.get().status().terminal()) {
            return current;
        }
        var run = active.get(jobId);
        if (run == null) {
            // No runner in this process (only possible for a store shared in
            // unexpected ways) — the store transition is still ours to make so
            // the job cannot stick at RUNNING forever (Invariant #2).
            store.finishJob(jobId, BatchJob.Status.CANCELLED, "");
            return store.job(jobId);
        }
        run.cancelRequested.set(true);
        var runner = run.runner;
        if (runner != null) {
            runner.interrupt();
        }
        try {
            if (!run.done.await(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("Batch job {} did not reach a terminal state within {}ms of "
                        + "cancellation; it will finish asynchronously", jobId, CANCEL_WAIT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return store.job(jobId);
    }

    /**
     * Wait until the job reaches a terminal state (or the timeout elapses)
     * and return its latest snapshot. Callers must check
     * {@link BatchJob.Status#terminal()} on the result — a timeout returns
     * the current, possibly non-terminal snapshot.
     */
    public Optional<BatchJob> awaitTerminal(String jobId, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(jobId, "jobId");
        var run = active.get(jobId);
        if (run != null) {
            run.done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return store.job(jobId);
    }

    /**
     * Stop the executor: interrupt every job runner, shut down the item
     * executor, and fail whatever is still open with a clear status. Never
     * closes the store (Invariant #1). Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (var run : active.values()) {
            var runner = run.runner;
            if (runner != null) {
                runner.interrupt();
            }
        }
        itemExecutor.shutdownNow();
        for (var run : active.values()) {
            try {
                if (!run.done.await(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    logger.warn("A batch job runner ignored interruption for {}ms during "
                            + "shutdown; abandoning its virtual thread", SHUTDOWN_WAIT_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            if (!itemExecutor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("Batch item workers did not terminate within {}ms of shutdown",
                        SHUTDOWN_WAIT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Belt and braces: a runner that could not persist its terminal
        // transition (store hiccup mid-shutdown) must not strand a job at
        // RUNNING (Invariant #2).
        try {
            store.failInFlight(SHUTDOWN_ERROR);
        } catch (RuntimeException e) {
            logger.warn("Failed to fail in-flight batch jobs during shutdown: {}", e.toString());
        }
    }

    // ── Job runner ─────────────────────────────────────────────────────────

    private void runJob(String jobId, AiPipeline pipeline, AiConversationMemory memory,
                        List<BatchItem> items, JobRun run, Duration itemTimeout) {
        try {
            if (!store.markRunning(jobId)) {
                // A concurrent cancel / recovery sweep already transitioned
                // the job; its item sweep is authoritative — nothing to run.
                return;
            }
            var interrupted = driveItems(jobId, pipeline, memory, items, run, itemTimeout);
            if (run.cancelRequested.get()) {
                store.finishJob(jobId, BatchJob.Status.CANCELLED, "");
            } else if (interrupted) {
                store.finishJob(jobId, BatchJob.Status.FAILED, SHUTDOWN_ERROR);
            } else {
                store.finishJob(jobId, BatchJob.Status.COMPLETED, "");
            }
        } catch (RuntimeException e) {
            logger.error("Batch job {} failed", jobId, e);
            try {
                store.finishJob(jobId, BatchJob.Status.FAILED,
                        "job failed with an internal error");
            } catch (RuntimeException persistFailure) {
                logger.error("Batch job {} could not persist its failure", jobId, persistFailure);
            }
        } finally {
            active.remove(jobId);
            run.done.countDown();
        }
    }

    /**
     * Dispatch every item and wait for the outcomes. Returns whether the
     * driver was interrupted (shutdown); cancellation is observed through
     * {@code run.cancelRequested}.
     */
    private boolean driveItems(String jobId, AiPipeline pipeline, AiConversationMemory memory,
                               List<BatchItem> items, JobRun run, Duration itemTimeout) {
        var futures = new ArrayList<Future<?>>(items.size());
        for (var item : items) {
            futures.add(itemExecutor.submit(() -> {
                itemGate.acquire();
                try {
                    runItem(jobId, item, pipeline, memory, itemTimeout, run);
                } finally {
                    itemGate.release();
                }
                return null;
            }));
        }
        // Overall deadline: enough for every fairness-gate batch plus grace.
        // The in-item session await enforces the precise per-item bound; this
        // driver-side net catches a pipeline that blocks before dispatch
        // (same construction as EvalRunner).
        var batches = (items.size() + itemConcurrency - 1) / itemConcurrency + 1;
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                itemTimeout.toMillis() * batches + DRIVER_GRACE_MS);
        var interrupted = false;
        for (int i = 0; i < futures.size(); i++) {
            var future = futures.get(i);
            if (interrupted || run.cancelRequested.get()) {
                future.cancel(true);
                // The item stays PENDING here; the terminal sweep in
                // finishJob records it as cancelled / failed (Invariant #2).
                continue;
            }
            try {
                var remaining = Math.max(1, deadline - System.nanoTime());
                future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                store.completeItem(jobId, i, BatchItem.Status.FAILED, "",
                        "timeout: item did not finish within the job deadline");
            } catch (ExecutionException ee) {
                logger.warn("Batch job {} item {} worker failed", jobId, i, ee.getCause());
                store.completeItem(jobId, i, BatchItem.Status.FAILED, "",
                        "item failed with an internal error");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                interrupted = true;
                future.cancel(true);
            }
        }
        return interrupted && !run.cancelRequested.get();
    }

    private void runItem(String jobId, BatchItem item, AiPipeline pipeline,
                         AiConversationMemory memory, Duration timeout, JobRun run) {
        if (run.cancelRequested.get()) {
            return; // the terminal sweep will record the cancellation
        }
        var conversationKey = conversationKey(jobId, item.index());
        var session = new CollectingItemSession();
        String failure = null;
        try {
            try {
                pipeline.execute(conversationKey, item.input(), session);
            } catch (RuntimeException e) {
                logger.warn("Batch job {} item {} dispatch failed", jobId, item.index(), e);
                failure = "item failed with an internal error";
            }
            if (failure == null) {
                try {
                    if (!session.await(timeout)) {
                        session.abandon();
                        failure = "timeout: item did not complete within "
                                + timeout.toMillis() + "ms";
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    session.abandon();
                    store.completeItem(jobId, item.index(), BatchItem.Status.CANCELLED, "",
                            "cancelled");
                    return;
                }
                if (failure == null) {
                    var error = session.failure();
                    if (error != null) {
                        failure = describeFailure(jobId, item.index(), error);
                    }
                }
            }
            if (failure == null) {
                store.completeItem(jobId, item.index(), BatchItem.Status.SUCCEEDED,
                        session.text(), "");
            } else {
                store.completeItem(jobId, item.index(), BatchItem.Status.FAILED, "", failure);
            }
        } finally {
            // The per-item conversation key must never accumulate in the
            // pipeline's memory — same rule as the OpenAI-compatible
            // surface's per-request keys (Invariant #3).
            if (memory != null) {
                memory.clear(conversationKey);
            }
        }
    }

    /**
     * Map a pipeline-reported failure to the per-item error text. Guardrail
     * and governance denials ({@link SecurityException}) and budget trips
     * ({@link AiBudgetExceededException}) carry caller-meaningful messages;
     * anything else stays generic with the details in the server log — the
     * same disclosure posture as the OpenAI-compatible surface.
     */
    private static String describeFailure(String jobId, int index, Throwable error) {
        if (error instanceof SecurityException) {
            return error.getMessage() != null ? error.getMessage() : "Request blocked by policy.";
        }
        if (error instanceof AiBudgetExceededException) {
            return error.getMessage() != null
                    ? error.getMessage() : "The configured AI budget was exceeded.";
        }
        logger.warn("Batch job {} item {} failed", jobId, index, error);
        return "item failed with an internal error";
    }

    private static String conversationKey(String jobId, int index) {
        return "batch:" + jobId + ":" + index;
    }

    /**
     * Collecting session for one batch item: buffers the response text,
     * captures the terminal outcome exactly once (CAS), and is awaitable.
     * {@link #abandon()} force-closes it after a timeout so a late-running
     * runtime can no longer mutate an already-recorded item (Invariant #2).
     */
    static final class CollectingItemSession implements StreamingSession {

        private final String id = UUID.randomUUID().toString();
        private final StringBuilder text = new StringBuilder();
        private final ReentrantLock textLock = new ReentrantLock();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public String sessionId() {
            return id;
        }

        @Override
        public void send(String chunk) {
            if (chunk == null || isClosed()) {
                return;
            }
            textLock.lock();
            try {
                text.append(chunk);
            } finally {
                textLock.unlock();
            }
        }

        @Override
        public void sendMetadata(String key, Object value) {
            // Wire-internal metadata has no representation in an item result;
            // cost / usage accounting already happened inside the pipeline's
            // decorator chain.
        }

        @Override
        public void progress(String message) {
            // Progress frames are interactive affordances; batch items only
            // record the terminal outcome.
        }

        @Override
        public void complete() {
            complete(null);
        }

        @Override
        public void complete(String summary) {
            if (closed.compareAndSet(false, true)) {
                if (summary != null && !summary.isBlank()) {
                    textLock.lock();
                    try {
                        text.setLength(0);
                        text.append(summary);
                    } finally {
                        textLock.unlock();
                    }
                }
                done.countDown();
            }
        }

        @Override
        public void error(Throwable t) {
            if (closed.compareAndSet(false, true)) {
                failure.set(t != null ? t : new IllegalStateException("Unknown stream error"));
                done.countDown();
            }
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public boolean hasErrored() {
            return failure.get() != null;
        }

        boolean await(Duration timeout) throws InterruptedException {
            return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** Force-close after a timeout; later runtime writes become no-ops. */
        void abandon() {
            if (closed.compareAndSet(false, true)) {
                failure.compareAndSet(null,
                        new IllegalStateException("Item abandoned after timeout"));
                done.countDown();
            }
        }

        Throwable failure() {
            return failure.get();
        }

        String text() {
            textLock.lock();
            try {
                return text.toString();
            } finally {
                textLock.unlock();
            }
        }
    }
}
