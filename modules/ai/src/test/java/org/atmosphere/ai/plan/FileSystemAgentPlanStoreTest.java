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
package org.atmosphere.ai.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FileSystemAgentPlanStore}: JSON round-trip per
 * agent × conversation, traversal-safe keys, size bounds, and fail-safe reads
 * of corrupt documents.
 */
public class FileSystemAgentPlanStoreTest {

    @TempDir
    Path root;

    private FileSystemAgentPlanStore store() {
        return new FileSystemAgentPlanStore(root);
    }

    private static AgentPlan plan(String goal, String... contents) {
        var steps = new ArrayList<AgentPlan.Step>();
        for (var content : contents) {
            steps.add(new AgentPlan.Step(content, PlanStatus.PENDING, null));
        }
        return new AgentPlan(goal, steps);
    }

    @Test
    public void roundTripsPerAgentAndConversation() {
        var store = store();
        var planA = plan("goal-a", "step 1", "step 2");
        var planB = plan("goal-b", "other");

        store.put("agent", "conv-1", planA);
        store.put("agent", "conv-2", planB);

        assertEquals(planA, store.get("agent", "conv-1").orElseThrow());
        assertEquals(planB, store.get("agent", "conv-2").orElseThrow());
        assertTrue(store.get("agent", "conv-3").isEmpty(),
                "an unwritten conversation must read as empty");
        assertTrue(store.get("other-agent", "conv-1").isEmpty(),
                "plans must not bleed across agents");
    }

    @Test
    public void fullListReplaceOverwrites() {
        var store = store();
        store.put("agent", "conv", plan(null, "old"));
        store.put("agent", "conv", plan(null, "new-1", "new-2"));

        var read = store.get("agent", "conv").orElseThrow();
        assertEquals(2, read.steps().size());
        assertEquals("new-1", read.steps().get(0).content());
    }

    @Test
    public void traversalKeysAreRejected() {
        var store = store();
        var plan = plan(null, "x");

        for (var bad : new String[]{"..", "a/b", "a\\b", "../escape", "", "  ", null}) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.put("agent", bad, plan),
                    "conversationId '" + bad + "' must be rejected");
            assertThrows(IllegalArgumentException.class,
                    () -> store.put(bad, "conv", plan),
                    "agentId '" + bad + "' must be rejected");
        }
        assertFalse(Files.exists(root.getParent().resolve("escape.json")),
                "nothing may be written outside the store root");
    }

    @Test
    public void oversizedPlansAreRejected() {
        var store = store();
        var tooManySteps = new ArrayList<AgentPlan.Step>();
        for (int i = 0; i <= FileSystemAgentPlanStore.MAX_STEPS; i++) {
            tooManySteps.add(new AgentPlan.Step("step " + i, PlanStatus.PENDING, null));
        }
        var e1 = assertThrows(IllegalArgumentException.class,
                () -> store.put("agent", "conv", new AgentPlan(null, tooManySteps)));
        assertTrue(e1.getMessage().contains("step"), e1.getMessage());

        var hugeContent = "x".repeat(FileSystemAgentPlanStore.MAX_PLAN_BYTES);
        var e2 = assertThrows(IllegalArgumentException.class,
                () -> store.put("agent", "conv", plan(null, hugeContent)));
        assertTrue(e2.getMessage().contains("byte"), e2.getMessage());
        assertTrue(store.get("agent", "conv").isEmpty(),
                "a rejected plan must not be persisted");
    }

    @Test
    public void nullPlanIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> store().put("agent", "conv", null));
    }

    @Test
    public void corruptDocumentReadsAsEmpty() throws Exception {
        var store = store();
        store.put("agent", "conv", plan(null, "x"));
        var file = root.resolve("plans").resolve("agent").resolve("conv.json");
        Files.write(file, "{not json".getBytes(StandardCharsets.UTF_8));

        assertTrue(store.get("agent", "conv").isEmpty(),
                "a corrupt plan document must read as empty, not throw");
    }

    @Test
    public void planWithStatusesSurvivesTheRoundTrip() {
        var store = store();
        var plan = new AgentPlan("g", List.of(
                new AgentPlan.Step("a", PlanStatus.COMPLETED, "doing a"),
                new AgentPlan.Step("b", PlanStatus.ABANDONED, null)));

        store.put("agent", "conv", plan);

        assertEquals(plan, store.get("agent", "conv").orElseThrow());
    }

    @Test
    public void capEvictsTheOldestPlanInsteadOfBrickingTheStore() throws Exception {
        // Regression: the cap used to reject every NEW key forever once
        // reached — on channel paths (a fresh conversation id per turn, at
        // the time) that permanently disabled planning for the owner until
        // an operator deleted files by hand (Invariant #2: a cap is a bound,
        // not a terminal state).
        var store = store();
        var plansDir = root.resolve("plans").resolve("agent");
        java.nio.file.Files.createDirectories(plansDir);
        var doc = "{\"goal\":\"g\",\"steps\":[]}";
        for (int i = 0; i < FileSystemAgentPlanStore.MAX_PLAN_FILES; i++) {
            java.nio.file.Files.writeString(plansDir.resolve("conv-" + i + ".json"), doc);
        }
        var oldest = plansDir.resolve("conv-0.json");
        java.nio.file.Files.setLastModifiedTime(oldest,
                java.nio.file.attribute.FileTime.fromMillis(1_000L));

        store.put("agent", "fresh-conversation", plan("new goal", "step"));

        assertFalse(java.nio.file.Files.exists(oldest),
                "the least-recently-modified plan must be evicted to make room");
        assertEquals("new goal",
                store.get("agent", "fresh-conversation").orElseThrow().goal());
        try (var walk = java.nio.file.Files.walk(root.resolve("plans"))) {
            var count = walk.filter(java.nio.file.Files::isRegularFile).count();
            assertTrue(count <= FileSystemAgentPlanStore.MAX_PLAN_FILES,
                    "store must stay within the cap, got " + count);
        }
    }
}
