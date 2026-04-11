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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 regression: the policy-aware overload of
 * {@link ToolExecutionHelper#executeWithApproval} respects the session-scoped
 * {@link ToolApprovalPolicy}, so callers can force-allow, force-deny, or run
 * a custom predicate without re-annotating each {@code @AiTool}. Before this
 * phase the helper hard-coded {@code tool.requiresApproval()} as the gate
 * decision, leaving {@code ToolApprovalPolicy} as dead code.
 */
class ToolExecutionHelperPolicyTest {

    private static ToolDefinition annotatedTool(AtomicBoolean executorFired) {
        return ToolDefinition.builder("delete_record", "delete a record")
                .parameter("id", "record id", "string", true)
                .executor(args -> {
                    executorFired.set(true);
                    return "deleted:" + args.get("id");
                })
                .requiresApproval("Approve deletion?", 60)
                .build();
    }

    private static ToolDefinition unannotatedTool(AtomicBoolean executorFired) {
        return ToolDefinition.builder("list_rows", "readonly query")
                .parameter("table", "table name", "string", true)
                .executor(args -> {
                    executorFired.set(true);
                    return "ok";
                })
                .build();
    }

    @Test
    void allowAllPolicyBypassesGateEvenForAnnotatedTool() {
        var executorFired = new AtomicBoolean();
        var tool = annotatedTool(executorFired);
        var strategyConsulted = new AtomicBoolean();

        ApprovalStrategy wouldTimeOut = (approval, session) -> {
            strategyConsulted.set(true);
            return ApprovalStrategy.ApprovalOutcome.TIMED_OUT;
        };

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_record", tool, Map.of("id", "r-1"),
                new NoopSession(), wouldTimeOut, ToolApprovalPolicy.allowAll());

        assertTrue(executorFired.get(),
                "AllowAll policy must bypass the gate and invoke the executor directly");
        assertFalse(strategyConsulted.get(),
                "AllowAll policy must not consult the ApprovalStrategy");
        assertTrue(result.contains("deleted:r-1"), result);
    }

    @Test
    void denyAllPolicyGatesEvenUnannotatedTool() {
        var executorFired = new AtomicBoolean();
        var tool = unannotatedTool(executorFired);
        var observed = new AtomicReference<PendingApproval>();

        ApprovalStrategy capturing = (approval, session) -> {
            observed.set(approval);
            return ApprovalStrategy.ApprovalOutcome.DENIED;
        };

        var result = ToolExecutionHelper.executeWithApproval(
                "list_rows", tool, Map.of("table", "customers"),
                new NoopSession(), capturing, ToolApprovalPolicy.denyAll());

        assertFalse(executorFired.get(),
                "DenyAll policy must gate every tool, even readonly ones without @RequiresApproval");
        assertNotNull(observed.get(),
                "DenyAll policy must consult the ApprovalStrategy for every tool");
        assertTrue(result.contains("cancelled"), result);
    }

    @Test
    void customPolicyPredicateIsConsulted() {
        var executorFired = new AtomicBoolean();
        var tool = unannotatedTool(executorFired);
        var observed = new AtomicReference<PendingApproval>();

        ApprovalStrategy capturing = (approval, session) -> {
            observed.set(approval);
            return ApprovalStrategy.ApprovalOutcome.APPROVED;
        };

        ToolApprovalPolicy onlyListRows = ToolApprovalPolicy.custom(
                t -> "list_rows".equals(t.name()));

        ToolExecutionHelper.executeWithApproval(
                "list_rows", tool, Map.of("table", "customers"),
                new NoopSession(), capturing, onlyListRows);

        assertNotNull(observed.get(),
                "Custom policy predicate must gate list_rows even though it is unannotated");
        assertTrue(executorFired.get(),
                "Approved outcome must fall through to executor");
    }

    @Test
    void nullPolicyDefaultsToAnnotatedBehavior() {
        var executorFired = new AtomicBoolean();
        var tool = annotatedTool(executorFired);
        var observed = new AtomicReference<PendingApproval>();

        ApprovalStrategy capturing = (approval, session) -> {
            observed.set(approval);
            return ApprovalStrategy.ApprovalOutcome.DENIED;
        };

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_record", tool, Map.of("id", "r-1"),
                new NoopSession(), capturing, null);

        assertNotNull(observed.get(),
                "Null policy must default to Annotated — tool with @RequiresApproval gates");
        assertFalse(executorFired.get());
        assertTrue(result.contains("cancelled"));
    }

    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() { return "policy-test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }
}
