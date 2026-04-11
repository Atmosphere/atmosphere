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
package org.atmosphere.ai;

import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.tool.DefaultToolRegistry;
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
 * Regression test for the 2026-04-11 Phase 0 review finding — {@link AiPipeline}
 * was building an {@link AgentExecutionContext} via the 14-arg constructor with
 * {@code approvalStrategy = null}, so every @Agent / @Coordinator / AG-UI /
 * channel path silently bypassed HITL gating even though the runtime bridges
 * all routed through {@link ToolExecutionHelper#executeWithApproval}. This test
 * builds a pipeline with a {@code @RequiresApproval} tool, drives a synthetic
 * runtime that invokes the tool, and asserts the pipeline's
 * {@link ApprovalStrategy} was consulted — closing Correctness Invariant #7
 * (Mode Parity) between the websocket path and every non-websocket caller.
 */
class AiPipelineHitlTest {

    /** A runtime that, instead of calling a model, just invokes every registered tool once through executeWithApproval. */
    private static final class ToolInvokingRuntime implements AgentRuntime {
        final AtomicReference<PendingApproval> observed = new AtomicReference<>();

        @Override public String name() { return "tool-invoking-fake"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            // Simulate an LLM that requested every tool. Route through the
            // unified HITL helper exactly like the real runtime bridges do —
            // this is the path the gap regression has to prove still works.
            for (var tool : context.tools()) {
                // Build args that satisfy the tool's declared parameter list so
                // ToolArgumentValidator (Phase 10, wired into executeWithApproval)
                // doesn't reject the call before the gate or the executor fires.
                var args = new java.util.HashMap<String, Object>();
                for (var p : tool.parameters()) {
                    args.put(p.name(), sampleValue(p.type()));
                }
                var wrappedStrategy = context.approvalStrategy() == null ? null : new ApprovalStrategy() {
                    @Override
                    public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession s) {
                        observed.set(approval);
                        return context.approvalStrategy().awaitApproval(approval, s);
                    }
                };
                ToolExecutionHelper.executeWithApproval(
                        tool.name(), tool, args, session, wrappedStrategy);
            }
            session.complete();
        }

        private static Object sampleValue(String jsonType) {
            return switch (jsonType) {
                case "string" -> "sample";
                case "integer" -> 1L;
                case "number" -> 1.0;
                case "boolean" -> true;
                default -> "sample";
            };
        }
    }

    /** Stub strategy that auto-approves — equivalent to "user clicked approve". */
    private static final class AutoApprove implements ApprovalStrategy {
        @Override
        public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session) {
            return ApprovalOutcome.APPROVED;
        }
    }

    @Test
    void pipelineExecuteHonorsRequiresApprovalOnNonWebsocketPath() {
        var delegateInvocations = new AtomicInteger();
        var sensitiveTool = ToolDefinition.builder("delete_account", "Permanently delete an account")
                .parameter("userId", "User id", "string")
                .executor(args -> {
                    delegateInvocations.incrementAndGet();
                    return "deleted:" + args.get("userId");
                })
                .requiresApproval("Permanently delete account?", 1)
                .build();

        var registry = new DefaultToolRegistry();
        registry.register(sensitiveTool);

        var runtime = new ToolInvokingRuntime();
        var pipeline = new AiPipeline(runtime, null, null, null, registry,
                List.of(), List.of(), AiMetrics.NOOP);

        var collector = new CollectingSession();
        pipeline.execute("conv-1", "please delete u-test", collector);
        collector.await(java.time.Duration.ofSeconds(1));

        // The pipeline's ApprovalRegistry is the single source of truth for
        // HITL decisions on non-websocket paths. The strategy must have been
        // consulted (observed a non-null PendingApproval) — proving the 15-arg
        // constructor is actually being used.
        assertNotNull(runtime.observed.get(),
                "pipeline.execute() must thread a non-null ApprovalStrategy into the runtime bridge");
        assertEquals("delete_account", runtime.observed.get().toolName());
        // Because the pipeline's default strategy parks on a future that only
        // resolves via tryResolveApproval, we get a timeout-shaped cancellation
        // path when no client responds — the tool's delegate executor must NOT
        // have been invoked in that case.
        assertEquals(0, delegateInvocations.get(),
                "@RequiresApproval tool must not execute when the pipeline's default strategy gets no approval");
    }

    @Test
    void pipelineTryResolveApprovalAcceptsProtocolMessages() {
        var registry = new DefaultToolRegistry();
        registry.register(ToolDefinition.builder("echo", "echo")
                .parameter("v", "value", "string")
                .executor(args -> args.get("v"))
                .build());

        var runtime = new ToolInvokingRuntime();
        var pipeline = new AiPipeline(runtime, null, null, null, registry,
                List.of(), List.of(), AiMetrics.NOOP);

        // Non-approval messages are ignored, approval-shaped messages are consumed.
        assertEquals(false, pipeline.tryResolveApproval("hello"));
        assertEquals(true, pipeline.tryResolveApproval("/__approval/apr_nonexistent/approve"));
        assertNotNull(pipeline.approvalRegistry(),
                "pipeline must expose its ApprovalRegistry so callers can share it across invocations");
    }

    @Test
    void toolsWithoutApprovalBypassTheGate() {
        var invocations = new AtomicInteger();
        var registry = new DefaultToolRegistry();
        registry.register(ToolDefinition.builder("list_rows", "readonly query")
                .parameter("table", "table name", "string")
                .executor(args -> {
                    invocations.incrementAndGet();
                    return "ok";
                })
                .build());

        var runtime = new ToolInvokingRuntime();
        var pipeline = new AiPipeline(runtime, null, null, null, registry,
                List.of(), List.of(), AiMetrics.NOOP);

        var collector = new CollectingSession();
        pipeline.execute("conv-2", "list rows", collector);

        // executeWithApproval falls through to direct execution when the tool
        // does not require approval — this proves the gate only fires for
        // @RequiresApproval tools and that the fix does not cause collateral
        // damage to readonly tool invocations on non-websocket paths.
        assertEquals(1, invocations.get(),
                "readonly tools must still run unchanged after the HITL gap fix");
        assertTrue(runtime.observed.get() == null,
                "approval strategy must not be consulted for non-@RequiresApproval tools");
    }
}
