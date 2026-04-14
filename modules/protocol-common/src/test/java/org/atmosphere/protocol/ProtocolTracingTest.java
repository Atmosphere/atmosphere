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
package org.atmosphere.protocol;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ProtocolTracing} — trace span wrapping and error handling.
 */
class ProtocolTracingTest {

    @Test
    void traced_returnsResultOnSuccess() throws Exception {
        var tracing = new ProtocolTracing(
                OpenTelemetry.noop(), "test-scope", "1.0", "test");

        var result = tracing.traced("tool", "add", 2, () -> 42);
        assertEquals(42, result);
    }

    @Test
    void traced_propagatesExceptionOnFailure() {
        var tracing = new ProtocolTracing(
                OpenTelemetry.noop(), "test-scope", "1.0", "test");

        assertThrows(IllegalStateException.class, () ->
                tracing.traced("tool", "fail", 0, () -> {
                    throw new IllegalStateException("boom");
                }));
    }

    @Test
    void traced_returnsNullResult() throws Exception {
        var tracing = new ProtocolTracing(
                OpenTelemetry.noop(), "test-scope", "1.0", "test");

        var result = tracing.traced("resource", "read", 1, () -> null);
        assertEquals(null, result);
    }

    @Test
    void prefix_returnsConfiguredPrefix() {
        var tracing = new ProtocolTracing(
                OpenTelemetry.noop(), "test-scope", "1.0", "mcp");
        assertEquals("mcp", tracing.prefix());
    }

    @Test
    void constructor_withTracerDirectly() throws Exception {
        var tracer = OpenTelemetry.noop().getTracer("test");
        var tracing = new ProtocolTracing(tracer, "a2a");

        assertEquals("a2a", tracing.prefix());
        var result = tracing.traced("tool", "execute", 3, () -> "ok");
        assertEquals("ok", result);
    }
}
