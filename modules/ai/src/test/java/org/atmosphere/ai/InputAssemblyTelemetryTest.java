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

import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-unit coverage for {@link InputAssemblyTelemetry}. Pipeline-level
 * integration is covered separately in {@link AiPipelineInputAssemblyTest}.
 */
class InputAssemblyTelemetryTest {

    /** Records every {@code recordInputAssembly} call for assertion. */
    static final class RecordingMetrics implements AiMetrics {
        record Entry(String model, String stage, int approximateTokens, int approximateChars) { }
        final List<Entry> entries = new ArrayList<>();
        @Override public void recordStreamingTextUsage(String m, int p, int c) { }
        @Override public void recordLatency(String m, Duration ttf, Duration t) { }
        @Override public void recordCost(String m, BigDecimal c) { }
        @Override public void recordToolCall(String m, String t, Duration d, boolean s) { }
        @Override public void recordError(String m, String t) { }
        @Override
        public void recordInputAssembly(String model, String stage,
                                        int approximateTokens, int approximateChars) {
            entries.add(new Entry(model, stage, approximateTokens, approximateChars));
        }
    }

    private static ToolDefinition tool(String name, String desc) {
        ToolExecutor exec = args -> "ok";
        return ToolDefinition.builder(name, desc)
                .parameter("p", "param description", "string")
                .returnType("string")
                .executor(exec)
                .build();
    }

    @Test
    void approximateTokensIsCharsOverFour() {
        assertEquals(0, InputAssemblyTelemetry.approximateTokens(0));
        assertEquals(0, InputAssemblyTelemetry.approximateTokens(-5));
        // chars/4 with a floor of 1 so a non-empty stage never disappears
        assertEquals(1, InputAssemblyTelemetry.approximateTokens(1));
        assertEquals(1, InputAssemblyTelemetry.approximateTokens(3));
        assertEquals(1, InputAssemblyTelemetry.approximateTokens(4));
        assertEquals(25, InputAssemblyTelemetry.approximateTokens(100));
        assertEquals(250, InputAssemblyTelemetry.approximateTokens(1000));
    }

    @Test
    void toolSchemaCharsSumsAllToolFields() {
        var t = tool("search_web", "Search the public web for a query");
        // name(10) + desc(34) + returnType "string"(6) + param name "p"(1) +
        // param desc "param description"(17) + param type "string"(6) = 74
        int expected = "search_web".length()
                + "Search the public web for a query".length()
                + "string".length()
                + "p".length()
                + "param description".length()
                + "string".length();
        assertEquals(expected, InputAssemblyTelemetry.toolSchemaChars(List.of(t)));
    }

    @Test
    void toolSchemaCharsIsZeroForEmptyOrNull() {
        assertEquals(0, InputAssemblyTelemetry.toolSchemaChars(null));
        assertEquals(0, InputAssemblyTelemetry.toolSchemaChars(List.of()));
    }

    @Test
    void scrollbackCharsSumsContentAcrossMessages() {
        var history = List.of(
                ChatMessage.user("hello"),
                ChatMessage.assistant("world!"),
                new ChatMessage("user", "second turn"));
        assertEquals("hello".length() + "world!".length() + "second turn".length(),
                InputAssemblyTelemetry.scrollbackChars(history));
    }

    @Test
    void scrollbackCharsIsZeroForEmptyOrNull() {
        assertEquals(0, InputAssemblyTelemetry.scrollbackChars(null));
        assertEquals(0, InputAssemblyTelemetry.scrollbackChars(List.of()));
    }

    @Test
    void emitFiresOncePerNonEmptyStage() {
        var metrics = new RecordingMetrics();
        var tools = List.of(tool("a", "alpha"));
        var history = List.of(ChatMessage.user("prior"));

        InputAssemblyTelemetry.emit(metrics, "gpt-4",
                "system prompt",
                "schema-instructions",
                "confidence-cue",
                tools,
                history,
                "user msg");

        var stages = metrics.entries.stream().map(RecordingMetrics.Entry::stage).toList();
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_SYSTEM));
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_TOOL_SCHEMA));
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_STRUCTURED_OUTPUT_SCHEMA));
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_CONFIDENCE_CUE));
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_SCROLLBACK));
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_USER_MESSAGE));
        // No duplicate stage emissions for a single emit() call
        assertEquals(stages.size(), stages.stream().distinct().count());
        // Every entry carries the model name as supplied
        assertTrue(metrics.entries.stream().allMatch(e -> "gpt-4".equals(e.model())));
    }

    @Test
    void emitSkipsStagesWithZeroChars() {
        var metrics = new RecordingMetrics();

        // No tools, no scrollback, no structured output, no confidence cue
        InputAssemblyTelemetry.emit(metrics, "gpt-4",
                "system",
                null,
                null,
                List.of(),
                List.of(),
                "msg");

        var stages = metrics.entries.stream().map(RecordingMetrics.Entry::stage).toList();
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_SYSTEM));
        assertTrue(stages.contains(InputAssemblyTelemetry.STAGE_USER_MESSAGE));
        assertFalse(stages.contains(InputAssemblyTelemetry.STAGE_TOOL_SCHEMA));
        assertFalse(stages.contains(InputAssemblyTelemetry.STAGE_STRUCTURED_OUTPUT_SCHEMA));
        assertFalse(stages.contains(InputAssemblyTelemetry.STAGE_CONFIDENCE_CUE));
        assertFalse(stages.contains(InputAssemblyTelemetry.STAGE_SCROLLBACK));
    }

    @Test
    void emitFallsBackToUnknownModelWhenNull() {
        var metrics = new RecordingMetrics();
        InputAssemblyTelemetry.emit(metrics, null, "sys", null, null,
                List.of(), List.of(), "msg");
        assertTrue(metrics.entries.stream().allMatch(e -> "unknown".equals(e.model())));
    }

    @Test
    void emitIsNoopWhenMetricsIsNullOrNoop() {
        // Should neither throw nor record (no recorder to inspect, just no-throw)
        assertDoesNotThrow(() -> InputAssemblyTelemetry.emit(null, "gpt-4", "s",
                null, null, List.of(), List.of(), "m"));
        assertDoesNotThrow(() -> InputAssemblyTelemetry.emit(AiMetrics.NOOP, "gpt-4", "s",
                null, null, List.of(), List.of(), "m"));
    }

    @Test
    void emitSwallowsMetricsImplementationErrors() {
        AiMetrics throwing = new AiMetrics() {
            @Override public void recordStreamingTextUsage(String m, int p, int c) { }
            @Override public void recordLatency(String m, Duration t, Duration u) { }
            @Override public void recordCost(String m, BigDecimal c) { }
            @Override public void recordToolCall(String m, String t, Duration d, boolean s) { }
            @Override public void recordError(String m, String t) { }
            @Override
            public void recordInputAssembly(String model, String stage, int t, int c) {
                throw new RuntimeException("metrics backend down");
            }
        };
        // A misbehaving metrics impl must not abort the turn
        assertDoesNotThrow(() -> InputAssemblyTelemetry.emit(throwing, "gpt-4",
                "system", null, null, List.of(), List.of(), "msg"));
    }

    @Test
    void emitReportsExactCharCountsAndApproxTokens() {
        var metrics = new RecordingMetrics();
        String sys = "x".repeat(100);   // 100 chars -> 25 approx tokens
        String msg = "y".repeat(40);    // 40  chars -> 10 approx tokens

        InputAssemblyTelemetry.emit(metrics, "gpt-4", sys, null, null,
                List.of(), List.of(), msg);

        var system = metrics.entries.stream()
                .filter(e -> e.stage().equals(InputAssemblyTelemetry.STAGE_SYSTEM))
                .findFirst().orElseThrow();
        assertEquals(100, system.approximateChars());
        assertEquals(25, system.approximateTokens());

        var user = metrics.entries.stream()
                .filter(e -> e.stage().equals(InputAssemblyTelemetry.STAGE_USER_MESSAGE))
                .findFirst().orElseThrow();
        assertEquals(40, user.approximateChars());
        assertEquals(10, user.approximateTokens());
    }
}
