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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchExecutorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    static final class StubRuntime implements AgentRuntime {

        private final BiConsumer<AgentExecutionContext, StreamingSession> behavior;

        StubRuntime(BiConsumer<AgentExecutionContext, StreamingSession> behavior) {
            this.behavior = behavior;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            behavior.accept(context, session);
        }
    }

    private static AiPipeline echoPipeline() {
        return new AiPipeline(new StubRuntime((context, session) -> {
            session.send("echo:" + context.message());
            session.complete();
        }), "sys", "stub-model", null, null, List.of(), List.of(), null);
    }

    private static List<BatchExecutor.ItemRequest> items(String... inputs) {
        var list = new java.util.ArrayList<BatchExecutor.ItemRequest>(inputs.length);
        for (var input : inputs) {
            list.add(new BatchExecutor.ItemRequest("", input));
        }
        return list;
    }

    @Test
    void restartRecoveryFailsJobsLeftInFlightByAPreviousProcess(@TempDir Path tempDir)
            throws Exception {
        var db = tempDir.resolve("batch.db");
        var now = java.time.Instant.now();
        // Simulate the previous process: a RUNNING job with PENDING items,
        // abandoned without a terminal transition (crash), then closed.
        try (var crashed = new SqliteBatchJobStore(db, 10)) {
            crashed.createJob(
                    new BatchJob("batch-crashed", "demo", "", BatchJob.Status.QUEUED,
                            now, now, 2, 0, 0, 0, ""),
                    List.of(new BatchItem(0, "a", "one", BatchItem.Status.PENDING, "", ""),
                            new BatchItem(1, "b", "two", BatchItem.Status.PENDING, "", "")));
            assertTrue(crashed.markRunning("batch-crashed"));
        }

        // The next process opens a store over the same file; the executor's
        // recovery sweep must leave the job terminal and pollable.
        var store = new SqliteBatchJobStore(db, 10);
        try (var executor = new BatchExecutor(store, 4, 100, 2, TIMEOUT)) {
            var recovered = store.job("batch-crashed").orElseThrow();
            assertEquals(BatchJob.Status.FAILED, recovered.status());
            assertEquals(BatchExecutor.RESTART_ERROR, recovered.error());
            assertEquals(0, recovered.pendingItems());
            for (var item : store.items("batch-crashed")) {
                assertEquals(BatchItem.Status.FAILED, item.status());
                assertEquals(BatchExecutor.RESTART_ERROR, item.error());
            }
            assertEquals(0, store.countOpen());

            // And the recovered file still serves new jobs end to end.
            var job = executor.submit("demo", echoPipeline(), null, items("hello"), "test");
            var done = executor.awaitTerminal(job.id(), Duration.ofSeconds(10)).orElseThrow();
            assertEquals(BatchJob.Status.COMPLETED, done.status());
            assertEquals("echo:hello", store.items(job.id()).get(0).output());
        } finally {
            store.close();
        }
    }

    @Test
    void storeFailureDuringARunLeavesTheJobTerminalFailed() throws Exception {
        var failing = new FailingCompleteItemStore(new InMemoryBatchJobStore(10));
        try (var executor = new BatchExecutor(failing, 4, 100, 2, TIMEOUT)) {
            var job = executor.submit("demo", echoPipeline(), null, items("one"), "test");
            var done = executor.awaitTerminal(job.id(), Duration.ofSeconds(10)).orElseThrow();
            assertEquals(BatchJob.Status.FAILED, done.status());
            assertEquals("job failed with an internal error", done.error());
            // The sweep still terminalized the item (Invariant #2).
            for (var item : failing.items(job.id())) {
                assertTrue(item.status().terminal());
            }
        }
    }

    @Test
    void perItemConversationKeysAreClearedFromMemory() throws Exception {
        var memory = new InMemoryConversationMemory(20);
        var pipeline = new AiPipeline(new StubRuntime((context, session) -> {
            session.send("ok");
            session.complete();
        }), "sys", "stub-model", memory, null, List.of(), List.of(), null);
        var store = new InMemoryBatchJobStore(10);
        try (var executor = new BatchExecutor(store, 4, 100, 2, TIMEOUT)) {
            var job = executor.submit("demo", pipeline, memory, items("one", "two"), "test");
            var done = executor.awaitTerminal(job.id(), Duration.ofSeconds(10)).orElseThrow();
            assertEquals(BatchJob.Status.COMPLETED, done.status());
            // Batch traffic must not accumulate per-item conversations
            // (Invariant #3 — mirrors the OpenAI surface's per-request keys).
            for (var item : store.items(job.id())) {
                assertTrue(memory.getHistory("batch:" + job.id() + ":" + item.index()).isEmpty(),
                        "per-item conversation key must be cleared");
            }
        } finally {
            store.close();
        }
    }

    @Test
    void submitValidatesBoundsAndClosedState() {
        var store = new InMemoryBatchJobStore(10);
        var executor = new BatchExecutor(store, 1, 2, 2, TIMEOUT);
        assertThrows(IllegalArgumentException.class,
                () -> executor.submit("demo", echoPipeline(), null, List.of(), "test"));
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> executor.submit("demo", echoPipeline(), null,
                        items("a", "b", "c"), "test"));
        executor.close();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> executor.submit("demo", echoPipeline(), null, items("a"), "test"));
        assertEquals(Optional.empty(), executor.cancel("batch-unknown"));
        store.close();
    }

    /** Delegating store whose {@code completeItem} always fails. */
    private static final class FailingCompleteItemStore implements BatchJobStore {

        private final BatchJobStore delegate;

        FailingCompleteItemStore(BatchJobStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void createJob(BatchJob job, List<BatchItem> items) {
            delegate.createJob(job, items);
        }

        @Override
        public Optional<BatchJob> job(String id) {
            return delegate.job(id);
        }

        @Override
        public List<BatchJob> jobs(int limit) {
            return delegate.jobs(limit);
        }

        @Override
        public List<BatchItem> items(String jobId) {
            return delegate.items(jobId);
        }

        @Override
        public boolean markRunning(String jobId) {
            return delegate.markRunning(jobId);
        }

        @Override
        public boolean completeItem(String jobId, int index, BatchItem.Status status,
                                    String output, String error) {
            throw new IllegalStateException("simulated store failure");
        }

        @Override
        public boolean finishJob(String jobId, BatchJob.Status status, String error) {
            return delegate.finishJob(jobId, status, error);
        }

        @Override
        public int countOpen() {
            return delegate.countOpen();
        }

        @Override
        public int failInFlight(String error) {
            return delegate.failInFlight(error);
        }

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
