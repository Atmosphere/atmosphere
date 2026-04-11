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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: Phase 10's {@link ToolArgumentValidator} was shipped but not
 * invoked by any runtime bridge. This test wires validation into
 * {@link ToolExecutionHelper#executeWithApproval} and
 * {@link ToolExecutionHelper#executeAndFormat(ToolDefinition, Map)} so a
 * missing required parameter or a type mismatch produces a structured
 * {@code invalid_arguments} JSON error uniformly across all runtimes,
 * without invoking the tool executor.
 */
class ToolExecutionHelperValidationTest {

    private static ToolDefinition strictTool(AtomicBoolean executorFired) {
        return ToolDefinition.builder("delete_record", "delete a record")
                .parameter("id", "record id", "string", true)
                .parameter("confirm", "confirmation flag", "boolean", true)
                .executor(args -> {
                    executorFired.set(true);
                    return "deleted:" + args.get("id");
                })
                .build();
    }

    @Test
    void missingRequiredParameterReturnsStructuredError() {
        var executorFired = new AtomicBoolean();
        var tool = strictTool(executorFired);

        var result = ToolExecutionHelper.executeAndFormat(tool, Map.of("id", "r-1"));

        assertTrue(result.contains("\"error\":\"invalid_arguments\""), result);
        assertTrue(result.contains("missing required parameter 'confirm'"), result);
        assertFalse(executorFired.get(),
                "executor must NOT fire when validation rejects the arguments");
    }

    @Test
    void wrongTypeReturnsStructuredError() {
        var executorFired = new AtomicBoolean();
        var tool = strictTool(executorFired);

        var result = ToolExecutionHelper.executeAndFormat(
                tool, Map.of("id", "r-1", "confirm", "yes-please"));

        assertTrue(result.contains("\"error\":\"invalid_arguments\""), result);
        assertTrue(result.contains("parameter 'confirm' must be a boolean"), result);
        assertFalse(executorFired.get());
    }

    @Test
    void validArgumentsFallThroughToExecutor() {
        var executorFired = new AtomicBoolean();
        var tool = strictTool(executorFired);

        var result = ToolExecutionHelper.executeAndFormat(
                tool, Map.of("id", "r-1", "confirm", true));

        assertTrue(executorFired.get(),
                "executor must fire when validation passes");
        assertTrue(result.contains("deleted:r-1"), result);
    }

    @Test
    void executeWithApprovalValidatesBeforeGate() {
        var executorFired = new AtomicBoolean();
        var tool = strictTool(executorFired);

        // Strategy that would approve if ever consulted; the validator failure
        // must short-circuit before the gate is reached.
        var approvalInvoked = new AtomicBoolean();
        var alwaysApprove = new org.atmosphere.ai.approval.ApprovalStrategy() {
            @Override
            public org.atmosphere.ai.approval.ApprovalStrategy.ApprovalOutcome awaitApproval(
                    org.atmosphere.ai.approval.PendingApproval approval,
                    org.atmosphere.ai.StreamingSession session) {
                approvalInvoked.set(true);
                return org.atmosphere.ai.approval.ApprovalStrategy.ApprovalOutcome.APPROVED;
            }
        };

        var session = new NoopSession();
        var result = ToolExecutionHelper.executeWithApproval(
                "delete_record", tool, Map.of("id", "r-1"), session, alwaysApprove);

        assertTrue(result.contains("\"error\":\"invalid_arguments\""), result);
        assertFalse(executorFired.get());
        assertFalse(approvalInvoked.get(),
                "approval strategy must NOT be consulted when validation fails");
    }

    private static final class NoopSession implements org.atmosphere.ai.StreamingSession {
        @Override public String sessionId() { return "noop"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }
}
