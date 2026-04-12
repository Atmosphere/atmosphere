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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 guarantee that Spring AI's {@code AtmosphereToolCallback} routes
 * {@code @RequiresApproval} tools through the unified
 * {@link ToolExecutionHelper#executeWithApproval} call site.
 */
class SpringAiToolBridgeApprovalTest {

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
        @Override public String sessionId() { return "spring-ai-test"; }
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
    void approvedCallbackRunsDelegate() {
        var counter = new AtomicInteger();
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.APPROVED);

        var callbacks = SpringAiToolBridge.toToolCallbacks(
                List.of(sensitive(counter)), new NoopSession(), strategy, List.of(), null);

        var result = callbacks.get(0).call("{\"userId\":\"u1\"}");

        assertEquals("deleted:u1", result);
        assertEquals(1, counter.get());
        assertNotNull(strategy.last.get());
        assertEquals("delete_account", strategy.last.get().toolName());
    }

    @Test
    void deniedCallbackReturnsCancelled() {
        var counter = new AtomicInteger();
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.DENIED);

        var callbacks = SpringAiToolBridge.toToolCallbacks(
                List.of(sensitive(counter)), new NoopSession(), strategy, List.of(), null);

        var result = callbacks.get(0).call("{\"userId\":\"u1\"}");

        assertTrue(result.contains("cancelled"));
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

        var callbacks = SpringAiToolBridge.toToolCallbacks(List.of(tool), null, null, List.of(), null);
        assertEquals("echo:hi", callbacks.get(0).call("{\"value\":\"hi\"}"));
        assertEquals(1, counter.get());
    }
}
