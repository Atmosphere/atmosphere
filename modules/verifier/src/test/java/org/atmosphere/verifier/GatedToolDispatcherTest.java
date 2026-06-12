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

import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.execute.ApprovalDeniedException;
import org.atmosphere.verifier.execute.ApprovalGate;
import org.atmosphere.verifier.execute.GatedToolDispatcher;
import org.atmosphere.verifier.execute.RegistryToolDispatcher;
import org.atmosphere.verifier.execute.ToolDispatcher;
import org.atmosphere.verifier.execute.WorkflowExecutionException;
import org.atmosphere.verifier.execute.WorkflowExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatedToolDispatcherTest {

    @Test
    void approvedCallReachesTheDelegate() {
        var delegateCalled = new AtomicBoolean(false);
        ToolDispatcher delegate = (name, args) -> {
            delegateCalled.set(true);
            return "ok-" + name;
        };
        ApprovalGate allow = (name, args) -> true;

        String result = new GatedToolDispatcher(delegate, allow)
                .dispatch("send_email", Map.of());

        assertEquals("ok-send_email", result);
        assertTrue(delegateCalled.get());
    }

    @Test
    void deniedCallNeverReachesTheDelegate() {
        var delegateCalled = new AtomicBoolean(false);
        ToolDispatcher delegate = (name, args) -> {
            delegateCalled.set(true);
            return "should not happen";
        };
        ApprovalGate deny = (name, args) -> false;

        assertThrows(ApprovalDeniedException.class,
                () -> new GatedToolDispatcher(delegate, deny).dispatch("send_email", Map.of()));
        assertFalse(delegateCalled.get(), "denied tool must not dispatch");
    }

    @Test
    void gateThatThrowsFailsClosed() {
        var delegateCalled = new AtomicBoolean(false);
        ToolDispatcher delegate = (name, args) -> {
            delegateCalled.set(true);
            return "should not happen";
        };
        ApprovalGate unreachable = (name, args) -> {
            throw new IllegalStateException("approver offline");
        };

        ApprovalDeniedException ex = assertThrows(ApprovalDeniedException.class,
                () -> new GatedToolDispatcher(delegate, unreachable).dispatch("send_email", Map.of()));
        assertEquals("send_email", ex.toolName());
        assertFalse(delegateCalled.get(), "unreachable approver must not let the tool fire");
    }

    @Test
    void denialDuringExecutionPreservesPartialEnvironment() {
        // First step is approved and binds a result; second step is denied —
        // the executor wraps the denial and carries the partial env.
        ApprovalGate denySend = (name, args) -> !name.equals(PlanFixtures.SEND);
        var gated = new GatedToolDispatcher(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(null)), denySend);
        var executor = new WorkflowExecutor(gated);

        Workflow wf = new Workflow("approve then deny", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        PlanFixtures.FETCH, Map.of(), "emails")),
                new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND, Map.of("body", "hi"), null))));

        WorkflowExecutionException ex = assertThrows(WorkflowExecutionException.class,
                () -> executor.run(wf, Map.of()));
        assertTrue(ex.partialEnv().containsKey("emails"),
                "binding produced before the denied step must survive");
        assertInstanceOfApprovalDenied(ex);
    }

    private static void assertInstanceOfApprovalDenied(WorkflowExecutionException ex) {
        assertTrue(ex.getCause() instanceof ApprovalDeniedException,
                () -> "expected ApprovalDeniedException cause, got: " + ex.getCause());
    }
}
