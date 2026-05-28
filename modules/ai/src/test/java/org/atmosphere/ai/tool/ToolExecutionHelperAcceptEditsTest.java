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
import org.atmosphere.ai.identity.PermissionMode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@link PermissionMode#ACCEPT_EDITS}: it auto-approves
 * tools whose author declared {@link ToolKind#EDIT}, while leaving every other
 * tool on the normal per-tool {@code @RequiresApproval} path and never
 * overriding a DenyAll policy. Pins the behaviour the {@code @AiTool#kind()}
 * tag now enables, closing the "ACCEPT_EDITS behaves identically to DEFAULT"
 * gap.
 */
class ToolExecutionHelperAcceptEditsTest {

    private static final Map<Class<?>, Object> ACCEPT_EDITS_SCOPE =
            Map.of(PermissionMode.class, PermissionMode.ACCEPT_EDITS);

    private static ToolDefinition editTool(AtomicInteger counter) {
        return ToolDefinition.builder("apply_patch", "Apply a code edit")
                .parameter("path", "file path", "string")
                .kind(ToolKind.EDIT)
                .requiresApproval("Apply this edit?", 60)
                .executor(args -> {
                    counter.incrementAndGet();
                    return "patched:" + args.get("path");
                })
                .build();
    }

    private static ToolDefinition shellTool(AtomicInteger counter) {
        return ToolDefinition.builder("run_shell", "Run a shell command")
                .parameter("cmd", "command", "string")
                .kind(ToolKind.EXECUTE)
                .requiresApproval("Run this command?", 60)
                .executor(args -> {
                    counter.incrementAndGet();
                    return "ran:" + args.get("cmd");
                })
                .build();
    }

    /** Records whether it was consulted and always returns DENIED if it is. */
    private static final class DenyingStrategy implements ApprovalStrategy {
        final AtomicReference<PendingApproval> consulted = new AtomicReference<>();

        @Override
        public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session) {
            consulted.set(approval);
            return ApprovalOutcome.DENIED;
        }
    }

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
    void acceptEditsAutoApprovesEditKindToolWithoutConsultingStrategy() {
        var count = new AtomicInteger();
        var strategy = new DenyingStrategy();

        var result = ToolExecutionHelper.executeWithApproval(
                "apply_patch", editTool(count), Map.of("path", "src/Main.java"),
                new NoopSession(), strategy, null, ACCEPT_EDITS_SCOPE);

        assertTrue(result.contains("patched:src/Main.java"),
                "EDIT-kind tool must execute under ACCEPT_EDITS, got: " + result);
        assertEquals(1, count.get(), "edit tool must run exactly once");
        assertNull(strategy.consulted.get(),
                "ACCEPT_EDITS must auto-approve the edit tool without prompting the strategy");
    }

    @Test
    void acceptEditsStillPromptsNonEditTool() {
        var count = new AtomicInteger();
        var strategy = new DenyingStrategy();

        var result = ToolExecutionHelper.executeWithApproval(
                "run_shell", shellTool(count), Map.of("cmd", "rm -rf /"),
                new NoopSession(), strategy, null, ACCEPT_EDITS_SCOPE);

        assertTrue(result.contains("cancelled"),
                "non-EDIT tool must still go through approval (denied here), got: " + result);
        assertEquals(0, count.get(), "denied non-edit tool must not execute");
        assertEquals("run_shell", strategy.consulted.get().toolName(),
                "ACCEPT_EDITS must NOT auto-approve a non-edit tool — the strategy must be consulted");
    }

    @Test
    void acceptEditsNeverOverridesDenyAllPolicy() {
        var count = new AtomicInteger();
        var strategy = new DenyingStrategy();

        var result = ToolExecutionHelper.executeWithApproval(
                "apply_patch", editTool(count), Map.of("path", "src/Main.java"),
                new NoopSession(), strategy, ToolApprovalPolicy.denyAll(), ACCEPT_EDITS_SCOPE);

        assertTrue(result.contains("cancelled"),
                "DenyAll policy must win over ACCEPT_EDITS auto-approval, got: " + result);
        assertEquals(0, count.get(),
                "an edit tool under a DenyAll policy must not execute even in ACCEPT_EDITS mode");
    }

    @Test
    void defaultModeDoesNotAutoApproveEditTool() {
        // The kind tag alone must not auto-approve — the auto-approval is gated
        // on PermissionMode.ACCEPT_EDITS. Under DEFAULT, an EDIT tool with
        // @RequiresApproval still prompts.
        var count = new AtomicInteger();
        var strategy = new DenyingStrategy();

        var result = ToolExecutionHelper.executeWithApproval(
                "apply_patch", editTool(count), Map.of("path", "src/Main.java"),
                new NoopSession(), strategy);

        assertTrue(result.contains("cancelled"),
                "EDIT tool under DEFAULT mode must still prompt (denied here), got: " + result);
        assertEquals(0, count.get(), "edit tool must not auto-execute outside ACCEPT_EDITS");
        assertEquals("apply_patch", strategy.consulted.get().toolName(),
                "DEFAULT mode must consult the approval strategy for an @RequiresApproval edit tool");
    }
}
