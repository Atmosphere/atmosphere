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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 regression guard for the unified HITL path: {@code executeWithApproval}
 * is the single canonical call site every runtime bridge now routes through, so
 * we cover all four outcomes (approved, denied, timed-out, no strategy) once here
 * and let the per-runtime bridge tests prove they actually reach this helper.
 */
class ToolExecutionHelperApprovalTest {

    private static ToolDefinition sensitiveTool(AtomicInteger invocationCounter) {
        return ToolDefinition.builder("delete_account", "Permanently delete an account")
                .parameter("userId", "User id to delete", "string")
                .executor(args -> {
                    invocationCounter.incrementAndGet();
                    return "deleted:" + args.get("userId");
                })
                .requiresApproval("This will permanently delete the account. Approve?", 60)
                .build();
    }

    private static ToolDefinition plainTool() {
        return ToolDefinition.builder("echo", "Echo input")
                .parameter("value", "value to echo", "string")
                .executor(args -> "echo:" + args.get("value"))
                .build();
    }

    /** Records the most recent approval request and returns a caller-supplied outcome. */
    private static final class RecordingStrategy implements ApprovalStrategy {
        private final ApprovalOutcome outcome;
        final AtomicReference<PendingApproval> last = new AtomicReference<>();

        RecordingStrategy(ApprovalOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session) {
            last.set(approval);
            return outcome;
        }
    }

    /** Minimal StreamingSession that satisfies the PendingApproval.sessionId() lookup. */
    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() { return "test-session"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }

    @Test
    void executesImmediatelyWhenToolDoesNotRequireApproval() {
        var tool = plainTool();
        var result = ToolExecutionHelper.executeWithApproval(
                "echo", tool, Map.of("value", "hi"), null, new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.APPROVED));
        assertEquals("echo:hi", result);
    }

    @Test
    void executesImmediatelyWhenStrategyIsNullEvenIfApprovalRequired() {
        var count = new AtomicInteger();
        var tool = sensitiveTool(count);
        var result = ToolExecutionHelper.executeWithApproval(
                "delete_account", tool, Map.of("userId", "u1"), null, null);
        assertEquals("deleted:u1", result);
        assertEquals(1, count.get());
    }

    @Test
    void approvedOutcomeRunsTheDelegate() {
        var count = new AtomicInteger();
        var tool = sensitiveTool(count);
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.APPROVED);

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_account", tool, Map.of("userId", "u42"), new NoopSession(), strategy);

        assertEquals("deleted:u42", result);
        assertEquals(1, count.get());
        var pending = strategy.last.get();
        assertNotNull(pending);
        assertEquals("delete_account", pending.toolName());
        assertEquals("u42", pending.arguments().get("userId"));
        assertTrue(pending.approvalId().startsWith("apr_"));
    }

    @Test
    void deniedOutcomeReturnsCancelledWithoutExecutingDelegate() {
        var count = new AtomicInteger();
        var tool = sensitiveTool(count);
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.DENIED);

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_account", tool, Map.of("userId", "u1"), new NoopSession(), strategy);

        assertTrue(result.contains("cancelled"));
        assertTrue(result.contains("Action cancelled by user"));
        assertEquals(0, count.get(),
                "Denied approvals must never run the underlying tool executor");
    }

    @Test
    void timedOutOutcomeReturnsTimeoutWithoutExecutingDelegate() {
        var count = new AtomicInteger();
        var tool = sensitiveTool(count);
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.TIMED_OUT);

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_account", tool, Map.of("userId", "u1"), new NoopSession(), strategy);

        assertTrue(result.contains("timeout"));
        assertTrue(result.contains("Approval timed out"));
        assertEquals(0, count.get());
    }

    @Test
    void toolMapBuilderPreservesToolIdentity() {
        var tools = List.of(plainTool(), sensitiveTool(new AtomicInteger()));
        var map = ToolExecutionHelper.toToolMap(tools);
        assertEquals(2, map.size());
        assertEquals("echo", map.get("echo").name());
        assertEquals("delete_account", map.get("delete_account").name());
    }
}
