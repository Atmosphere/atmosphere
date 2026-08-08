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

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for {@link BatchJob}s and their {@link BatchItem}s.
 * Two implementations ship: {@link InMemoryBatchJobStore} (bounded, does not
 * survive restart) and {@link SqliteBatchJobStore} (durable). All state
 * transitions are guarded — an already-terminal job or item never transitions
 * again, so racing writers (item worker vs. driver timeout vs. cancel) cannot
 * double-record (Correctness Invariant #2).
 *
 * <p>Retention: implementations keep at most the configured number of
 * terminal jobs; the oldest terminal jobs and their items are evicted on
 * every terminal transition (Invariant #3 — the store never grows
 * unbounded).</p>
 */
public interface BatchJobStore extends AutoCloseable {

    /**
     * Persist a new job with its items (all {@code PENDING}).
     *
     * @throws IllegalStateException when the job id already exists
     */
    void createJob(BatchJob job, List<BatchItem> items);

    /** The job with the given id, if present. */
    Optional<BatchJob> job(String id);

    /** Most recently created jobs first, at most {@code limit}. */
    List<BatchJob> jobs(int limit);

    /** The job's items in index order; empty when the job is unknown. */
    List<BatchItem> items(String jobId);

    /**
     * Transition {@code QUEUED → RUNNING}.
     *
     * @return {@code false} when the job was not {@code QUEUED} (a concurrent
     *         cancel / failure already transitioned it)
     */
    boolean markRunning(String jobId);

    /**
     * Transition one {@code PENDING} item to a terminal status and refresh
     * the job's item counters.
     *
     * @return {@code false} when the item was not {@code PENDING} (another
     *         path — driver timeout, cancel sweep — already recorded it)
     */
    boolean completeItem(String jobId, int index, BatchItem.Status status,
                         String output, String error);

    /**
     * Transition a non-terminal job to a terminal status. Any items still
     * {@code PENDING} are swept to a matching terminal status ({@code CANCELLED}
     * for a cancelled job, {@code FAILED} carrying {@code error} otherwise) so
     * a terminal job always has all-terminal items, and the retention bound is
     * enforced afterwards.
     *
     * @return {@code false} when the job was already terminal (or unknown)
     */
    boolean finishJob(String jobId, BatchJob.Status status, String error);

    /** Number of jobs currently {@code QUEUED} or {@code RUNNING}. */
    int countOpen();

    /**
     * Fail every non-terminal job (and sweep its {@code PENDING} items to
     * {@code FAILED}) with the given error — the restart-recovery sweep: a
     * job left in flight by a previous process can never be resumed, so it is
     * moved to a clear, pollable terminal state instead of sticking at
     * {@code RUNNING} forever (Correctness Invariant #2).
     *
     * @return the number of jobs transitioned
     */
    int failInFlight(String error);

    /** Store name for logs / diagnostics. */
    String name();

    @Override
    void close();
}
