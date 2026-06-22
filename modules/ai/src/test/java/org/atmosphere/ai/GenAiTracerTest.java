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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GenAiTracer}: the OpenTelemetry GenAI semantic-convention
 * span emitter (OTel marks the spec experimental). Drives a real {@link SdkTracerProvider} +
 * {@link InMemorySpanExporter} registered as the global OpenTelemetry instance
 * so the reflective {@code Span.current()} lookup resolves to a live span and
 * the exported {@link SpanData} can be asserted directly.
 */
class GenAiTracerTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
        tracer = sdk.getTracer("test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    private static final AttributeKey<Long> INPUT = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> OUTPUT = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Long> TOTAL = AttributeKey.longKey("gen_ai.usage.total_tokens");
    private static final AttributeKey<String> REQ_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<String> RES_MODEL = AttributeKey.stringKey("gen_ai.response.model");
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> PROVIDER = AttributeKey.stringKey("gen_ai.provider.name");

    @Test
    void recordsAllSevenGenAiAttributesOnCurrentSpan() {
        var parent = tracer.spanBuilder("ai.turn").startSpan();
        Scope scope = parent.makeCurrent();
        try {
            // Provider-reported response model differs from the request model,
            // proving the two are tagged independently.
            var usage = new TokenUsage(120, 80, 0, 200, "gpt-4o-2024-08-06");
            GenAiTracer.record(usage, "gpt-4o", "google-adk");
        } finally {
            scope.close();
            parent.end();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "exactly the parent span — no orphan span created");
        var attrs = spans.get(0).getAttributes();

        // Typed long values (not stringified).
        assertEquals(120L, attrs.get(INPUT));
        assertEquals(80L, attrs.get(OUTPUT));
        assertEquals(200L, attrs.get(TOTAL));
        // Request vs response model are distinct.
        assertEquals("gpt-4o", attrs.get(REQ_MODEL));
        assertEquals("gpt-4o-2024-08-06", attrs.get(RES_MODEL));
        // Operation + provider.
        assertEquals("chat", attrs.get(OPERATION));
        assertEquals("google-adk", attrs.get(PROVIDER));
    }

    @Test
    void skipsWhenHasCountsFalse() {
        var parent = tracer.spanBuilder("ai.turn").startSpan();
        Scope scope = parent.makeCurrent();
        try {
            var empty = new TokenUsage(0, 0, 0, 0, "gpt-4o");
            assertFalse(empty.hasCounts());
            GenAiTracer.record(empty, "gpt-4o", "google-adk");
        } finally {
            scope.close();
            parent.end();
        }

        var attrs = exporter.getFinishedSpanItems().get(0).getAttributes();
        assertNull(attrs.get(INPUT), "no gen_ai.usage.* tags when hasCounts() is false");
        assertNull(attrs.get(PROVIDER), "no gen_ai.provider.name tag when hasCounts() is false");
    }

    @Test
    void noOpWhenNoCurrentSpan() {
        // No span made current → Span.current() is the invalid no-op span.
        // record() must neither throw nor create a span to hold attributes.
        var usage = new TokenUsage(10, 5, 0, 15, "gpt-4o");
        GenAiTracer.record(usage, "gpt-4o", "google-adk");

        assertTrue(exporter.getFinishedSpanItems().isEmpty(),
                "no span should be created when there is no current span");
    }

    @Test
    void omitsResponseModelWhenNull() {
        var parent = tracer.spanBuilder("ai.turn").startSpan();
        Scope scope = parent.makeCurrent();
        try {
            // Runtime did not report a model → response model is null.
            var usage = new TokenUsage(10, 5, 0, 15, null);
            GenAiTracer.record(usage, "gpt-4o", "google-adk");
        } finally {
            scope.close();
            parent.end();
        }

        var attrs = exporter.getFinishedSpanItems().get(0).getAttributes();
        // Counts + request model + provider still present...
        assertEquals(10L, attrs.get(INPUT));
        assertEquals("gpt-4o", attrs.get(REQ_MODEL));
        assertEquals("google-adk", attrs.get(PROVIDER));
        // ...but the response model attribute is omitted entirely (Runtime
        // Truth: no placeholder when the runtime did not report a model).
        assertNull(attrs.get(RES_MODEL), "gen_ai.response.model must be omitted when null");
    }

    @Test
    void omitsResponseModelWhenBlank() {
        var parent = tracer.spanBuilder("ai.turn").startSpan();
        Scope scope = parent.makeCurrent();
        try {
            var usage = new TokenUsage(10, 5, 0, 15, "   ");
            GenAiTracer.record(usage, "gpt-4o", "google-adk");
        } finally {
            scope.close();
            parent.end();
        }

        var attrs = exporter.getFinishedSpanItems().get(0).getAttributes();
        assertNull(attrs.get(RES_MODEL), "gen_ai.response.model must be omitted when blank");
    }
}
