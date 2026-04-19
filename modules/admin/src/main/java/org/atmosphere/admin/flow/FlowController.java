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
package org.atmosphere.admin.flow;

import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationQuery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent-to-agent flow graph for the admin plane. Reads the
 * {@link CoordinationJournal} and aggregates dispatches into a
 * graph shape (nodes = agents, edges = dispatch counts) for live
 * visualization. Closes the v0.8 "agent-to-agent flow viewer" gap
 * that the Dynatrace 2026 report flagged: 44% of respondents today
 * review agent-to-agent flows manually because the data isn't
 * structured.
 *
 * <p>This controller is read-only; the admin REST layer maps it to
 * GET endpoints. The underlying data producer is
 * {@code CoordinationJournal} which every {@code @Coordinator}
 * already populates.</p>
 */
public final class FlowController {

    private final CoordinationJournal journal;

    public FlowController(CoordinationJournal journal) {
        this.journal = journal != null ? journal : CoordinationJournal.NOOP;
    }

    /**
     * Render the full journal as a flow graph.
     *
     * @param lookbackMinutes include events at most this old; {@code 0} means unbounded
     * @return map with {@code nodes} and {@code edges} keys suitable for
     *         direct JSON serialization by the admin UI
     */
    public Map<String, Object> renderFlow(int lookbackMinutes) {
        // CoordinationQuery#all() returns every recorded event; filter
        // by lookback in memory since the record's fromTimestamp slot
        // has slightly different semantics across journal impls.
        var events = journal.query(CoordinationQuery.all());
        if (lookbackMinutes > 0) {
            var cutoff = java.time.Instant.now().minus(Duration.ofMinutes(lookbackMinutes));
            events = events.stream()
                    .filter(e -> e != null && !eventTimestamp(e).isBefore(cutoff))
                    .toList();
        }
        return buildGraph(events);
    }

    private static java.time.Instant eventTimestamp(CoordinationEvent e) {
        return switch (e) {
            case CoordinationEvent.CoordinationStarted s -> s.timestamp();
            case CoordinationEvent.AgentDispatched d -> d.timestamp();
            case CoordinationEvent.AgentCompleted c -> c.timestamp();
            case CoordinationEvent.AgentFailed f -> f.timestamp();
            default -> java.time.Instant.EPOCH;
        };
    }

    /** Render a single coordination run. Useful for drilldowns on the UI. */
    public Map<String, Object> renderRun(String coordinationId) {
        var events = journal.retrieve(coordinationId);
        return buildGraph(events);
    }

    /**
     * Build a graph payload: nodes (agents + coordinator roots) and edges
     * (aggregated dispatch counts). Null events are ignored so a partial
     * journal still renders.
     */
    private Map<String, Object> buildGraph(List<CoordinationEvent> events) {
        Set<String> nodeSet = new LinkedHashSet<>();
        Map<String, EdgeKey> edgeAgg = new LinkedHashMap<>();
        // Key: coordinationId → coordinator name. A flat "currentCoordinator"
        // cursor misattributes edges whenever two @Coordinator runs
        // interleave (common in multi-user tenants or concurrent fleet
        // calls). Every event carries coordinationId; we look up the
        // coordinator per-event and preserve attribution even when events
        // arrive out of order. Fallback "unknown" only kicks in for
        // completed/failed events whose CoordinationStarted is outside
        // the queried window.
        Map<String, String> coordinatorByRun = new java.util.HashMap<>();

        for (var e : events) {
            if (e == null) {
                continue;
            }
            switch (e) {
                case CoordinationEvent.CoordinationStarted started -> {
                    coordinatorByRun.put(started.coordinationId(), started.coordinatorName());
                    nodeSet.add(started.coordinatorName());
                }
                case CoordinationEvent.AgentDispatched dispatched -> {
                    nodeSet.add(dispatched.agentName());
                    var from = coordinatorByRun.getOrDefault(
                            dispatched.coordinationId(), "unknown");
                    var key = from + "->" + dispatched.agentName();
                    edgeAgg.computeIfAbsent(key, k -> new EdgeKey(from, dispatched.agentName()))
                            .dispatches++;
                }
                case CoordinationEvent.AgentCompleted completed -> {
                    nodeSet.add(completed.agentName());
                    var from = coordinatorByRun.getOrDefault(
                            completed.coordinationId(), "unknown");
                    var key = from + "->" + completed.agentName();
                    var existing = edgeAgg.get(key);
                    if (existing != null) {
                        existing.successes++;
                        existing.totalDurationMs += completed.duration() != null
                                ? completed.duration().toMillis() : 0;
                    }
                }
                case CoordinationEvent.AgentFailed failed -> {
                    nodeSet.add(failed.agentName());
                    var from = coordinatorByRun.getOrDefault(
                            failed.coordinationId(), "unknown");
                    var key = from + "->" + failed.agentName();
                    var existing = edgeAgg.get(key);
                    if (existing != null) {
                        existing.failures++;
                    }
                }
                default -> {
                    // handoff, route-evaluated, etc. don't affect the base graph
                }
            }
        }

        var nodes = new ArrayList<Map<String, Object>>();
        for (var n : nodeSet) {
            nodes.add(Map.of("id", n, "label", n));
        }
        var edges = new ArrayList<Map<String, Object>>();
        for (var entry : edgeAgg.entrySet()) {
            var e = entry.getValue();
            var avgMs = e.successes > 0 ? e.totalDurationMs / e.successes : 0;
            edges.add(Map.of(
                    "from", e.from,
                    "to", e.to,
                    "dispatches", e.dispatches,
                    "successes", e.successes,
                    "failures", e.failures,
                    "averageDurationMs", avgMs));
        }
        var graph = new LinkedHashMap<String, Object>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    /** Mutable aggregator; never leaves this file. */
    private static final class EdgeKey {
        final String from;
        final String to;
        int dispatches;
        int successes;
        int failures;
        long totalDurationMs;

        EdgeKey(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }
}
