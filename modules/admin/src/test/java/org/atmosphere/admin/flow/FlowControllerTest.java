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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControllerTest {

    private static CoordinationJournal withEvents(List<CoordinationEvent> events) {
        // Minimal in-memory journal impl that simply returns the seeded
        // event list for any query.
        return new CoordinationJournal() {
            @Override public void start() { }
            @Override public void stop() { }
            @Override public void record(CoordinationEvent event) { }
            @Override public List<CoordinationEvent> retrieve(String coordinationId) {
                return events;
            }
            @Override public List<CoordinationEvent> query(
                    org.atmosphere.coordinator.journal.CoordinationQuery query) {
                return events;
            }
            @Override public CoordinationJournal inspector(
                    org.atmosphere.coordinator.journal.CoordinationJournalInspector inspector) {
                return this;
            }
        };
    }

    @Test
    void renderFlowProducesNodesAndEdgesFromJournal() {
        var now = Instant.now();
        var journal = withEvents(List.of(
                new CoordinationEvent.CoordinationStarted(
                        "coord-1", "primary-assistant", now),
                new CoordinationEvent.AgentDispatched(
                        "coord-1", "scheduler-agent", "propose_slots",
                        Map.of("topic", "Sarah"), now.plusMillis(5)),
                new CoordinationEvent.AgentCompleted(
                        "coord-1", "scheduler-agent", "propose_slots",
                        "ok", Duration.ofMillis(42), now.plusMillis(50))));
        var graph = new FlowController(journal).renderFlow(0);

        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) graph.get("nodes");
        assertEquals(2, nodes.size(),
                "coordinator + one downstream agent → two nodes");
        assertTrue(nodes.stream().anyMatch(n -> "primary-assistant".equals(n.get("id"))));
        assertTrue(nodes.stream().anyMatch(n -> "scheduler-agent".equals(n.get("id"))));

        @SuppressWarnings("unchecked")
        var edges = (List<Map<String, Object>>) graph.get("edges");
        assertEquals(1, edges.size());
        var edge = edges.get(0);
        assertEquals("primary-assistant", edge.get("from"));
        assertEquals("scheduler-agent", edge.get("to"));
        assertEquals(1, edge.get("dispatches"));
        assertEquals(1, edge.get("successes"));
        assertEquals(0, edge.get("failures"));
        assertEquals(42L, edge.get("averageDurationMs"));
    }

    @Test
    void renderRunScopesToOneCoordinationId() {
        var now = Instant.now();
        var journal = withEvents(List.of(
                new CoordinationEvent.CoordinationStarted("coord-X", "primary", now),
                new CoordinationEvent.AgentDispatched("coord-X", "a1", "do",
                        Map.of(), now.plusMillis(1))));
        var graph = new FlowController(journal).renderRun("coord-X");
        assertNotNull(graph.get("nodes"));
        assertNotNull(graph.get("edges"));
    }

    /**
     * Regression for the P2 "flow graph attribution is wrong for
     * interleaved/multi-coordination streams" finding. Two concurrent
     * coordinator runs interleave their events in the journal; the graph
     * must attribute each dispatch to its own coordinator, not to whichever
     * started-event the iterator happened to see last.
     */
    @Test
    void renderFlowAttributesInterleavedCoordinationsByCoordinationId() {
        var now = Instant.now();
        var journal = withEvents(List.of(
                // Run A starts under coordinator "ceo"
                new CoordinationEvent.CoordinationStarted(
                        "coord-A", "ceo", now),
                // Run B starts under coordinator "primary-assistant"
                new CoordinationEvent.CoordinationStarted(
                        "coord-B", "primary-assistant", now.plusMillis(1)),
                // Run A dispatches to research-agent — must attribute to "ceo"
                new CoordinationEvent.AgentDispatched(
                        "coord-A", "research-agent", "web_search",
                        Map.of(), now.plusMillis(5)),
                // Run B dispatches to scheduler-agent — must attribute to
                // "primary-assistant", NOT the most-recently-seen "ceo"
                new CoordinationEvent.AgentDispatched(
                        "coord-B", "scheduler-agent", "propose_slots",
                        Map.of(), now.plusMillis(6)),
                new CoordinationEvent.AgentCompleted(
                        "coord-A", "research-agent", "web_search",
                        "ok", Duration.ofMillis(30), now.plusMillis(40)),
                new CoordinationEvent.AgentCompleted(
                        "coord-B", "scheduler-agent", "propose_slots",
                        "ok", Duration.ofMillis(20), now.plusMillis(41))));
        var graph = new FlowController(journal).renderFlow(0);

        @SuppressWarnings("unchecked")
        var edges = (List<Map<String, Object>>) graph.get("edges");
        assertEquals(2, edges.size(), "two distinct edges, one per coordinator");

        var ceoEdge = edges.stream()
                .filter(e -> "ceo".equals(e.get("from"))
                        && "research-agent".equals(e.get("to")))
                .findFirst().orElseThrow(() ->
                        new AssertionError("missing ceo->research-agent edge: " + edges));
        assertEquals(1, ceoEdge.get("dispatches"));

        var paEdge = edges.stream()
                .filter(e -> "primary-assistant".equals(e.get("from"))
                        && "scheduler-agent".equals(e.get("to")))
                .findFirst().orElseThrow(() ->
                        new AssertionError(
                                "missing primary-assistant->scheduler-agent edge: " + edges));
        assertEquals(1, paEdge.get("dispatches"));

        // The pre-fix behaviour would have collapsed the second dispatch
        // under "ceo" (last-wins cursor), producing a single ceo->* edge
        // with dispatches=2 — explicitly assert we don't see that shape.
        assertTrue(edges.stream().noneMatch(e -> "ceo".equals(e.get("from"))
                        && "scheduler-agent".equals(e.get("to"))),
                "scheduler-agent must not be attributed to ceo: " + edges);
    }

    @Test
    void renderFlowOnEmptyJournalReturnsEmptyGraph() {
        var graph = new FlowController(CoordinationJournal.NOOP).renderFlow(0);
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) graph.get("nodes");
        @SuppressWarnings("unchecked")
        var edges = (List<Map<String, Object>>) graph.get("edges");
        assertTrue(nodes.isEmpty());
        assertTrue(edges.isEmpty());
    }
}
