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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteBatchJobStoreTest {

    private static BatchJob queued(String id, int totalItems) {
        var now = Instant.now();
        return new BatchJob(id, "demo", "tester", BatchJob.Status.QUEUED, now, now,
                totalItems, 0, 0, 0, "");
    }

    private static List<BatchItem> pendingItems(int count) {
        var items = new java.util.ArrayList<BatchItem>(count);
        for (int i = 0; i < count; i++) {
            items.add(new BatchItem(i, "c" + i, "input-" + i, BatchItem.Status.PENDING, "", ""));
        }
        return items;
    }

    @Test
    void jobsAndItemsRoundTripWithGuardedTransitions(@TempDir Path tempDir) {
        try (var store = new SqliteBatchJobStore(tempDir.resolve("batch.db"), 10)) {
            store.createJob(queued("batch-1", 2), pendingItems(2));
            assertThrows(IllegalStateException.class,
                    () -> store.createJob(queued("batch-1", 1), pendingItems(1)));
            assertEquals(1, store.countOpen());

            assertTrue(store.markRunning("batch-1"));
            assertFalse(store.markRunning("batch-1"), "RUNNING is not QUEUED — guarded");

            assertTrue(store.completeItem("batch-1", 0, BatchItem.Status.SUCCEEDED, "out", ""));
            assertFalse(store.completeItem("batch-1", 0, BatchItem.Status.FAILED, "", "late"),
                    "a terminal item must never transition again");
            assertTrue(store.completeItem("batch-1", 1, BatchItem.Status.FAILED, "", "boom"));

            var running = store.job("batch-1").orElseThrow();
            assertEquals(1, running.succeededItems());
            assertEquals(1, running.failedItems());
            assertEquals(0, running.pendingItems());

            assertTrue(store.finishJob("batch-1", BatchJob.Status.COMPLETED, ""));
            assertFalse(store.finishJob("batch-1", BatchJob.Status.FAILED, "late"),
                    "a terminal job must never transition again");
            assertEquals(0, store.countOpen());

            var items = store.items("batch-1");
            assertEquals("out", items.get(0).output());
            assertEquals("boom", items.get(1).error());
            assertEquals(BatchJob.Status.COMPLETED, store.jobs(10).get(0).status());
        }
    }

    @Test
    void cancelFinishSweepsPendingItemsToCancelled(@TempDir Path tempDir) {
        try (var store = new SqliteBatchJobStore(tempDir.resolve("batch.db"), 10)) {
            store.createJob(queued("batch-1", 3), pendingItems(3));
            store.markRunning("batch-1");
            store.completeItem("batch-1", 0, BatchItem.Status.SUCCEEDED, "out", "");

            assertTrue(store.finishJob("batch-1", BatchJob.Status.CANCELLED, ""));
            var job = store.job("batch-1").orElseThrow();
            assertEquals(BatchJob.Status.CANCELLED, job.status());
            assertEquals(1, job.succeededItems());
            assertEquals(2, job.cancelledItems());
            assertEquals(0, job.pendingItems());
            assertEquals(BatchItem.Status.CANCELLED, store.items("batch-1").get(1).status());
            assertEquals("cancelled", store.items("batch-1").get(1).error());
        }
    }

    @Test
    void terminalJobsBeyondTheRetentionCapAreEvictedWithTheirItems(@TempDir Path tempDir) {
        try (var store = new SqliteBatchJobStore(tempDir.resolve("batch.db"), 2)) {
            for (int i = 1; i <= 3; i++) {
                var id = "batch-" + i;
                store.createJob(queued(id, 1), pendingItems(1));
                store.markRunning(id);
                store.completeItem(id, 0, BatchItem.Status.SUCCEEDED, "out", "");
                store.finishJob(id, BatchJob.Status.COMPLETED, "");
            }
            assertEquals(2, store.jobs(10).size(), "retention cap must bite");
            assertTrue(store.job("batch-1").isEmpty(), "oldest terminal job evicted");
            assertTrue(store.items("batch-1").isEmpty(), "evicted job's items removed too");
            assertTrue(store.job("batch-3").isPresent());
        }
    }

    @Test
    void refusesToOpenADatabaseStampedNewerThanTheCode(@TempDir Path tempDir) throws Exception {
        var db = tempDir.resolve("batch.db");
        try (var store = new SqliteBatchJobStore(db, 10)) {
            store.createJob(queued("batch-1", 1), pendingItems(1));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE atmosphere_schema_version SET version = 99 "
                    + "WHERE component = 'ai_batch_jobs'");
        }
        var refusal = assertThrows(IllegalStateException.class,
                () -> new SqliteBatchJobStore(db, 10));
        assertTrue(refusal.getMessage().contains("refusing to open"),
                "newer-than-code schema must fail closed: " + refusal.getMessage());
    }

    @Test
    void failInFlightTerminalizesEveryOpenJob(@TempDir Path tempDir) {
        try (var store = new SqliteBatchJobStore(tempDir.resolve("batch.db"), 10)) {
            store.createJob(queued("batch-q", 1), pendingItems(1));
            store.createJob(queued("batch-r", 1), pendingItems(1));
            store.markRunning("batch-r");
            store.createJob(queued("batch-done", 1), pendingItems(1));
            store.markRunning("batch-done");
            store.completeItem("batch-done", 0, BatchItem.Status.SUCCEEDED, "out", "");
            store.finishJob("batch-done", BatchJob.Status.COMPLETED, "");

            assertEquals(2, store.failInFlight("interrupted by server restart"));
            assertEquals(0, store.countOpen());
            assertEquals(BatchJob.Status.FAILED, store.job("batch-q").orElseThrow().status());
            assertEquals(BatchJob.Status.FAILED, store.job("batch-r").orElseThrow().status());
            assertEquals(BatchJob.Status.COMPLETED,
                    store.job("batch-done").orElseThrow().status());
            assertEquals("interrupted by server restart",
                    store.items("batch-q").get(0).error());
        }
    }
}
