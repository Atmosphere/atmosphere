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
package org.atmosphere.mcp;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.atmosphere.mcp.runtime.McpTracing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class McpTracingTest {

    private Tracer tracer;
    private SpanBuilder spanBuilder;
    private Span span;
    private Scope scope;
    private McpTracing tracing;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        spanBuilder = mock(SpanBuilder.class);
        span = mock(Span.class);
        scope = mock(Scope.class);

        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any(SpanKind.class))).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(any(AttributeKey.class), any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);

        tracing = new McpTracing(tracer);
    }

    @Test
    void tracedToolCallSuccess() throws Exception {
        var result = tracing.traced("tool", "echo", 2, () -> "hello");

        assertEquals("hello", result);
        verify(tracer).spanBuilder("mcp.tool/echo");
        verify(spanBuilder).setSpanKind(SpanKind.SERVER);
        verify(spanBuilder).setAttribute(McpTracing.TOOL_NAME, "echo");
        verify(spanBuilder).setAttribute(McpTracing.TOOL_TYPE, "tool");
        verify(spanBuilder).setAttribute(McpTracing.ARG_COUNT, 2L);
        verify(span).setAttribute(McpTracing.TOOL_ERROR, false);
        verify(span).end();
        verify(scope).close();
    }

    @Test
    void tracedToolCallError() {
        var ex = new RuntimeException("tool failed");
        var thrown = assertThrows(RuntimeException.class,
                () -> tracing.traced("tool", "broken", 0, () -> { throw ex; }));

        assertSame(ex, thrown);
        verify(span).setAttribute(McpTracing.TOOL_ERROR, true);
        verify(span).setStatus(StatusCode.ERROR, "tool failed");
        verify(span).recordException(ex);
        verify(span).end();
        verify(scope).close();
    }

    @Test
    void tracedResourceRead() throws Exception {
        var result = tracing.traced("resource", "file://config.json", 1,
                () -> "{\"key\":\"value\"}");

        assertEquals("{\"key\":\"value\"}", result);
        verify(tracer).spanBuilder("mcp.resource/file://config.json");
        verify(spanBuilder).setAttribute(McpTracing.TOOL_TYPE, "resource");
    }

    @Test
    void tracedPromptGet() throws Exception {
        var result = tracing.traced("prompt", "summarize", 3,
                () -> "Summarize the following...");

        assertEquals("Summarize the following...", result);
        verify(tracer).spanBuilder("mcp.prompt/summarize");
        verify(spanBuilder).setAttribute(McpTracing.TOOL_TYPE, "prompt");
        verify(spanBuilder).setAttribute(McpTracing.ARG_COUNT, 3L);
    }

    @Test
    void tracedZeroArgs() throws Exception {
        tracing.traced("tool", "noop", 0, () -> null);

        verify(spanBuilder).setAttribute(McpTracing.ARG_COUNT, 0L);
        verify(span).setAttribute(McpTracing.TOOL_ERROR, false);
        verify(span).end();
    }

    @Test
    void spanAlwaysEndsEvenOnError() {
        assertThrows(IllegalStateException.class,
                () -> tracing.traced("tool", "err", 1,
                        () -> { throw new IllegalStateException("state"); }));

        verify(span).end();
    }
}
