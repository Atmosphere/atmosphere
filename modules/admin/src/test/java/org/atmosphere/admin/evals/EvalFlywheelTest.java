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
package org.atmosphere.admin.evals;

import org.atmosphere.admin.ControlAuthorizer;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationJournalInspector;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalFlywheelTest {

    private static final ControlAuthorizer ALLOW = (action, target, principal) -> true;

    // --- JournalDatasetPromoter ------------------------------------------

    @Test
    void promoterExtractsPromptAndReference() {
        var events = List.<CoordinationEvent>of(
                new CoordinationEvent.AgentDispatched("c1", "researcher", "research",
                        Map.of("message", "What is WebTransport?"), Instant.now()),
                new CoordinationEvent.AgentCompleted("c1", "researcher", "research",
                        "WebTransport is an HTTP/3-based transport.", Duration.ofMillis(50), Instant.now()));

        var promoted = JournalDatasetPromoter.promote("c1", events, List.of("prod")).orElseThrow();

        assertEquals("What is WebTransport?", promoted.prompt());
        assertEquals("WebTransport is an HTTP/3-based transport.", promoted.reference());
        assertEquals("journal:c1", promoted.source());
        assertTrue(promoted.tags().contains("prod"));
    }

    @Test
    void promoterUsesLastCompletionAsReference() {
        var events = List.<CoordinationEvent>of(
                new CoordinationEvent.AgentDispatched("c2", "a", "skill", Map.of(), Instant.now()),
                new CoordinationEvent.AgentCompleted("c2", "a", "skill", "first", Duration.ZERO, Instant.now()),
                new CoordinationEvent.AgentCompleted("c2", "b", "skill", "final answer", Duration.ZERO, Instant.now()));

        assertEquals("final answer",
                JournalDatasetPromoter.promote("c2", events, List.of()).orElseThrow().reference());
    }

    @Test
    void promoterReturnsEmptyWithoutCompletion() {
        var events = List.<CoordinationEvent>of(
                new CoordinationEvent.AgentDispatched("c3", "a", "skill", Map.of(), Instant.now()));
        assertTrue(JournalDatasetPromoter.promote("c3", events, List.of()).isEmpty());
        assertTrue(JournalDatasetPromoter.promote("c4", List.of(), List.of()).isEmpty());
    }

    // --- SampledLiveScorer -----------------------------------------------

    @Test
    void scorerRecordsWhenSampledIn() {
        var store = new InMemoryEvalRunStore();
        var scorer = new SampledLiveScorer(0.5,
                (p, r) -> new SampledLiveScorer.Verdict(true, 0.9, "looks good"),
                store, "live", "judge", () -> 0.1); // 0.1 < 0.5 → sampled in
        var run = scorer.observe("hi", "hello there").orElseThrow();

        assertTrue(run.passed());
        assertEquals(0.9, run.scores().get("score"));
        assertEquals(1, store.list().size(), "the verdict must land in the run store");
    }

    @Test
    void scorerSkipsWhenSampledOut() {
        var store = new InMemoryEvalRunStore();
        var scorer = new SampledLiveScorer(0.2,
                (p, r) -> new SampledLiveScorer.Verdict(true, 1.0, ""),
                store, "live", "judge", () -> 0.9); // 0.9 >= 0.2 → sampled out
        assertTrue(scorer.observe("hi", "hello").isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void scorerRateZeroNeverScores() {
        var store = new InMemoryEvalRunStore();
        var scorer = new SampledLiveScorer(0.0,
                (p, r) -> new SampledLiveScorer.Verdict(true, 1.0, ""), store, "live", "j", () -> 0.0);
        assertTrue(scorer.observe("a", "b").isEmpty());
    }

    @Test
    void scorerSwallowsScorerFailure() {
        var store = new InMemoryEvalRunStore();
        var scorer = new SampledLiveScorer(1.0,
                (p, r) -> { throw new RuntimeException("judge down"); },
                store, "live", "j", () -> 0.0);
        assertTrue(scorer.observe("a", "b").isEmpty(), "a scorer failure must not propagate");
        assertTrue(store.list().isEmpty());
    }

    // --- EvalController wiring (consumer) --------------------------------

    @Test
    void controllerPromotesFromJournalAndLists() {
        var journal = journalWith(List.of(
                new CoordinationEvent.AgentDispatched("co", "a", "summarize",
                        Map.of("input", "long doc"), Instant.now()),
                new CoordinationEvent.AgentCompleted("co", "a", "summarize", "short summary",
                        Duration.ZERO, Instant.now())));
        var controller = new EvalController(new InMemoryEvalRunStore(), new InMemoryEvalDatasetStore(),
                journal, null, ALLOW, null);

        var promoted = controller.promoteFromJournal("co", List.of("golden"), "alex").orElseThrow();
        assertEquals("long doc", promoted.prompt());
        assertEquals(1, controller.listDataset().size());
        assertTrue(controller.getDatasetCase(promoted.id()).isPresent());
    }

    @Test
    void controllerObserveLiveRecordsVerdict() {
        var runStore = new InMemoryEvalRunStore();
        var scorer = new SampledLiveScorer(1.0,
                (p, r) -> new SampledLiveScorer.Verdict(false, 0.2, "off-topic"),
                runStore, "live", "judge", () -> 0.0);
        var controller = new EvalController(runStore, new InMemoryEvalDatasetStore(),
                CoordinationJournal.NOOP, scorer, ALLOW, null);

        var run = controller.observeLive("q", "bad answer", "alex").orElseThrow();
        assertFalse(run.passed());
        assertTrue(controller.liveScoringEnabled());
        assertEquals(1, controller.listRuns().size());
    }

    @Test
    void controllerEnforcesWriteAuthorization() {
        var controller = new EvalController(new InMemoryEvalRunStore(), new InMemoryEvalDatasetStore(),
                CoordinationJournal.NOOP, null, ControlAuthorizer.DENY_ALL, null);
        assertThrows(SecurityException.class,
                () -> controller.promoteFromJournal("co", List.of(), "intruder"));
    }

    private static CoordinationJournal journalWith(List<CoordinationEvent> events) {
        return new CoordinationJournal() {
            @Override public void start() { }
            @Override public void stop() { }
            @Override public void record(CoordinationEvent event) { }
            @Override public List<CoordinationEvent> retrieve(String coordinationId) { return events; }
            @Override public List<CoordinationEvent> query(CoordinationQuery query) { return events; }
            @Override public CoordinationJournal inspector(CoordinationJournalInspector inspector) { return this; }
        };
    }
}
