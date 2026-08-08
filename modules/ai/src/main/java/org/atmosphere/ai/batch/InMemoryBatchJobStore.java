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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded in-memory {@link BatchJobStore}. Jobs do <em>not</em> survive JVM
 * restart — operators who need durability configure
 * {@code atmosphere.ai.batch.db} and get the {@link SqliteBatchJobStore}
 * instead. Retention matches the SQLite store: at most
 * {@code retainedTerminalJobs} terminal jobs are kept, oldest evicted first
 * (Correctness Invariant #3).
 */
public final class InMemoryBatchJobStore implements BatchJobStore {

    private final int retainedTerminalJobs;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, JobState> jobs = new LinkedHashMap<>();

    private static final class JobState {
        BatchJob job;
        final List<BatchItem> items = new ArrayList<>();
    }

    public InMemoryBatchJobStore(int retainedTerminalJobs) {
        if (retainedTerminalJobs <= 0) {
            throw new IllegalArgumentException("retainedTerminalJobs must be > 0");
        }
        this.retainedTerminalJobs = retainedTerminalJobs;
    }

    @Override
    public void createJob(BatchJob job, List<BatchItem> items) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(items, "items");
        lock.lock();
        try {
            if (jobs.containsKey(job.id())) {
                throw new IllegalStateException("Batch job id " + job.id() + " already exists");
            }
            var state = new JobState();
            state.job = job;
            state.items.addAll(items);
            jobs.put(job.id(), state);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<BatchJob> job(String id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try {
            var state = jobs.get(id);
            return state == null ? Optional.empty() : Optional.of(state.job);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<BatchJob> jobs(int limit) {
        lock.lock();
        try {
            return jobs.values().stream()
                    .map(state -> state.job)
                    .sorted(Comparator.comparing(BatchJob::createdAt).reversed()
                            .thenComparing(BatchJob::id))
                    .limit(Math.max(0, limit))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<BatchItem> items(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        lock.lock();
        try {
            var state = jobs.get(jobId);
            return state == null ? List.of() : List.copyOf(state.items);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean markRunning(String jobId) {
        lock.lock();
        try {
            var state = jobs.get(jobId);
            if (state == null || state.job.status() != BatchJob.Status.QUEUED) {
                return false;
            }
            state.job = withStatus(state.job, BatchJob.Status.RUNNING, state.job.error());
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean completeItem(String jobId, int index, BatchItem.Status status,
                                String output, String error) {
        if (!status.terminal()) {
            throw new IllegalArgumentException("completeItem requires a terminal status");
        }
        lock.lock();
        try {
            var state = jobs.get(jobId);
            if (state == null || index < 0 || index >= state.items.size()) {
                return false;
            }
            var item = state.items.get(index);
            if (item.status().terminal()) {
                return false;
            }
            state.items.set(index,
                    new BatchItem(index, item.customId(), item.input(), status, output, error));
            state.job = withCounts(state.job, state.items, state.job.status(), state.job.error());
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean finishJob(String jobId, BatchJob.Status status, String error) {
        if (!status.terminal()) {
            throw new IllegalArgumentException("finishJob requires a terminal status");
        }
        lock.lock();
        try {
            var state = jobs.get(jobId);
            if (state == null || state.job.status().terminal()) {
                return false;
            }
            sweepPending(state, status, error);
            state.job = withCounts(state.job, state.items, status, error);
            evictTerminalOverCap();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int countOpen() {
        lock.lock();
        try {
            return (int) jobs.values().stream()
                    .filter(state -> !state.job.status().terminal())
                    .count();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int failInFlight(String error) {
        lock.lock();
        try {
            int transitioned = 0;
            for (var state : jobs.values()) {
                if (state.job.status().terminal()) {
                    continue;
                }
                sweepPending(state, BatchJob.Status.FAILED, error);
                state.job = withCounts(state.job, state.items, BatchJob.Status.FAILED, error);
                transitioned++;
            }
            if (transitioned > 0) {
                evictTerminalOverCap();
            }
            return transitioned;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void close() {
        lock.lock();
        try {
            jobs.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Sweep still-pending items to the terminal status implied by the job's. */
    private static void sweepPending(JobState state, BatchJob.Status jobStatus, String error) {
        var itemStatus = jobStatus == BatchJob.Status.CANCELLED
                ? BatchItem.Status.CANCELLED : BatchItem.Status.FAILED;
        var itemError = jobStatus == BatchJob.Status.CANCELLED
                ? "cancelled"
                : (error == null || error.isBlank() ? "job finished before this item completed" : error);
        for (int i = 0; i < state.items.size(); i++) {
            var item = state.items.get(i);
            if (!item.status().terminal()) {
                state.items.set(i, new BatchItem(i, item.customId(), item.input(),
                        itemStatus, "", itemError));
            }
        }
    }

    private void evictTerminalOverCap() {
        var terminal = jobs.values().stream()
                .map(state -> state.job)
                .filter(job -> job.status().terminal())
                .sorted(Comparator.comparing(BatchJob::updatedAt).thenComparing(BatchJob::id))
                .toList();
        var excess = terminal.size() - retainedTerminalJobs;
        for (int i = 0; i < excess; i++) {
            jobs.remove(terminal.get(i).id());
        }
    }

    private static BatchJob withStatus(BatchJob job, BatchJob.Status status, String error) {
        return new BatchJob(job.id(), job.agent(), job.submitter(), status, job.createdAt(),
                Instant.now(), job.totalItems(), job.succeededItems(), job.failedItems(),
                job.cancelledItems(), error);
    }

    private static BatchJob withCounts(BatchJob job, List<BatchItem> items,
                                       BatchJob.Status status, String error) {
        int succeeded = 0;
        int failed = 0;
        int cancelled = 0;
        for (var item : items) {
            switch (item.status()) {
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case CANCELLED -> cancelled++;
                case PENDING -> {
                    // Still in flight; counted via pendingItems().
                }
            }
        }
        return new BatchJob(job.id(), job.agent(), job.submitter(), status, job.createdAt(),
                Instant.now(), job.totalItems(), succeeded, failed, cancelled, error);
    }
}
