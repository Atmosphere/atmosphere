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
package org.atmosphere.verifier;

import org.atmosphere.verifier.ast.ConditionalNode;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.execute.RegistryToolDispatcher;
import org.atmosphere.verifier.execute.WorkflowExecutionException;
import org.atmosphere.verifier.execute.WorkflowExecutor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowExecutorConditionalTest {

    private static Workflow branchOnScore() {
        return new Workflow("score gate", List.of(
                new WorkflowStep("decide", new ConditionalNode(
                        "score >= 80",
                        List.of(new WorkflowStep("send", new ToolCallNode(
                                PlanFixtures.SEND,
                                Map.of("to", "a@b.example", "body", "hi"), "sent"))),
                        List.of(new WorkflowStep("sum", new ToolCallNode(
                                PlanFixtures.SUMMARIZE, Map.of(), "skipped")))))));
    }

    @Test
    void runsThenArmWhenPredicateTrue() {
        var captures = new HashMap<String, Map<String, Object>>();
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(captures)));

        Map<String, Object> env = executor.run(branchOnScore(), Map.of("score", 90));

        assertTrue(captures.containsKey(PlanFixtures.SEND), "then-arm tool should fire");
        assertFalse(captures.containsKey(PlanFixtures.SUMMARIZE), "else-arm must not fire");
        assertTrue(env.containsKey("sent"));
        assertFalse(env.containsKey("skipped"));
    }

    @Test
    void runsElseArmWhenPredicateFalse() {
        var captures = new HashMap<String, Map<String, Object>>();
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(captures)));

        Map<String, Object> env = executor.run(branchOnScore(), Map.of("score", 50));

        assertTrue(captures.containsKey(PlanFixtures.SUMMARIZE), "else-arm tool should fire");
        assertFalse(captures.containsKey(PlanFixtures.SEND), "then-arm must not fire");
        assertTrue(env.containsKey("skipped"));
        assertFalse(env.containsKey("sent"));
    }

    @Test
    void unboundPredicateVariableAbortsWithTypedException() {
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(null)));

        WorkflowExecutionException ex = assertThrows(WorkflowExecutionException.class,
                () -> executor.run(branchOnScore(), Map.of())); // no 'score'
        assertTrue(ex.getMessage().contains("predicate"), () -> ex.getMessage());
        assertEquals(0, ex.partialEnv().size());
    }
}
