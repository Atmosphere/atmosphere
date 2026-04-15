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
package org.atmosphere.a2a.runtime;

import io.opentelemetry.api.OpenTelemetry;
import org.atmosphere.protocol.ProtocolTracing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class A2aTracingTest {

    @Test
    void constructorWithNoopOpenTelemetrySucceeds() {
        var tracing = new A2aTracing(OpenTelemetry.noop());
        assertNotNull(tracing);
    }

    @Test
    void tracingReturnsNonNullProtocolTracing() {
        var tracing = new A2aTracing(OpenTelemetry.noop());
        ProtocolTracing pt = tracing.tracing();
        assertNotNull(pt);
    }

    @Test
    void tracingHasA2aPrefix() {
        var tracing = new A2aTracing(OpenTelemetry.noop());
        assertEquals("a2a", tracing.tracing().prefix());
    }

    @Test
    void tracingReturnsSameInstanceOnMultipleCalls() {
        var tracing = new A2aTracing(OpenTelemetry.noop());
        ProtocolTracing first = tracing.tracing();
        ProtocolTracing second = tracing.tracing();
        assertNotNull(first);
        assertEquals(first, second);
    }

    @Test
    void tracedOperationExecutesWithNoopTelemetry() throws Exception {
        var tracing = new A2aTracing(OpenTelemetry.noop());
        String result = tracing.tracing().traced("tool", "test-tool", 2, () -> "hello");
        assertEquals("hello", result);
    }
}
