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
package org.atmosphere.samples.springboot.otelchat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Guards the 4.0.60 release-gate regression: the OTLP span exporter died at
 * export time with
 * {@code NoClassDefFoundError: io/opentelemetry/api/internal/InstrumentationUtil},
 * so no span ever reached Jaeger even though the app booted and the Jaeger UI
 * was reachable.
 *
 * <p>Root cause was an OpenTelemetry version skew: the parent pom manages only
 * {@code opentelemetry-api} (to 1.63.0), while the unmanaged
 * {@code opentelemetry-sdk}/{@code -exporter-otlp}/{@code -sender-okhttp} fell
 * to an older line — and the okhttp gRPC sender linked against a class the
 * newer api had reshaped. The sample pom now imports {@code opentelemetry-bom}
 * so every OTel artifact shares one version.</p>
 *
 * <p>This test reproduces the exact failing call chain deterministically and
 * WITHOUT Docker: build the real {@link OtlpGrpcSpanExporter}, push a span
 * through a {@link SimpleSpanProcessor} (synchronous export on span end), and
 * force a flush. The export path is
 * {@code OtlpGrpcSpanExporter.export → OkHttpGrpcSender.send → InstrumentationUtil},
 * so a version skew surfaces as a {@link LinkageError} here. A connection
 * failure to the dead endpoint is fine — that is a healthy export attempt; a
 * {@code NoClassDefFoundError} is the regression.</p>
 */
class OtlpExporterLinkageTest {

    @Test
    void otlpExportPathLinksCleanly() {
        // Point at a port nothing listens on: we are testing that the export
        // CODE PATH links, not that a collector answers. The gRPC sender will
        // fail to connect and the flush completes with a failed result — but
        // it must not throw a LinkageError (the version-skew symptom).
        var exporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint("http://localhost:1")
                .build();
        var tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        try (var sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()) {
            var tracer = sdk.getTracer("otel-chat-linkage-test");

            assertDoesNotThrow(() -> {
                Span span = tracer.spanBuilder("atmosphere GET /ai-chat")
                        .setAttribute("atmosphere.transport", "WEBSOCKET")
                        .startSpan();
                span.end(); // SimpleSpanProcessor exports synchronously here

                // Explicit flush too, so the exporter's send() definitely runs
                // on this thread and any linkage failure surfaces in the test.
                tracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
            }, "OTLP export path must link cleanly — a NoClassDefFoundError here "
                    + "means the opentelemetry-* artifacts are on mismatched versions");
        }
    }
}
