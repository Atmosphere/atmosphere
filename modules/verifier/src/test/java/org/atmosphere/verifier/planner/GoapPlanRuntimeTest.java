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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.prompt.WorkflowJsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the deterministic planner emits workflow JSON that the production
 * {@link WorkflowJsonParser} accepts and re-binds correctly — the integration
 * contract a deterministic plan source must honour to be a drop-in for the
 * verifier pipeline.
 */
class GoapPlanRuntimeTest {

    private static final List<GoapAction> EMAIL_ACTIONS = List.of(
            new GoapAction("fetch", "fetch_emails", Set.of(), Set.of("fetched"),
                    Map.of("folder", "inbox"), "emails"),
            new GoapAction("summarize", "summarize", Set.of("fetched"), Set.of("summarized"),
                    Map.of("input", "@emails"), "summary"));

    private static GoapPlanRuntime runtime() {
        return new GoapPlanRuntime(EMAIL_ACTIONS, Set.of(),
                goal -> goal.toLowerCase().contains("summ") || goal.toLowerCase().contains("inbox")
                        ? Set.of("summarized") : Set.of());
    }

    private static AgentExecutionContext context(String message) {
        return new AgentExecutionContext(message, null, null, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null, null);
    }

    @Test
    void emittedJsonParsesIntoExpectedWorkflow() {
        var json = runtime().generate(context("Summarize the inbox"));
        var workflow = new WorkflowJsonParser().parse(json);

        assertEquals("Summarize the inbox", workflow.goal());
        assertEquals(2, workflow.steps().size());

        var fetch = (ToolCallNode) workflow.steps().get(0).node();
        assertEquals("fetch_emails", fetch.toolName());
        assertEquals("inbox", fetch.arguments().get("folder"));
        assertEquals("emails", fetch.resultBinding());

        var summarize = (ToolCallNode) workflow.steps().get(1).node();
        assertEquals("summarize", summarize.toolName());
        // "@emails" must have been re-bound to a SymRef by the parser.
        assertInstanceOf(SymRef.class, summarize.arguments().get("input"),
                "the symbolic argument round-trips as a SymRef after parsing");
        assertEquals("emails", ((SymRef) summarize.arguments().get("input")).ref());
    }

    @Test
    void unreachableGoalEmitsParseableEmptyPlan() {
        // A goal the action domain cannot satisfy yields a valid empty plan,
        // not a parse error — the verifier sees a well-formed (no-op) workflow.
        var json = runtime().generate(context("do something impossible"));
        var workflow = new WorkflowJsonParser().parse(json);
        assertTrue(workflow.steps().isEmpty());
    }
}
