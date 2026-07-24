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
package org.atmosphere.quarkus.runtime;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.arc.Arc;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus parity for the Spring Boot starter's {@code AtmosphereCheckpointEndpoint}:
 * a read-only admin surface over the durable-run {@link CheckpointStore}, serving
 * {@code GET /api/admin/checkpoints} so the console's <em>Checkpoints</em> tab shows
 * the same {@link WorkflowSnapshot} envelopes a durable execution / passivation run
 * persisted — on Quarkus exactly as on Spring (Runtime Truth, Invariant #5: the view
 * is served from the store the framework actually resolved, never a reconstructed
 * twin). The deployment processor registers this servlet only when
 * {@code atmosphere-checkpoint} is on the classpath, the same gate under which
 * {@code AtmosphereCheckpointProducer} produces the store, so the surface is live
 * out of the box (in-memory by default, an operator-supplied SQLite / Postgres store
 * transparently backing it).
 *
 * <p>The store is looked up from the Arc CDI container at request time — the servlet
 * is instantiated by the Undertow container, not CDI — so it always reflects the one
 * live {@link CheckpointStore} bean (the {@code @DefaultBean} default or a user
 * override).</p>
 *
 * <p>Read-only and bounded: only {@code GET} is served ({@link HttpServlet} answers
 * every other method {@code 405}); the store never mutates. {@code limit} defaults to
 * {@value #DEFAULT_LIMIT} and is capped at {@value #MAX_LIMIT} so an unbounded
 * {@code list()} can never be forced from the wire (Backpressure, Invariant #3). A
 * malformed {@code since}/{@code until} ISO-8601 instant is rejected {@code 400}, never
 * {@code 500} (Boundary Safety, Invariant #4). The opaque, application-owned
 * {@code state} field is deliberately omitted — it can be large and may carry sensitive
 * payloads; the admin view surfaces only the lineage/metadata envelope, matching the
 * Spring endpoint.</p>
 *
 * <p>JSON is hand-rolled to avoid pulling Jackson into the runtime classpath (the
 * Quarkus extension's runtime POM is intentionally minimal), mirroring
 * {@link AtmosphereConsoleInfoServlet}.</p>
 */
public class AtmosphereCheckpointServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereCheckpointServlet.class);

    /** Hard cap on rows returned in a single call — the surface bounds its own response. */
    static final int MAX_LIMIT = 500;

    /** Default page size when the caller supplies no {@code limit}. */
    static final int DEFAULT_LIMIT = 100;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var store = resolveStore();
        if (store == null) {
            // The producer registers with this servlet under the same classpath
            // gate, so this is only reachable transiently before the container is
            // ready; report an empty list rather than a 500 (Terminal Path).
            logger.debug("No CheckpointStore available from the Arc container yet — "
                    + "returning an empty checkpoint list");
            writeJson(resp, HttpServletResponse.SC_OK, "[]");
            return;
        }

        Instant since;
        Instant until;
        try {
            since = parseInstant(req.getParameter("since"));
            until = parseInstant(req.getParameter("until"));
        } catch (DateTimeParseException e) {
            var error = new LinkedHashMap<String, Object>();
            error.put("error", "Invalid timestamp: " + e.getMessage());
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, toJson(error));
            return;
        }

        var query = CheckpointQuery.builder()
                .coordinationId(blankToNull(req.getParameter("coordinationId")))
                .agentName(blankToNull(req.getParameter("agent")))
                .since(since)
                .until(until)
                .limit(clampLimit(req.getParameter("limit")))
                .build();

        // The store returns oldest-first and already applied the bound above;
        // reverse to newest-first for the operator view (most recent runs on top).
        var snapshots = store.list(query);
        var rows = new ArrayList<Map<String, Object>>(snapshots.size());
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            rows.add(toWire(snapshots.get(i)));
        }
        writeJson(resp, HttpServletResponse.SC_OK, toJson(rows));
    }

    /**
     * Looks up the single live {@link CheckpointStore} bean from the Arc container,
     * or {@code null} when the container or bean is not (yet) available.
     */
    private static CheckpointStore resolveStore() {
        var container = Arc.container();
        if (container == null) {
            return null;
        }
        var handle = container.instance(CheckpointStore.class);
        return handle.isAvailable() ? handle.get() : null;
    }

    /**
     * Wire envelope for one snapshot: lineage ({@code id} / {@code parentId} /
     * {@code root}), the owning {@code coordinationId} / {@code agentName}, the
     * {@code createdAt} timestamp, and the metadata map. The opaque, caller-owned
     * {@code state} is intentionally excluded (may be large / sensitive), matching
     * the Spring endpoint.
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

    private static Instant parseInstant(String raw) {
        return raw == null || raw.isBlank() ? null : Instant.parse(raw);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Parse and clamp the {@code limit} parameter to {@code [1, MAX_LIMIT]},
     * defaulting a missing or non-numeric value to {@value #DEFAULT_LIMIT}. The
     * response stays bounded whatever the caller sends (Invariant #3).
     */
    private static int clampLimit(String raw) {
        int limit = DEFAULT_LIMIT;
        if (raw != null && !raw.isBlank()) {
            try {
                limit = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                limit = DEFAULT_LIMIT;
            }
        }
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static void writeJson(HttpServletResponse resp, int status, String json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        try (var w = resp.getWriter()) {
            w.write(json);
        }
    }

    /**
     * Hand-rolled JSON serializer for the closed value set this servlet controls:
     * {@link List}, {@link Map} (String keys), {@link String}, {@link Boolean}, and
     * {@code null}. Keeps the runtime POM Jackson-free, like
     * {@link AtmosphereConsoleInfoServlet}.
     */
    private static String toJson(Object value) {
        var sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (value instanceof Boolean b) {
            sb.append(b.toString());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            var first = true;
            for (var e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            var first = true;
            for (var item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        var sb = new StringBuilder(raw.length() + 4);
        for (var i = 0; i < raw.length(); i++) {
            var c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
