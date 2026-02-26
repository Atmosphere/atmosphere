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
package org.atmosphere.spring.boot;

import io.opentelemetry.api.OpenTelemetry;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.metrics.AtmosphereTracing;
import org.atmosphere.mcp.runtime.McpTracing;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers Atmosphere's OpenTelemetry tracing interceptor
 * when both {@link OpenTelemetry} and {@link AtmosphereFramework} beans are available.
 *
 * <p>Creates trace spans for each Atmosphere request covering the full lifecycle
 * (inspect → suspend → broadcast → disconnect). Spans include attributes for
 * transport type, resource UUID, broadcaster ID, and disconnect reason.</p>
 *
 * <p>When {@code atmosphere-mcp} is on the classpath, also creates an {@link McpTracing}
 * bean that wraps MCP tool/resource/prompt calls in trace spans.</p>
 *
 * <p>Enable with {@code atmosphere.tracing.enabled=true} (default).
 * Requires {@code io.opentelemetry:opentelemetry-api} on the classpath and an
 * {@link OpenTelemetry} bean (typically provided by Spring Boot's OTel auto-configuration
 * via {@code spring-boot-starter-actuator} + {@code opentelemetry-exporter-otlp}).</p>
 *
 * @since 4.0.5
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass({OpenTelemetry.class, AtmosphereTracing.class})
@ConditionalOnBean({AtmosphereFramework.class, OpenTelemetry.class})
@ConditionalOnProperty(name = "atmosphere.tracing.enabled", matchIfMissing = true)
public class AtmosphereTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereTracing atmosphereTracing(OpenTelemetry openTelemetry,
                                               AtmosphereFramework framework) {
        var tracing = new AtmosphereTracing(openTelemetry);
        framework.interceptor(tracing);
        return tracing;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(McpTracing.class)
    public McpTracing mcpTracing(OpenTelemetry openTelemetry) {
        return new McpTracing(openTelemetry);
    }
}
