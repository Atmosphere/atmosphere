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
package org.atmosphere.admin.coordinator;

import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.EventEnvelope;
import org.atmosphere.coordinator.journal.InMemoryCoordinationJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#23): the parent/child {@code EventEnvelope} lineage
 * was authored on every dispatch and read by nothing — the admin plane's
 * only journal reader used the flat {@code retrieve}. The causal-tree
 * endpoint must render the lineage the journal has been paying to write.
 */
class CoordinatorControllerTreeTest {

    private InMemoryCoordinationJournal journal;
    private CoordinatorController controller;

    @BeforeEach
    void setUp() {
        journal = new InMemoryCoordinationJournal();
        journal.start();
        controller = new CoordinatorController(
                () -> new ConcurrentHashMap<String, AgentFleet>(), journal);
    }

    @AfterEach
    void tearDown() {
        journal.stop();
    }

    @Test
    void treeRendersTheJournaledCausalLineage() {
        var coordId = "coord-tree-1";
        var started = EventEnvelope.root(new CoordinationEvent.CoordinationStarted(
                coordId, "ceo", Instant.now()));
        journal.recordEnveloped(started);
        var dispatched = EventEnvelope.childOf(started.eventId(),
                new CoordinationEvent.AgentDispatched(
                        coordId, "research", "search", Map.of(), Instant.now()));
        journal.recordEnveloped(dispatched);
        journal.recordEnveloped(EventEnvelope.childOf(dispatched.eventId(),
                new CoordinationEvent.AgentCompleted(
                        coordId, "research", "search", "found", Duration.ofMillis(5),
                        Instant.now())));

        var tree = controller.getCoordinationTree(coordId).orElseThrow();

        assertEquals(1, tree.size(), "one root: " + tree);
        var root = tree.get(0);
        assertEquals("CoordinationStarted", root.get("type"));
        @SuppressWarnings("unchecked")
        var children = (List<Map<String, Object>>) root.get("children");
        assertEquals(1, children.size(),
                "the dispatch must hang off the coordination start: " + tree);
        assertEquals("AgentDispatched", children.get(0).get("type"));
        @SuppressWarnings("unchecked")
        var grandchildren = (List<Map<String, Object>>) children.get(0).get("children");
        assertEquals("AgentCompleted", grandchildren.get(0).get("type"),
                "the completion must hang off its own dispatch — the flat "
                + "retrieve view could never show this causality");
    }

    @Test
    void unknownCoordinationYieldsEmpty() {
        assertTrue(controller.getCoordinationTree("nope").isEmpty());
    }
}
