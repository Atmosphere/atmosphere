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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
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
 * Phase 0 guarantee that {@link LangChain4jToolBridge#executeToolCalls} routes
 * {@code @RequiresApproval} tools through the unified
 * {@link ToolExecutionHelper#executeWithApproval} call site.
 */
class LangChain4jToolBridgeApprovalTest {

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
        @Override public String sessionId() { return "lc4j-test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }

    @Test
    void approvedRunsDelegateAndReturnsResult() {
        var counter = new AtomicInteger();
        var tool = ToolDefinition.builder("delete_account", "Permanently delete")
                .parameter("userId", "user id", "string")
                .executor(args -> {
                    counter.incrementAndGet();
                    return "deleted:" + args.get("userId");
                })
                .requiresApproval("Approve delete?", 60)
                .build();
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.APPROVED);

        var aiMessage = AiMessage.from(List.of(
                ToolExecutionRequest.builder()
                        .id("tc-1").name("delete_account")
                        .arguments("{\"userId\":\"u1\"}")
                        .build()));

        var results = LangChain4jToolBridge.executeToolCalls(
                aiMessage, ToolExecutionHelper.toToolMap(List.of(tool)),
                new NoopSession(), strategy);

        assertEquals(1, results.size());
        assertEquals("deleted:u1", results.get(0).text());
        assertEquals(1, counter.get());
        assertNotNull(strategy.last.get());
        assertEquals("delete_account", strategy.last.get().toolName());
    }

    @Test
    void deniedSkipsDelegateAndReturnsCancelled() {
        var counter = new AtomicInteger();
        var tool = ToolDefinition.builder("delete_account", "Permanently delete")
                .parameter("userId", "user id", "string")
                .executor(args -> {
                    counter.incrementAndGet();
                    return "deleted:" + args.get("userId");
                })
                .requiresApproval("Approve delete?", 60)
                .build();
        var strategy = new RecordingStrategy(ApprovalStrategy.ApprovalOutcome.DENIED);

        var aiMessage = AiMessage.from(List.of(
                ToolExecutionRequest.builder()
                        .id("tc-1").name("delete_account")
                        .arguments("{\"userId\":\"u1\"}")
                        .build()));

        var results = LangChain4jToolBridge.executeToolCalls(
                aiMessage, ToolExecutionHelper.toToolMap(List.of(tool)),
                new NoopSession(), strategy);

        assertEquals(1, results.size());
        assertTrue(results.get(0).text().contains("cancelled"));
        assertEquals(0, counter.get(),
                "denied approvals must not run the underlying executor");
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

        var aiMessage = AiMessage.from(List.of(
                ToolExecutionRequest.builder()
                        .id("tc-1").name("echo")
                        .arguments("{\"value\":\"hi\"}")
                        .build()));

        var results = LangChain4jToolBridge.executeToolCalls(
                aiMessage, ToolExecutionHelper.toToolMap(List.of(tool)),
                null, null);

        assertEquals("echo:hi", results.get(0).text());
        assertEquals(1, counter.get());
    }
}
