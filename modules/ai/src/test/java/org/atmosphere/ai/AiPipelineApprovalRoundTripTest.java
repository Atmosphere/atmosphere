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

import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the full non-websocket HITL approval round-trip:
 *
 * <ol>
 *   <li>VT-A calls {@link AiPipeline#execute} with a {@code @RequiresApproval}
 *       tool. The fake runtime routes the invocation through the unified
 *       {@link ToolExecutionHelper#executeWithApproval} seam, which parks VT-A
 *       on the pipeline's {@link org.atmosphere.ai.approval.ApprovalRegistry}
 *       awaiting the client's decision.</li>
 *   <li>VT-B calls {@link AiPipeline#tryResolveApproval} with the protocol
 *       message {@code /__approval/&lt;id&gt;/approve} — the same wire format
 *       a channel bridge ({@code ChannelAiBridge}, {@code AgentProcessor},
 *       {@code CoordinatorProcessor}, {@code AgUiHandler}) sees when a user
 *       clicks "approve".</li>
 *   <li>VT-A unparks, the tool's executor fires, and VT-A's pipeline call
 *       returns with the real tool result streamed to the session.</li>
 * </ol>
 *
 * <p>This is the regression test ChefFamille's review asked for: previously
 * the guard in {@link org.atmosphere.ai.processor.AiEndpointHandler} was
 * untested end-to-end against a parked virtual thread. Now there's explicit
 * proof that the guard actually wakes the waiting VT within the same JVM —
 * the contract every non-websocket call site (A2A, channels, coordinator,
 * AG-UI) relies on.</p>
 */
class AiPipelineApprovalRoundTripTest {

    /** Fake runtime: invokes every registered tool through executeWithApproval. */
    private static final class ToolInvokingRuntime implements AgentRuntime {
        @Override public String name() { return "tool-invoking-fake"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.TOOL_CALLING);
        }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            for (var tool : context.tools()) {
                var args = new java.util.HashMap<String, Object>();
                for (var p : tool.parameters()) {
                    args.put(p.name(), "sample");
                }
                var result = ToolExecutionHelper.executeWithApproval(
                        tool.name(), tool, args, session, context.approvalStrategy());
                session.send(result);
            }
            session.complete();
        }
    }

    private static final class CollectingSession implements StreamingSession {
        private final StringBuilder buf = new StringBuilder();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean closed;
        @Override public String sessionId() { return "round-trip"; }
        @Override public synchronized void send(String text) { buf.append(text); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; done.countDown(); }
        @Override public void complete(String summary) { closed = true; done.countDown(); }
        @Override public void error(Throwable t) { closed = true; done.countDown(); }
        @Override public boolean isClosed() { return closed; }
        void await(long seconds) throws InterruptedException {
            done.await(seconds, TimeUnit.SECONDS);
        }
        String text() { return buf.toString(); }
    }

    @Test
    void approvalMessageUnparksWaitingVirtualThread() throws Exception {
        var executorFired = new AtomicInteger();

        var sensitive = ToolDefinition.builder("delete_record", "delete a record")
                .parameter("id", "record id", "string")
                .executor(args -> {
                    executorFired.incrementAndGet();
                    return "deleted:" + args.get("id");
                })
                .requiresApproval("Approve deletion?", 30)
                .build();

        var registry = new DefaultToolRegistry();
        registry.register(sensitive);

        var pipeline = new AiPipeline(new ToolInvokingRuntime(), null, null,
                null, registry, java.util.List.of(), java.util.List.of(), AiMetrics.NOOP);

        // Hook the pipeline's internal registry by snapshotting the PendingApproval
        // as soon as the strategy is consulted. The pipeline creates its own
        // ApprovalStrategy.virtualThread(registry), so we intercept via a listener
        // on AbstractAgentRuntime? Simpler: poll the pipeline's approvalRegistry()
        // for the first registered approval.
        var approvalId = new AtomicReference<String>();
        var pipelineRegistry = pipeline.approvalRegistry();

        // Poller thread — reads the first pending approval ID out of the
        // registry as soon as executeWithApproval has registered it.
        Thread.startVirtualThread(() -> {
            var deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    var field = pipelineRegistry.getClass().getDeclaredField("pending");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var map = (java.util.concurrent.ConcurrentHashMap<String, ?>) field.get(pipelineRegistry);
                    if (!map.isEmpty()) {
                        approvalId.set(map.keys().nextElement());
                        return;
                    }
                    Thread.sleep(10);
                } catch (Exception e) {
                    return;
                }
            }
        });

        // VT-A: fire the pipeline. This parks inside executeWithApproval until
        // the approval is resolved or times out.
        var session = new CollectingSession();
        var vtA = Thread.startVirtualThread(() ->
                pipeline.execute("conv-1", "delete record 42", session));

        // Wait for the poller to catch the pending approval ID.
        var deadline = System.currentTimeMillis() + 5_000;
        while (approvalId.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertNotNull(approvalId.get(),
                "pipeline.execute should have registered a pending approval within 5s");

        // VT-B: fire the approval message through the same public API that
        // channel bridges use.
        var resolved = pipeline.tryResolveApproval(
                "/__approval/" + approvalId.get() + "/approve");
        assertTrue(resolved,
                "pipeline.tryResolveApproval must return true for a known approval ID");

        // VT-A must now unpark, execute the tool, and complete the session.
        session.await(5);
        vtA.join(5_000);

        assertEquals(1, executorFired.get(),
                "The @RequiresApproval tool's delegate must have executed exactly once");
        assertTrue(session.text().contains("deleted:sample"),
                "Session must contain the tool result: " + session.text());
        assertTrue(session.isClosed(), "Session must be completed");
    }
}
