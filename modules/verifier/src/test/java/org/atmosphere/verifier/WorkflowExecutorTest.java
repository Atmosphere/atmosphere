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

import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.execute.RegistryToolDispatcher;
import org.atmosphere.verifier.execute.UnresolvedSymRefException;
import org.atmosphere.verifier.execute.WorkflowExecutionException;
import org.atmosphere.verifier.execute.WorkflowExecutor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowExecutorTest {

    @Test
    void okPlanProducesExpectedBindings() {
        Map<String, Map<String, Object>> captures = new LinkedHashMap<>();
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(captures)));

        Map<String, Object> env = executor.run(PlanFixtures.okPlan(), Map.of());

        assertEquals("result-of-" + PlanFixtures.FETCH, env.get("emails"));
        assertEquals("result-of-" + PlanFixtures.SUMMARIZE, env.get("summary"));
        // Both tools were called in order
        assertTrue(captures.containsKey(PlanFixtures.FETCH));
        assertTrue(captures.containsKey(PlanFixtures.SUMMARIZE));
    }

    @Test
    void symRefArgumentsAreResolvedBeforeDispatch() {
        Map<String, Map<String, Object>> captures = new LinkedHashMap<>();
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(captures)));

        executor.run(PlanFixtures.okPlan(), Map.of());

        Map<String, Object> summarizeArgs = captures.get(PlanFixtures.SUMMARIZE);
        // The SymRef was replaced by the resolved value before dispatch
        assertEquals("result-of-" + PlanFixtures.FETCH, summarizeArgs.get("input"));
        assertFalse(summarizeArgs.get("input") instanceof SymRef,
                "SymRef leaked through to the dispatcher");
    }

    @Test
    void initialEnvBindingsAreVisibleToFirstStep() {
        Map<String, Map<String, Object>> captures = new LinkedHashMap<>();
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(captures)));

        Workflow wf = new Workflow(
                "use external binding",
                List.of(new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND,
                        Map.of("to", new SymRef("user_email")),
                        null))));

        Map<String, Object> initial = new HashMap<>();
        initial.put("user_email", "alice@company.com");
        executor.run(wf, initial);

        assertEquals("alice@company.com", captures.get(PlanFixtures.SEND).get("to"));
    }

    @Test
    void toolFailureCarriesPartialEnv() {
        // Step 1 (fetch) succeeds; step 2 (summarize) is configured to fail.
        var executor = new WorkflowExecutor(new RegistryToolDispatcher(
                PlanFixtures.failingRegistry(PlanFixtures.SUMMARIZE, null)));

        WorkflowExecutionException ex = assertThrows(
                WorkflowExecutionException.class,
                () -> executor.run(PlanFixtures.okPlan(), Map.of()));

        // partialEnv contains the fetch result that landed before the failure
        assertEquals("result-of-" + PlanFixtures.FETCH, ex.partialEnv().get("emails"));
        assertFalse(ex.partialEnv().containsKey("summary"));
        assertEquals("summary", ex.failedAtBinding());
        assertEquals("summarize", ex.failedAtLabel());
        assertEquals(1, ex.failedAtIndex());
    }

    @Test
    void unresolvedSymRefThrowsTypedException() {
        // A plan that would have failed well-formedness — this verifies
        // the executor still surfaces the boundary error as a typed
        // exception (correctness invariant #4) rather than NPE.
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(null)));

        UnresolvedSymRefException ex = assertThrows(
                UnresolvedSymRefException.class,
                () -> executor.run(PlanFixtures.forwardRefPlan(), Map.of()));
        assertEquals("emails", ex.ref());
        assertEquals(0, ex.stepIndex());
    }

    @Test
    @SuppressWarnings("unchecked")
    void symRefsNestedInListAndMapAreResolved() {
        // Mirrors the Python ref impl's _normalize_refs traversal: SymRefs
        // inside list elements and map values must be resolved before
        // dispatch so the tool sees the real bound values.
        Map<String, Map<String, Object>> captures = new LinkedHashMap<>();
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(captures)));

        Workflow wf = new Workflow(
                "nested refs",
                List.of(new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND,
                        Map.of(
                                "recipients", List.of(
                                        new SymRef("user_email"),
                                        "static@example.com"),
                                "headers", Map.of(
                                        "from", new SymRef("user_email"),
                                        "subject", "literal value"),
                                "deeply_nested", List.of(
                                        Map.of("inner", new SymRef("user_email")))),
                        null))));

        Map<String, Object> initial = new HashMap<>();
        initial.put("user_email", "alice@company.com");
        executor.run(wf, initial);

        Map<String, Object> sendArgs = captures.get(PlanFixtures.SEND);
        List<Object> recipients = (List<Object>) sendArgs.get("recipients");
        assertEquals("alice@company.com", recipients.get(0));
        assertEquals("static@example.com", recipients.get(1));
        assertFalse(recipients.get(0) instanceof SymRef, "Nested list SymRef leaked through");
        Map<String, Object> headers = (Map<String, Object>) sendArgs.get("headers");
        assertEquals("alice@company.com", headers.get("from"));
        assertEquals("literal value", headers.get("subject"));
        assertFalse(headers.get("from") instanceof SymRef, "Nested map SymRef leaked through");
        List<Object> deep = (List<Object>) sendArgs.get("deeply_nested");
        Map<String, Object> deepMap = (Map<String, Object>) deep.get(0);
        assertEquals("alice@company.com", deepMap.get("inner"));
    }

    @Test
    void unresolvedSymRefNestedInListThrowsTypedException() {
        // Boundary error (#4): nested SymRef referencing an unbound
        // identifier must surface as the same typed exception, not NPE.
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(null)));

        Workflow wf = new Workflow(
                "nested forward ref",
                List.of(new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND,
                        Map.of("recipients", List.of(new SymRef("emails"))),
                        null))));

        UnresolvedSymRefException ex = assertThrows(
                UnresolvedSymRefException.class,
                () -> executor.run(wf, Map.of()));
        assertEquals("emails", ex.ref());
        assertEquals(0, ex.stepIndex());
    }
}
