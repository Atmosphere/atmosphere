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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the guardrail tool-filtering bypass. Previously,
 * {@link AiPipeline#execute} and {@link AiStreamingSession#stream} attached
 * {@code toolRegistry.allTools()} to the request, ran guardrails, then threw
 * away the modified request's tool list and re-read the full registry when
 * building the execution context. A guardrail that dropped a sensitive tool
 * via {@code request.withTools(filtered)} was silently ignored — the full
 * tool set still reached the runtime.
 *
 * <p>This test drives {@link AiPipeline#execute} with a guardrail that drops
 * the {@code delete_account} tool and a fake runtime that captures the tool
 * list on the {@link AgentExecutionContext} it was handed, asserting the drop
 * actually took effect.</p>
 */
class GuardrailToolFilteringTest {

    private static ToolDefinition readTool(String name) {
        return ToolDefinition.builder(name, "test tool " + name)
                .parameter("q", "query", "string")
                .executor(args -> "ok")
                .build();
    }

    private static final class CapturingRuntime implements AgentRuntime {
        final AtomicReference<AgentExecutionContext> captured = new AtomicReference<>();

        @Override public String name() { return "capturing"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.TOOL_CALLING);
        }
        @Override public void execute(AgentExecutionContext context, StreamingSession session) {
            captured.set(context);
            session.complete();
        }
    }

    private static final class TextCollectingSession implements StreamingSession {
        private volatile boolean closed;
        @Override public String sessionId() { return "test-session"; }
        @Override public void send(String t) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }

    @Test
    void guardrailNarrowedToolListReachesRuntime() {
        var runtime = new CapturingRuntime();

        var registry = new DefaultToolRegistry();
        registry.register(readTool("read_file"));
        registry.register(readTool("delete_account"));

        // Guardrail that drops the sensitive tool via AiRequest#withTools.
        var dropDelete = new AiGuardrail() {
            @Override
            public GuardrailResult inspectRequest(AiRequest request) {
                var filtered = request.tools().stream()
                        .filter(t -> !"delete_account".equals(t.name()))
                        .toList();
                return GuardrailResult.modify(request.withTools(filtered));
            }
        };

        var pipeline = new AiPipeline(
                runtime, "you are helpful", null,
                null, registry,
                List.of(dropDelete), List.of(), AiMetrics.NOOP);

        pipeline.execute("test-client", "hello", new TextCollectingSession());

        var ctx = runtime.captured.get();
        assertNotNull(ctx, "runtime.execute must have been called");
        assertEquals(1, ctx.tools().size(),
                "guardrail drop must reach runtime: got " + ctx.tools());
        assertEquals("read_file", ctx.tools().get(0).name());
    }

    @Test
    void guardrailPassLeavesFullToolListIntact() {
        var runtime = new CapturingRuntime();

        var registry = new DefaultToolRegistry();
        registry.register(readTool("read_file"));
        registry.register(readTool("write_file"));

        var passThrough = new AiGuardrail() {
            @Override
            public GuardrailResult inspectRequest(AiRequest request) {
                return GuardrailResult.pass();
            }
        };

        var pipeline = new AiPipeline(
                runtime, "you are helpful", null,
                null, registry,
                List.of(passThrough), List.of(), AiMetrics.NOOP);

        pipeline.execute("test-client", "hello", new TextCollectingSession());

        var ctx = runtime.captured.get();
        assertEquals(2, ctx.tools().size(),
                "pass-through guardrail must not drop tools");
        assertTrue(ctx.tools().stream().anyMatch(t -> "read_file".equals(t.name())));
        assertTrue(ctx.tools().stream().anyMatch(t -> "write_file".equals(t.name())));
    }
}
