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
import org.atmosphere.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage that {@link AiPipeline#execute} routes the per-stage
 * input breakdown through {@link AiMetrics#recordInputAssembly} on the
 * runtime-bound path, and skips the emission entirely when the metrics
 * sink is the {@link AiMetrics#NOOP} default.
 */
class AiPipelineInputAssemblyTest {

    static final class RecordingRuntime implements AgentRuntime {
        final AtomicReference<AgentExecutionContext> lastContext = new AtomicReference<>();
        @Override public String name() { return "recording"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            lastContext.set(context);
            session.send("ok");
            session.complete();
        }
    }

    static final class CapturingSession implements StreamingSession {
        boolean completed;
        @Override public String sessionId() { return "test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { completed = true; }
        @Override public void complete(String summary) { completed = true; }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return completed; }
    }

    static final class RecordingMetrics implements AiMetrics {
        record Entry(String model, String stage, int tokens, int chars) { }
        final List<Entry> entries = new ArrayList<>();
        @Override public void recordStreamingTextUsage(String m, int p, int c) { }
        @Override public void recordLatency(String m, Duration ttf, Duration t) { }
        @Override public void recordCost(String m, BigDecimal c) { }
        @Override public void recordToolCall(String m, String t, Duration d, boolean s) { }
        @Override public void recordError(String m, String t) { }
        @Override
        public void recordInputAssembly(String model, String stage, int tokens, int chars) {
            entries.add(new Entry(model, stage, tokens, chars));
        }

        Entry stage(String name) {
            return entries.stream().filter(e -> e.stage().equals(name))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("no entry for stage " + name + ": " + entries));
        }
    }

    private static ToolDefinition tool(String name, String desc) {
        ToolExecutor exec = args -> "ok";
        return ToolDefinition.builder(name, desc)
                .parameter("query", "search query", "string")
                .returnType("string")
                .executor(exec)
                .build();
    }

    @Test
    void emitsSystemAndUserMessageStagesOnPlainExecute() {
        var runtime = new RecordingRuntime();
        var metrics = new RecordingMetrics();
        var pipeline = new AiPipeline(runtime, "you are a helpful assistant", "gpt-4",
                null, null, List.of(), List.of(), metrics);

        pipeline.execute("client-1", "hello world", new CapturingSession());

        var system = metrics.stage(InputAssemblyTelemetry.STAGE_SYSTEM);
        assertEquals("gpt-4", system.model());
        assertEquals("you are a helpful assistant".length(), system.chars());

        var user = metrics.stage(InputAssemblyTelemetry.STAGE_USER_MESSAGE);
        assertEquals("hello world".length(), user.chars());

        // Stages absent on a tool-free, history-free turn
        var stages = metrics.entries.stream().map(RecordingMetrics.Entry::stage).toList();
        assertFalse(stages.contains(InputAssemblyTelemetry.STAGE_TOOL_SCHEMA));
        assertFalse(stages.contains(InputAssemblyTelemetry.STAGE_SCROLLBACK));
        assertFalse(stages.contains(InputAssemblyTelemetry.STAGE_STRUCTURED_OUTPUT_SCHEMA));
        assertFalse(stages.contains(InputAssemblyTelemetry.STAGE_CONFIDENCE_CUE));
    }

    @Test
    void emitsToolSchemaStageWhenToolsAttached() {
        var runtime = new RecordingRuntime();
        var metrics = new RecordingMetrics();
        var registry = new DefaultToolRegistry();
        registry.register(tool("search_web", "Search the public web for a query"));

        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, registry, List.of(), List.of(), metrics);

        pipeline.execute("client-1", "find atmosphere docs", new CapturingSession());

        var toolStage = metrics.stage(InputAssemblyTelemetry.STAGE_TOOL_SCHEMA);
        assertTrue(toolStage.chars() > 0, "tool schema stage should have non-zero chars");
        assertTrue(toolStage.tokens() > 0, "tool schema stage should have non-zero tokens");
    }

    @Test
    void emitsScrollbackStageWhenHistoryNonEmpty() {
        var runtime = new RecordingRuntime();
        var metrics = new RecordingMetrics();
        var memory = new InMemoryConversationMemory();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                memory, null, List.of(), List.of(), metrics);

        pipeline.execute("client-1", "first message", new CapturingSession());
        // First turn has empty history; reset metrics, run a second turn that
        // replays the first.
        metrics.entries.clear();
        pipeline.execute("client-1", "second message", new CapturingSession());

        var scrollback = metrics.stage(InputAssemblyTelemetry.STAGE_SCROLLBACK);
        assertTrue(scrollback.chars() > 0,
                "scrollback should reflect prior turn contents on the second call");
    }

    @Test
    void noopMetricsSkipsEmissionEntirely() {
        var runtime = new RecordingRuntime();
        // Pipeline takes null and substitutes AiMetrics.NOOP — exercise that
        // the short-circuit in InputAssemblyTelemetry.emit fires.
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "hello", session);

        // No assertion on metrics; the test is that execute completes
        // without exercising a recording metrics implementation.
        assertTrue(session.completed);
    }

    @Test
    void misbehavingMetricsDoesNotAbortTurn() {
        var runtime = new RecordingRuntime();
        AiMetrics throwing = new AiMetrics() {
            @Override public void recordStreamingTextUsage(String m, int p, int c) { }
            @Override public void recordLatency(String m, Duration ttf, Duration t) { }
            @Override public void recordCost(String m, BigDecimal c) { }
            @Override public void recordToolCall(String m, String t, Duration d, boolean s) { }
            @Override public void recordError(String m, String t) { }
            @Override
            public void recordInputAssembly(String model, String stage, int tokens, int chars) {
                throw new IllegalStateException("metrics down");
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), throwing);
        var session = new CapturingSession();

        pipeline.execute("client-1", "hello", session);

        assertTrue(session.completed);
        assertEquals("hello", runtime.lastContext.get().message());
    }

    @Test
    void modelLabelMatchesRequestModel() {
        var runtime = new RecordingRuntime();
        var metrics = new RecordingMetrics();
        var pipeline = new AiPipeline(runtime, "system", "claude-opus-4-7",
                null, null, List.of(), List.of(), metrics);

        pipeline.execute("client-1", "hi", new CapturingSession());

        assertTrue(metrics.entries.stream()
                        .allMatch(e -> "claude-opus-4-7".equals(e.model())),
                "every assembly entry should carry the configured model: " + metrics.entries);
    }

    @Test
    void extraMetadataDoesNotBreakAssembly() {
        var runtime = new RecordingRuntime();
        var metrics = new RecordingMetrics();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), metrics);

        pipeline.execute("client-1", "hi", new CapturingSession(),
                Map.of("custom.key", "custom.value"));

        // Sanity: assembly emitted as usual; metadata threading didn't
        // perturb the breakdown.
        var system = metrics.stage(InputAssemblyTelemetry.STAGE_SYSTEM);
        assertEquals("system".length(), system.chars());
    }
}
