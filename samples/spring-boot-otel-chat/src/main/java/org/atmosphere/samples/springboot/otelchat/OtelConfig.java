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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the OpenTelemetry SDK with OTLP export.
 *
 * <p>The SDK auto-configures from environment variables / system properties:</p>
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} — OTLP collector endpoint (default: http://localhost:4317)</li>
 *   <li>{@code OTEL_SERVICE_NAME} — service name shown in Jaeger (default: atmosphere-otel-chat)</li>
 * </ul>
 *
 * <p>When no collector is available, the SDK gracefully degrades — no traces are exported,
 * but the app runs normally.</p>
 */
@Configuration
public class OtelConfig {

    private static final Logger logger = LoggerFactory.getLogger(OtelConfig.class);

    @Bean
    public OpenTelemetry openTelemetry() {
        var otel = AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk();
        logger.info("OpenTelemetry SDK initialized — AtmosphereTracing will auto-register");
        return otel;
    }
}
