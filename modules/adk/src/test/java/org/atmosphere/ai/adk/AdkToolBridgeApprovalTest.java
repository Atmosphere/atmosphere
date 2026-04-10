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
package org.atmosphere.ai.adk;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 HITL guarantee for the ADK bridge: {@code AtmosphereAdkTool.runAsync}
 * must route every invocation through {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval}
 * so {@code @RequiresApproval} tools are gated by Atmosphere's single source of truth,
 * regardless of ADK's native {@code ToolContext.requestConfirmation} side effect.
 *
 * <p>The ADK {@code ToolContext} is passed as {@code null} here — the bridge tolerates a
 * null ToolContext because the native {@code requestConfirmation} call is observability-only
 * and the authoritative path is {@code executeWithApproval}. This mirrors how unit tests in
 * the LC4j, Spring AI, and Koog modules exercise the Phase 0 call site.</p>
 */
class AdkToolBridgeApprovalTest {

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

    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() { return "adk-test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }

    private static ToolDefinition sensitive(AtomicInteger counter) {
        return ToolDefinition.builder("delete_account", "Permanently delete")
                .parameter("userId", "user id", "string")
                .executor(args -> {
                    counter.incrementAndGet();
                    return "deleted:" + args.get("userId");
                })
                .requiresApproval("Approve delete?", 60)
                .build();
    }

    @Test
    void approvedAdkToolRunsDelegate() {
        var counter = new AtomicInteger();
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.APPROVED);

        var tools = AdkToolBridge.toAdkTools(
                List.of(sensitive(counter)), new NoopSession(), strategy);
        assertEquals(1, tools.size());

        var result = tools.get(0).runAsync(Map.of("userId", "u1"), null).blockingGet();

        assertEquals("success", result.get("status"));
        assertEquals("deleted:u1", result.get("result"));
        assertEquals(1, counter.get());
        assertNotNull(strategy.last.get());
        assertEquals("delete_account", strategy.last.get().toolName());
    }

    @Test
    void deniedAdkToolSkipsDelegate() {
        var counter = new AtomicInteger();
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.DENIED);

        var tools = AdkToolBridge.toAdkTools(
                List.of(sensitive(counter)), new NoopSession(), strategy);

        var result = tools.get(0).runAsync(Map.of("userId", "u1"), null).blockingGet();

        assertEquals("success", result.get("status"));
        assertTrue(result.get("result").toString().contains("cancelled"),
                "denied approvals must surface the cancellation payload");
        assertEquals(0, counter.get(),
                "denied approvals must not run the underlying executor");
    }

    @Test
    void timedOutAdkToolSkipsDelegate() {
        var counter = new AtomicInteger();
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.TIMED_OUT);

        var tools = AdkToolBridge.toAdkTools(
                List.of(sensitive(counter)), new NoopSession(), strategy);

        var result = tools.get(0).runAsync(Map.of("userId", "u1"), null).blockingGet();

        assertEquals("success", result.get("status"));
        assertTrue(result.get("result").toString().contains("timeout"));
        assertEquals(0, counter.get());
    }

    @Test
    void nullStrategyFallsBackToDirectExecution() {
        var counter = new AtomicInteger();
        var tool = ToolDefinition.builder("echo", "echo")
                .parameter("value", "value", "string")
                .executor(args -> {
                    counter.incrementAndGet();
                    return "echo:" + args.get("value");
                })
                .build();

        var tools = AdkToolBridge.toAdkTools(
                List.of(tool), new NoopSession(), null);

        var result = tools.get(0).runAsync(Map.of("value", "hi"), null).blockingGet();
        assertEquals("success", result.get("status"));
        assertEquals("echo:hi", result.get("result"));
        assertEquals(1, counter.get());
    }
}
