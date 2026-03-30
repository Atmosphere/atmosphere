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
package org.atmosphere.ai.approval;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalGateExecutorTest {

    private ApprovalRegistry registry;
    private ApprovalStrategy strategy;
    private TestSession session;

    @BeforeEach
    void setUp() {
        registry = new ApprovalRegistry();
        strategy = ApprovalStrategy.virtualThread(registry);
        session = new TestSession();
    }

    @Test
    void approvedToolExecutes() throws Exception {
        var executed = new boolean[]{false};
        var executor = new ApprovalGateExecutor(
                args -> { executed[0] = true; return "done"; },
                "delete_account", "Are you sure?", 60,
                strategy, session
        );

        // Run on a VT so the approval gate can park it
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                return executor.execute(Map.of("userId", "u-1"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for the approval event to be emitted
        Thread.sleep(100);
        assertTrue(session.events.stream().anyMatch(
                e -> e instanceof AiEvent.ApprovalRequired), "Expected ApprovalRequired event");

        // Extract the approval ID from the emitted event
        var approvalEvent = session.events.stream()
                .filter(e -> e instanceof AiEvent.ApprovalRequired)
                .map(e -> (AiEvent.ApprovalRequired) e)
                .findFirst().orElseThrow();

        // Approve it
        registry.tryResolve("/__approval/" + approvalEvent.approvalId() + "/approve");

        var result = future.get();
        assertTrue(executed[0], "Delegate executor should have run");
        assertEquals("done", result.toString());
    }

    @Test
    void deniedToolReturnsCancelled() throws Exception {
        var executed = new boolean[]{false};
        var executor = new ApprovalGateExecutor(
                args -> { executed[0] = true; return "done"; },
                "delete_account", "Are you sure?", 60,
                strategy, session
        );

        var future = CompletableFuture.supplyAsync(() -> {
            try {
                return executor.execute(Map.of());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(100);
        var approvalEvent = session.events.stream()
                .filter(e -> e instanceof AiEvent.ApprovalRequired)
                .map(e -> (AiEvent.ApprovalRequired) e)
                .findFirst().orElseThrow();

        registry.tryResolve("/__approval/" + approvalEvent.approvalId() + "/deny");

        var result = future.get();
        assertFalse(executed[0], "Delegate executor should NOT have run");
        assertTrue(result.toString().contains("cancelled"));
    }

    @Test
    void toolDefinitionRequiresApprovalFlag() {
        var tool = org.atmosphere.ai.tool.ToolDefinition.builder("test", "test tool")
                .executor(args -> "ok")
                .requiresApproval("Confirm?")
                .build();

        assertTrue(tool.requiresApproval());
        assertEquals("Confirm?", tool.approvalMessage());
    }

    @Test
    void toolDefinitionWithoutApproval() {
        var tool = org.atmosphere.ai.tool.ToolDefinition.builder("test", "test tool")
                .executor(args -> "ok")
                .build();

        assertFalse(tool.requiresApproval());
        assertNull(tool.approvalMessage());
    }

    /** Minimal session for capturing events. */
    static class TestSession implements StreamingSession {
        final ArrayList<AiEvent> events = new ArrayList<>();

        @Override public String sessionId() { return "test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
        @Override public void emit(AiEvent event) { events.add(event); }
    }
}
