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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Full-wire test of the OpenTelemetry GenAI semantic-convention emission (OTel
 * marks the spec experimental): drives
 * one token-usage signal through the real {@link MetricsCapturingSession}
 * decorator (constructed exactly as {@code AiPipeline} / {@code AiStreamingSession}
 * do — with the resolved {@code AgentRuntime.name()} threaded in), a real
 * {@link MicrometerAiMetrics}, the real {@link GenAiTracer}, and a real
 * {@link SdkTracerProvider} + {@link InMemorySpanExporter} with a parent span
 * active.
 *
 * <p>Asserts the exported span AND the Micrometer GenAI series both carry the
 * resolved runtime provider name (sourced from {@link DemoAgentRuntime#name()},
 * never the hardcoded "atmosphere"), plus the response model the
 * {@link TokenUsage} reported, while the legacy {@code atmosphere.ai.*} series
 * is emitted unchanged.</p>
 */
class GenAiSemconvWiringTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;
    private SimpleMeterRegistry registry;

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
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void usageSignalCarriesResolvedRuntimeProviderThroughSpanAndMetrics() {
        // Resolve a REAL runtime name the way the pipeline does — not a literal.
        var runtime = new DemoAgentRuntime();
        var providerName = runtime.name();
        assertEquals("demo", providerName, "DemoAgentRuntime.name() is the runtime-truth provider id");

        // MicrometerAiMetrics constructed with the bogus "atmosphere" instance
        // provider to prove the GenAI series still picks up the runtime name.
        var metrics = new MicrometerAiMetrics(registry, "atmosphere");
        var delegate = Mockito.mock(StreamingSession.class);
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4o", providerName);

        var usage = new TokenUsage(120, 80, 0, 200, "gpt-4o-2024-08-06");
        var parent = tracer.spanBuilder("ai.turn").startSpan();
        Scope scope = parent.makeCurrent();
        try {
            session.usage(usage);
        } finally {
            scope.close();
            parent.end();
        }

        // --- Span side: gen_ai.* attributes hang off the live parent span. ---
        java.util.List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "no orphan span — attributes land on the active SERVER-style span");
        var attrs = spans.get(0).getAttributes();
        assertEquals(120L, attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(80L, attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(200L, attrs.get(AttributeKey.longKey("gen_ai.usage.total_tokens")));
        assertEquals("gpt-4o-2024-08-06",
                attrs.get(AttributeKey.stringKey("gen_ai.response.model")),
                "the runtime-reported response model is on the span");
        var spanProvider = attrs.get(AttributeKey.stringKey("gen_ai.provider.name"));
        assertEquals("demo", spanProvider, "span provider is the resolved runtime name");
        assertNotEquals("atmosphere", spanProvider, "span provider must NOT be the hardcoded 'atmosphere'");

        // --- Metric side: GenAI series carries the runtime provider + response model. ---
        var genAiInput = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "demo")
                .tag("gen_ai.response.model", "gpt-4o-2024-08-06")
                .tag("gen_ai.token.type", "input")
                .summary();
        assertNotNull(genAiInput, "GenAI metric must carry the resolved provider + response model");
        assertEquals(120.0, genAiInput.totalAmount(), 0.001);
        var hardcoded = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "atmosphere")
                .summary();
        assertNull(hardcoded, "GenAI metric provider must NOT be the hardcoded 'atmosphere'");

        // --- Legacy side: atmosphere.ai.tokens is byte-identical (instance provider, by type). ---
        var legacyInput = registry.find("atmosphere.ai.tokens")
                .tag("model", "gpt-4o")
                .tag("provider", "atmosphere")
                .tag("type", "input")
                .counter();
        assertNotNull(legacyInput, "legacy atmosphere.ai.tokens series unchanged");
        assertEquals(120.0, legacyInput.count(), 0.001);

        // --- And the legacy typed signal still reaches the delegate (ai.tokens.* sink). ---
        Mockito.verify(delegate).usage(usage);
    }
}
