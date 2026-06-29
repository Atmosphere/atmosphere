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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the headline claim from the Atmosphere 4 blog: the money-moving
 * {@code issue_refund} tool is gated behind a human via {@code @RequiresApproval},
 * and the refund <em>does not post</em> until a human approves.
 *
 * <p>The {@link ToolDefinition} under test is built from the sample's real
 * {@link AssistantTools#issueRefund(String)} method by {@link DefaultToolRegistry}
 * — so it carries the actual {@code @RequiresApproval} annotation, not a
 * hand-copied stand-in. The assertion is on the observable side effect (the
 * in-memory refund ledger), not on the mere presence of the annotation:</p>
 * <ul>
 *   <li><b>denied</b> approval → ledger untouched, no money moves;</li>
 *   <li><b>timed-out</b> approval → ledger untouched;</li>
 *   <li><b>no ApprovalStrategy wired</b> → fail-closed, ledger untouched;</li>
 *   <li><b>approved</b> approval → ledger gains exactly the order's amount.</li>
 * </ul>
 */
class RefundApprovalGateTest {

    private static final String ORDER_ID = "A-1001";
    /** Matches the seeded order total in {@link AssistantTools} ($49.99). */
    private static final long EXPECTED_REFUND_CENTS = 4999L;

    /** Resolves every approval request to a fixed, caller-chosen outcome. */
    private record FixedOutcomeStrategy(ApprovalOutcome outcome) implements ApprovalStrategy {
        @Override
        public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session) {
            return outcome;
        }
    }

    /** Minimal StreamingSession so PendingApproval.sessionId() resolves. */
    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() {
            return "refund-test-session";
        }

        @Override public void send(String text) {
        }

        @Override public void sendMetadata(String key, Object value) {
        }

        @Override public void progress(String message) {
        }

        @Override public void complete() {
        }

        @Override public void complete(String summary) {
        }

        @Override public void error(Throwable t) {
        }

        @Override public boolean isClosed() {
            return false;
        }
    }

    /**
     * Build the {@code issue_refund} {@link ToolDefinition} from the real
     * annotated sample method. The returned definition's executor mutates the
     * supplied {@link AssistantTools} instance's ledger, so the test observes
     * the genuine side effect.
     */
    private static ToolDefinition refundTool(AssistantTools tools) {
        var registry = new DefaultToolRegistry();
        registry.register(tools);
        return registry.getTool("issue_refund").orElseThrow(
                () -> new AssertionError("issue_refund tool not registered from @AiTool method"));
    }

    @Test
    void refundToolIsGatedByRequiresApproval() {
        var tool = refundTool(new AssistantTools());
        assertTrue(tool.requiresApproval(),
                "issue_refund must carry @RequiresApproval so it cannot move money unattended");
    }

    @Test
    void refundIsWithheldWhenApprovalIsDenied() {
        var tools = new AssistantTools();
        var tool = refundTool(tools);

        var result = ToolExecutionHelper.executeWithApproval(
                "issue_refund", tool, Map.of("orderId", ORDER_ID), new NoopSession(),
                new FixedOutcomeStrategy(ApprovalStrategy.ApprovalOutcome.DENIED));

        assertTrue(result.contains("cancelled"),
                "denied refund must return a cancellation, got: " + result);
        assertEquals(0L, tools.refundedCents(ORDER_ID),
                "no money may move while the refund is denied");
        assertEquals(0, tools.refundCount(),
                "the refund ledger must stay empty when approval is denied");
    }

    @Test
    void refundIsWithheldWhenApprovalTimesOut() {
        var tools = new AssistantTools();
        var tool = refundTool(tools);

        var result = ToolExecutionHelper.executeWithApproval(
                "issue_refund", tool, Map.of("orderId", ORDER_ID), new NoopSession(),
                new FixedOutcomeStrategy(ApprovalStrategy.ApprovalOutcome.TIMED_OUT));

        assertTrue(result.contains("timeout"),
                "timed-out refund must return a timeout, got: " + result);
        assertEquals(0L, tools.refundedCents(ORDER_ID),
                "no money may move while the refund approval is still pending/timed out");
    }

    @Test
    void refundIsWithheldWhenNoApprovalStrategyIsWired() {
        var tools = new AssistantTools();
        var tool = refundTool(tools);

        // Fail-closed: a money-moving tool must never run on a code path that
        // forgot to wire an ApprovalStrategy.
        var result = ToolExecutionHelper.executeWithApproval(
                "issue_refund", tool, Map.of("orderId", ORDER_ID), new NoopSession(), null);

        assertTrue(result.contains("cancelled"),
                "un-wired approval must fail closed, got: " + result);
        assertEquals(0L, tools.refundedCents(ORDER_ID),
                "no money may move when no ApprovalStrategy is wired");
    }

    @Test
    void refundPostsOnlyAfterApproval() {
        var tools = new AssistantTools();
        var tool = refundTool(tools);

        // Pre-condition: nothing posted yet.
        assertEquals(0L, tools.refundedCents(ORDER_ID),
                "ledger must start empty before any approval");

        var result = ToolExecutionHelper.executeWithApproval(
                "issue_refund", tool, Map.of("orderId", ORDER_ID), new NoopSession(),
                new FixedOutcomeStrategy(ApprovalStrategy.ApprovalOutcome.APPROVED));

        assertTrue(result.contains("posted"),
                "approved refund must confirm it posted, got: " + result);
        assertEquals(EXPECTED_REFUND_CENTS, tools.refundedCents(ORDER_ID),
                "approved refund must post exactly the order amount to the ledger");
        assertEquals(1, tools.refundCount(),
                "exactly one order should have a posted refund after approval");
    }
}
