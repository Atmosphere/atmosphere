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

import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only admin REST surface for the durable-run {@link CheckpointStore}: the
 * {@link WorkflowSnapshot} entries a durable execution / passivation run
 * persisted. Backs the console <em>Checkpoints</em> tab so operators can see
 * what durable-run state actually landed in the store the framework resolved at
 * runtime — never a reconstructed twin (Runtime Truth, Invariant #5).
 *
 * <p>Materializes only when a {@link CheckpointStore} bean is present
 * ({@link ConditionalOnBean}) and the {@code atmosphere-checkpoint} JAR is on
 * the classpath ({@link ConditionalOnClass}). The starter's
 * {@code AtmosphereCheckpointAutoConfiguration} default provides an in-memory
 * store when none is configured, so the surface is live out of the box while an
 * operator-supplied SQLite / Postgres store transparently backs it.</p>
 *
 * <p>All endpoints are GETs under {@code /api/admin/} — they inherit the opt-in
 * {@code atmosphere.admin.http-read-auth-required} token gate the
 * {@code AdminApiAuthFilter} applies across the whole {@code /api/admin/*}
 * space, matching the posture of the other admin read endpoints (Invariant
 * #6). The surface never mutates the store.</p>
 *
 * <p>The listing is bounded: {@code limit} defaults to 100 and is capped at
 * {@value #MAX_LIMIT}, so an unbounded {@code list()} can never be forced from
 * the wire (Backpressure, Invariant #3). The opaque, application-owned
 * {@code state} field is deliberately omitted from the wire shape — it can be
 * large and may carry sensitive payloads; the admin view surfaces only the
 * lineage/metadata envelope.</p>
 *
 * @since 4.1
 */
@AutoConfiguration
@RestController
@RequestMapping("/api/admin")
@ConditionalOnClass(CheckpointStore.class)
@ConditionalOnBean(CheckpointStore.class)
public class AtmosphereCheckpointEndpoint {

    /**
     * Hard cap on the number of checkpoint rows returned in a single call. A
     * request may ask for fewer, but never more — the admin surface must bound
     * its own response independently of the store's internal cap (Invariant #3).
     */
    static final int MAX_LIMIT = 500;

    /** Default page size when the caller supplies no {@code limit}. */
    static final int DEFAULT_LIMIT = 100;

    private final CheckpointStore store;

    public AtmosphereCheckpointEndpoint(CheckpointStore store) {
        this.store = store;
    }

    /**
     * List persisted checkpoint / durable-run snapshots, newest first. Optional
     * {@code coordinationId} / {@code agent} filters narrow the result; a
     * malformed {@code since}/{@code until} ISO-8601 instant is rejected as
     * {@code 400}, never {@code 500} (Boundary Safety, Invariant #4).
     *
     * @param coordinationId restrict to one workflow run, or {@code null}
     * @param agent          restrict to one agent, or {@code null}
     * @param since          only snapshots at/after this instant (ISO-8601), or {@code null}
     * @param until          only snapshots at/before this instant (ISO-8601), or {@code null}
     * @param limit          maximum rows; clamped to {@code [1, MAX_LIMIT]}
     * @return the checkpoint envelopes in wire shape
     */
    @GetMapping("/checkpoints")
    public ResponseEntity<List<Map<String, Object>>> listCheckpoints(
            @RequestParam(value = "coordinationId", required = false) String coordinationId,
            @RequestParam(value = "agent", required = false) String agent,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        Instant sinceInstant;
        Instant untilInstant;
        try {
            sinceInstant = since != null && !since.isBlank() ? Instant.parse(since) : null;
            untilInstant = until != null && !until.isBlank() ? Instant.parse(until) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(List.of(Map.of(
                    "error", "Invalid timestamp: " + e.getMessage())));
        }
        var query = CheckpointQuery.builder()
                .coordinationId(blankToNull(coordinationId))
                .agentName(blankToNull(agent))
                .since(sinceInstant)
                .until(untilInstant)
                .limit(clampLimit(limit))
                .build();
        var rows = new ArrayList<Map<String, Object>>();
        // The store returns oldest-first; reverse to newest-first for the
        // operator view (most recent runs at the top). The store already
        // applied the bound above, so this reversal is over a capped list.
        var snapshots = store.list(query);
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            rows.add(toWire(snapshots.get(i)));
        }
        return ResponseEntity.ok(rows);
    }

    /**
     * Wire envelope for one snapshot: lineage ({@code id} / {@code parentId} /
     * {@code root}), the owning {@code coordinationId} / {@code agentName}, the
     * {@code createdAt} timestamp, and the metadata map. The opaque, caller-
     * owned {@code state} is intentionally excluded (may be large / sensitive).
     */
    private static Map<String, Object> toWire(WorkflowSnapshot<?> snapshot) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", snapshot.id().value());
        entry.put("parentId", snapshot.parentId() != null ? snapshot.parentId().value() : null);
        entry.put("root", snapshot.isRoot());
        entry.put("coordinationId", snapshot.coordinationId());
        entry.put("agentName", snapshot.agentName());
        entry.put("createdAt", snapshot.createdAt().toString());
        entry.put("metadata", snapshot.metadata());
        return entry;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
