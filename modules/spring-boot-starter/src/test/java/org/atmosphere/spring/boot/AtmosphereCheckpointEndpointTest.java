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
package org.atmosphere.spring.boot;

import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the read-only checkpoint admin surface backing the console
 * <em>Checkpoints</em> tab ({@code GET /api/admin/checkpoints}): a snapshot
 * persisted into the resolved {@link org.atmosphere.checkpoint.CheckpointStore}
 * reads back through the endpoint with its lineage/metadata envelope (Runtime
 * Truth — the tab shows what actually landed in the store, not a twin). Also
 * pins the response bound (Backpressure, Invariant #3) and the malformed-
 * timestamp 400 boundary (Invariant #4).
 */
class AtmosphereCheckpointEndpointTest {

    private InMemoryCheckpointStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryCheckpointStore();
        store.start();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AtmosphereCheckpointEndpoint(store))
                .build();
    }

    @Test
    void listReturnsAPersistedCheckpointWithItsLineageAndMetadata() throws Exception {
        // Seed one durable-run snapshot exactly as a passivation / durable
        // execution run would — a root snapshot for a coordination, tagged.
        var seeded = store.save(WorkflowSnapshot.<String>builder()
                .id(CheckpointId.of("ckpt-root-1"))
                .coordinationId("run-42")
                .agentName("researcher")
                .state("{\"step\":\"gather\"}")
                .metadata(Map.of("label", "before-tool"))
                .createdAt(Instant.parse("2026-07-07T10:15:30Z"))
                .build());

        // Read it back through the admin surface the console tab polls.
        mockMvc.perform(get("/api/admin/checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("ckpt-root-1"))
                .andExpect(jsonPath("$[0].root").value(true))
                .andExpect(jsonPath("$[0].parentId").doesNotExist())
                .andExpect(jsonPath("$[0].coordinationId").value("run-42"))
                .andExpect(jsonPath("$[0].agentName").value("researcher"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-07-07T10:15:30Z"))
                .andExpect(jsonPath("$[0].metadata.label").value("before-tool"))
                // The opaque application state is deliberately NOT surfaced.
                .andExpect(jsonPath("$[0].state").doesNotExist());

        // Sanity: the store genuinely persisted it (side effect is real).
        org.junit.jupiter.api.Assertions.assertEquals("ckpt-root-1", seeded.id().value());
        org.junit.jupiter.api.Assertions.assertEquals(1, store.size());
    }

    @Test
    void newestSnapshotIsReturnedFirst() throws Exception {
        store.save(WorkflowSnapshot.<String>builder()
                .id(CheckpointId.of("old"))
                .coordinationId("run-1")
                .state("a")
                .createdAt(Instant.parse("2026-07-07T10:00:00Z"))
                .build());
        store.save(WorkflowSnapshot.<String>builder()
                .id(CheckpointId.of("new"))
                .coordinationId("run-1")
                .state("b")
                .createdAt(Instant.parse("2026-07-07T11:00:00Z"))
                .build());

        mockMvc.perform(get("/api/admin/checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("new"))
                .andExpect(jsonPath("$[1].id").value("old"));
    }

    @Test
    void coordinationFilterNarrowsTheResult() throws Exception {
        store.save(WorkflowSnapshot.<String>builder()
                .id(CheckpointId.of("a"))
                .coordinationId("run-A")
                .state("x")
                .createdAt(Instant.parse("2026-07-07T10:00:00Z"))
                .build());
        store.save(WorkflowSnapshot.<String>builder()
                .id(CheckpointId.of("b"))
                .coordinationId("run-B")
                .state("y")
                .createdAt(Instant.parse("2026-07-07T10:00:01Z"))
                .build());

        mockMvc.perform(get("/api/admin/checkpoints").param("coordinationId", "run-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("b"))
                .andExpect(jsonPath("$[0].coordinationId").value("run-B"));
    }

    @Test
    void limitIsCappedToProtectAgainstUnboundedListing() throws Exception {
        // Seed more than MAX_LIMIT snapshots; ask for a huge limit.
        for (int i = 0; i < AtmosphereCheckpointEndpoint.MAX_LIMIT + 25; i++) {
            store.save(WorkflowSnapshot.<String>builder()
                    .id(CheckpointId.of("c-" + i))
                    .coordinationId("bulk")
                    .state("s")
                    .createdAt(Instant.parse("2026-07-07T10:00:00Z").plusSeconds(i))
                    .build());
        }

        mockMvc.perform(get("/api/admin/checkpoints").param("limit", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(AtmosphereCheckpointEndpoint.MAX_LIMIT));
    }

    @Test
    void malformedTimestampIsRejectedAs400() throws Exception {
        mockMvc.perform(get("/api/admin/checkpoints").param("since", "not-an-instant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].error").exists());
    }

    @Test
    void emptyStoreReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/admin/checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
