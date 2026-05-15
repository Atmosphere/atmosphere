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

import org.atmosphere.admin.ControlAuditLog;
import org.atmosphere.admin.ControlAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalControllerTest {

    private InMemoryEvalRunStore store;
    private ControlAuditLog auditLog;
    private EvalController controller;

    @BeforeEach
    void setUp() {
        store = new InMemoryEvalRunStore();
        auditLog = new ControlAuditLog(100);
        controller = new EvalController(store, ControlAuthorizer.ALLOW_ALL, auditLog);
    }

    @Test
    void recordPersistsRunAndAudits() {
        var run = sampleRun("run-1", "intent-support", true);
        var saved = controller.record(run, "ci-bot");

        assertEquals("run-1", saved.id());
        assertEquals(1, store.list().size());

        var entry = auditLog.entries().get(0);
        assertEquals("evals.write", entry.action());
        assertEquals("ci-bot", entry.principal());
    }

    @Test
    void recordRequiresAuthorization() {
        controller = new EvalController(store, ControlAuthorizer.DENY_ALL, auditLog);
        var run = sampleRun("run-2", "intent-support", true);
        assertThrows(SecurityException.class, () -> controller.record(run, "ci-bot"));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void baselineSummaryAggregatesPassRate() {
        controller.record(sampleRun("r1", "intent-support", true), "ci");
        controller.record(sampleRun("r2", "intent-support", false), "ci");
        controller.record(sampleRun("r3", "intent-support", true), "ci");
        controller.record(sampleRun("r4", "quality-grounding", true), "ci");

        var summary = controller.baselineSummary();
        assertEquals(2, summary.size(), "two baselines, two rows");

        var byBaseline = summary.stream()
                .collect(java.util.stream.Collectors.toMap(
                        m -> (String) m.get("baseline"),
                        m -> m));
        var support = byBaseline.get("intent-support");
        assertEquals(3, support.get("total"));
        assertEquals(2, support.get("passed"));
        assertEquals(2.0 / 3, (double) support.get("passRate"), 1e-9);
    }

    @Test
    void duplicateIdRejected() {
        controller.record(sampleRun("r1", "intent-support", true), "ci");
        assertThrows(IllegalStateException.class,
                () -> controller.record(sampleRun("r1", "intent-support", false), "ci"),
                "Eval runs are immutable — a duplicate id is a programming error");
    }

    @Test
    void deleteRemovesRun() {
        controller.record(sampleRun("r1", "intent-support", true), "ci");
        controller.delete("r1", "ci");
        assertTrue(store.list().isEmpty());
    }

    @Test
    void deleteRequiresAuthorization() {
        var deny = new EvalController(store, ControlAuthorizer.DENY_ALL, auditLog);
        store.save(sampleRun("r1", "intent-support", true));
        assertThrows(SecurityException.class, () -> deny.delete("r1", "ci"));
        assertFalse(store.list().isEmpty(), "denied delete must leave the run in place");
    }

    @Test
    void listForBaselineFilters() {
        controller.record(sampleRun("r1", "intent-support", true), "ci");
        controller.record(sampleRun("r2", "quality-grounding", true), "ci");

        assertEquals(1, controller.listRuns("intent-support").size());
        assertEquals(1, controller.listRuns("quality-grounding").size());
        assertEquals(0, controller.listRuns("missing").size());
    }

    @Test
    void invalidRunIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvalRun("bad id with space", "intent-support",
                        Instant.now(), "v1.0", "p", "j", true, Map.of(), "judge",
                        true, ""));
    }

    private static EvalRun sampleRun(String id, String baseline, boolean passed) {
        return new EvalRun(
                id, baseline, Instant.now(), "atmosphere-4.0.46",
                "What is Atmosphere?", "{\"verdict\":" + passed + "}",
                passed, Map.of("relevance", 0.95), "gpt-4o-mini",
                passed, "");
    }
}
