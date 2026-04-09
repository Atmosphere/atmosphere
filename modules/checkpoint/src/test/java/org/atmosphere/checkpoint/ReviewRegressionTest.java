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
package org.atmosphere.checkpoint;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for code review findings — checkpoint module.
 */
class ReviewRegressionTest {

    @Test
    void overwriteWithDifferentCoordinationUpdatesIndex() {
        var store = new InMemoryCheckpointStore();

        var id = CheckpointId.random();

        // Save under coordination "c1"
        var v1 = WorkflowSnapshot.builder()
                .id(id).coordinationId("c1").state("v1").createdAt(Instant.now()).build();
        store.save(v1);

        assertEquals(1, store.list(CheckpointQuery.forCoordination("c1")).size());

        // Overwrite same ID with different coordination "c2"
        var v2 = WorkflowSnapshot.builder()
                .id(id).coordinationId("c2").state("v2").createdAt(Instant.now()).build();
        store.save(v2);

        // c1 should no longer contain the snapshot
        assertEquals(0, store.list(CheckpointQuery.forCoordination("c1")).size(),
                "Old coordination index should be cleaned up on overwrite");

        // c2 should contain it
        assertEquals(1, store.list(CheckpointQuery.forCoordination("c2")).size(),
                "New coordination index should contain the snapshot");

        // Total should be 1 (same ID, overwritten)
        assertEquals(1, store.list(CheckpointQuery.all()).size());
    }
}
