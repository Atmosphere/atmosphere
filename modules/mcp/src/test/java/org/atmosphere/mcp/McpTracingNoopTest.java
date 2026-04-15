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

import io.opentelemetry.api.OpenTelemetry;
import org.atmosphere.mcp.runtime.McpTracing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link McpTracing} using {@code OpenTelemetry.noop()}. Verifies
 * that tracing works correctly with a real (noop) OpenTelemetry instance
 * rather than mocked tracer objects.
 */
public class McpTracingNoopTest {

    private McpTracing tracing;

    @BeforeEach
    void setUp() {
        tracing = new McpTracing(OpenTelemetry.noop());
    }

    @Test
    void constructorWithNoopProducesNonNull() {
        assertNotNull(tracing);
    }

    @Test
    void tracedToolCallReturnsResult() throws Exception {
        var result = tracing.traced("tool", "echo", 1, () -> "hello");
        assertEquals("hello", result);
    }

    @Test
    void tracedResourceCallReturnsResult() throws Exception {
        var result = tracing.traced("resource", "file://data.json", 0,
                () -> "{\"key\":\"value\"}");
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void tracedPromptCallReturnsResult() throws Exception {
        var result = tracing.traced("prompt", "summarize", 2,
                () -> "Summarize this text.");
        assertEquals("Summarize this text.", result);
    }

    @Test
    void tracedReturnsNullWhenActionReturnsNull() throws Exception {
        Object result = tracing.traced("tool", "nil", 0, () -> null);
        assertEquals(null, result);
    }

    @Test
    void tracedPropagatesCheckedException() {
        var exception = assertThrows(Exception.class,
                () -> tracing.traced("tool", "fail", 0, () -> {
                    throw new Exception("checked failure");
                }));
        assertEquals("checked failure", exception.getMessage());
    }

    @Test
    void tracedPropagatesRuntimeException() {
        var ex = new IllegalStateException("bad state");
        var thrown = assertThrows(IllegalStateException.class,
                () -> tracing.traced("tool", "fail", 0, () -> {
                    throw ex;
                }));
        assertSame(ex, thrown);
    }

    @Test
    void tracedWithZeroArgs() throws Exception {
        var result = tracing.traced("tool", "noop", 0, () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void tracedWithLargeArgCount() throws Exception {
        var result = tracing.traced("tool", "complex", 100, () -> "done");
        assertEquals("done", result);
    }

    @Test
    void multipleTracedCallsSucceed() throws Exception {
        for (int i = 0; i < 10; i++) {
            var result = tracing.traced("tool", "iter_" + i, i, () -> "ok");
            assertEquals("ok", result);
        }
    }

    @Test
    void tracedTypeSafeWithGenericReturn() throws Exception {
        int intResult = tracing.traced("tool", "add", 2, () -> 42);
        assertEquals(42, intResult);
    }
}
