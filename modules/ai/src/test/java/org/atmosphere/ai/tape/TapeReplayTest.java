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
package org.atmosphere.ai.tape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Deterministic-reconstruction contract for {@link TapeReplay}. Builds a
 * synthetic multi-agent coordination tape (a coordinator run + two fan-out
 * child runs linked by {@code parentRunId}) directly in an
 * {@link InMemoryTapeStore}, then asserts that replay rebuilds the prompt,
 * output, tool calls, and the whole tree with no model in the loop.
 */
class TapeReplayTest {

    private static TapeStep step(String runId, long seq, String kind, String payload) {
        return new TapeStep(runId, seq, kind, payload, seq);
    }

    // Begin OPEN (a terminal row would make the store reject subsequent appends);
    // each run is flipped to COMPLETED via markTerminal after its steps land.
    private static TapeRun run(String runId, String parentRunId, long startedAt, long stepCount) {
        return new TapeRun(runId, runId, "sess", "res", "user-1", runId, "qwen", "built-in",
                startedAt, TapeStatus.OPEN, null, stepCount, 0, false, parentRunId);
    }

    private static InMemoryTapeStore teamTape() {
        var store = new InMemoryTapeStore();

        // Coordinator (root) run: system + user prompt, then a two-segment answer.
        store.begin(run("ceo-1", null, 1000, 2));
        store.append("ceo-1", List.of(
                step("ceo-1", 0, "input",
                        "{\"messages\":[{\"role\":\"system\",\"content\":\"You are the CEO\"},"
                                + "{\"role\":\"user\",\"content\":\"Plan the startup\"}]}"),
                step("ceo-1", 1, "text", "{\"text\":\"Delegating \"}"),
                step("ceo-1", 2, "text", "{\"text\":\"to the team.\"}")));
        store.markTerminal("ceo-1", TapeStatus.COMPLETED, new TapeStore.Counters(3, 0, false));

        // Two specialist children, dispatched at different times (order matters).
        store.begin(run("research-1", "ceo-1", 1100, 2));
        store.append("research-1", List.of(
                step("research-1", 0, "input",
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"Assess the market\"}]}"),
                step("research-1", 1, "tool-start",
                        "{\"toolName\":\"market\",\"arguments\":{\"sector\":\"ai\"}}"),
                step("research-1", 2, "text", "{\"text\":\"Market is large.\"}")));
        store.markTerminal("research-1", TapeStatus.COMPLETED, new TapeStore.Counters(3, 0, false));

        store.begin(run("finance-1", "ceo-1", 1200, 1));
        store.append("finance-1", List.of(
                step("finance-1", 0, "input",
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"Model the burn\"}]}"),
                step("finance-1", 1, "text", "{\"text\":\"Runway is 18 months.\"}")));
        store.markTerminal("finance-1", TapeStatus.COMPLETED, new TapeStore.Counters(2, 0, false));
        return store;
    }

    @Test
    void reconstructRebuildsPromptAndOutput() {
        var store = teamTape();
        var replayed = TapeReplay.reconstruct(store, "ceo-1").orElseThrow();

        assertEquals(List.of(
                new TapeReplay.ReplayedRun.Message("system", "You are the CEO"),
                new TapeReplay.ReplayedRun.Message("user", "Plan the startup")),
                replayed.input());
        assertEquals("Delegating to the team.", replayed.output(),
                "text segments must coalesce in seq order");
        assertTrue(replayed.tools().isEmpty());
        assertEquals(TapeStatus.COMPLETED, replayed.status());
    }

    @Test
    void reconstructDecodesToolCalls() {
        var store = teamTape();
        var replayed = TapeReplay.reconstruct(store, "research-1").orElseThrow();

        assertEquals(1, replayed.tools().size());
        assertEquals("market", replayed.tools().get(0).name());
        assertTrue(replayed.tools().get(0).arguments().contains("\"sector\""),
                "tool arguments must survive reconstruction: " + replayed.tools().get(0).arguments());
        assertEquals("Market is large.", replayed.output());
    }

    @Test
    void reconstructTreeLinksCoordinatorToChildrenInStartOrder() {
        var store = teamTape();
        var tree = TapeReplay.reconstructTree(store, "ceo-1").orElseThrow();

        assertEquals("ceo-1", tree.root().runId());
        assertEquals(3, tree.runCount(), "coordinator + 2 specialists");
        assertEquals(List.of("research-1", "finance-1"),
                tree.children().stream().map(TapeReplay.ReplayedRun::runId).toList(),
                "children ordered by startedAt (research dispatched before finance)");
        assertTrue(tree.children().stream()
                        .allMatch(c -> "ceo-1".equals(c.parentRunId())),
                "every child must link back to the coordinator run");
    }

    @Test
    void unknownRunReconstructsEmpty() {
        var store = teamTape();
        assertFalse(TapeReplay.reconstruct(store, "does-not-exist").isPresent());
        assertFalse(TapeReplay.reconstructTree(store, "does-not-exist").isPresent());
    }

    @Test
    void childRunIsNotItsOwnTreeRoot() {
        // Reconstructing a tree rooted at a child yields just that child (it has
        // no children of its own) — the parentRunId link points up, not down.
        var store = teamTape();
        var tree = TapeReplay.reconstructTree(store, "research-1").orElseThrow();
        assertEquals(1, tree.runCount());
        assertTrue(tree.children().isEmpty());
    }
}
