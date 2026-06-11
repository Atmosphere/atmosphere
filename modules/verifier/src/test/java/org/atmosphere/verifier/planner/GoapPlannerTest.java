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
package org.atmosphere.verifier.planner;

import org.atmosphere.verifier.ast.ToolCallNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoapPlannerTest {

    private static GoapAction action(String label, String tool, Set<String> pre, Set<String> eff,
                                     Map<String, Object> args, String binding) {
        return new GoapAction(label, tool, pre, eff, args, binding);
    }

    @Test
    void findsOrderedPlanRespectingPreconditions() {
        var fetch = action("fetch", "fetch_emails", Set.of(), Set.of("fetched"),
                Map.of("folder", "inbox"), "emails");
        var summarize = action("summarize", "summarize", Set.of("fetched"), Set.of("summarized"),
                Map.of("input", "@emails"), "summary");

        var plan = new GoapPlanner().plan("Summarize the inbox",
                Set.of(), Set.of("summarized"), List.of(summarize, fetch)); // order shuffled

        assertTrue(plan.isPresent());
        var steps = plan.get().steps();
        assertEquals(2, steps.size());
        // fetch must precede summarize because summarize needs "fetched".
        assertEquals("fetch_emails", ((ToolCallNode) steps.get(0).node()).toolName());
        assertEquals("summarize", ((ToolCallNode) steps.get(1).node()).toolName());
        // The argument template + result binding survive into the AST.
        var summarizeCall = (ToolCallNode) steps.get(1).node();
        assertEquals("@emails", summarizeCall.arguments().get("input"));
        assertEquals("summary", summarizeCall.resultBinding());
    }

    @Test
    void goalAlreadySatisfiedYieldsEmptyPlan() {
        var plan = new GoapPlanner().plan("nothing to do",
                Set.of("done"), Set.of("done"), List.of());
        assertTrue(plan.isPresent());
        assertTrue(plan.get().steps().isEmpty(), "no actions needed when the goal already holds");
    }

    @Test
    void unreachableGoalYieldsEmptyOptional() {
        var fetch = action("fetch", "fetch_emails", Set.of(), Set.of("fetched"), Map.of(), null);
        var plan = new GoapPlanner().plan("send",
                Set.of(), Set.of("sent"), List.of(fetch)); // no action produces "sent"
        assertFalse(plan.isPresent());
    }

    @Test
    void prefersFewestSteps() {
        // Two ways to reach "ready": a single direct action, or a two-step chain.
        var direct = action("direct", "do_it", Set.of(), Set.of("ready"), Map.of(), null);
        var stepA = action("a", "step_a", Set.of(), Set.of("mid"), Map.of(), null);
        var stepB = action("b", "step_b", Set.of("mid"), Set.of("ready"), Map.of(), null);

        var plan = new GoapPlanner().plan("get ready",
                Set.of(), Set.of("ready"), List.of(stepA, stepB, direct));

        assertTrue(plan.isPresent());
        assertEquals(1, plan.get().steps().size(), "BFS returns the shortest plan");
        assertEquals("do_it", ((ToolCallNode) plan.get().steps().get(0).node()).toolName());
    }

    @Test
    void respectsMaxPlanLengthBound() {
        var stepA = action("a", "step_a", Set.of(), Set.of("mid"), Map.of(), null);
        var stepB = action("b", "step_b", Set.of("mid"), Set.of("ready"), Map.of(), null);
        // Only a 2-step path exists, but the planner is capped at length 1.
        var plan = new GoapPlanner(1, 10_000).plan("get ready",
                Set.of(), Set.of("ready"), List.of(stepA, stepB));
        assertFalse(plan.isPresent(), "a plan longer than the bound is not returned");
    }

    @Test
    void multiPredicateGoalRequiresAllEffects() {
        var a = action("a", "tool_a", Set.of(), Set.of("x"), Map.of(), null);
        var b = action("b", "tool_b", Set.of(), Set.of("y"), Map.of(), null);
        var plan = new GoapPlanner().plan("need x and y",
                Set.of(), Set.of("x", "y"), List.of(a, b));
        assertTrue(plan.isPresent());
        assertEquals(2, plan.get().steps().size(), "both effects required, so both actions chosen");
    }
}
