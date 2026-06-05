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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration that registers Atmosphere's OpenTelemetry tracing interceptor
 * when both {@link OpenTelemetry} and {@link AtmosphereFramework} beans are available.
 *
 * <p>Creates trace spans for each Atmosphere request covering the full lifecycle
 * (inspect → suspend → broadcast → disconnect). Spans include attributes for
 * transport type, resource UUID, broadcaster ID, and disconnect reason.</p>
 *
 * <p>When {@code atmosphere-mcp}, {@code atmosphere-a2a}, or {@code atmosphere-agui}
 * is on the classpath, also creates the corresponding tracing bean and auto-attaches
 * it to the matching protocol handlers.</p>
 *
 * <p><strong>Class-load isolation:</strong> all references to the protocol-module
 * types ({@code McpTracing}/{@code McpHandler}, {@code A2aTracing}/{@code A2aHandler},
 * {@code AgUiTracing}/{@code AgUiHandler}) live <em>only</em> inside the nested
 * {@code @ConditionalOnClass(name = ...)} configurations. The outer class must never
 * reference them, or it would fail to load with {@code NoClassDefFoundError} on a
 * classpath that has OpenTelemetry but not the protocol module (e.g. a plain OTel
 * chat app).</p>
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
    @ConditionalOnMissingBean(AtmosphereTracing.class)
    public AtmosphereTracing atmosphereTracing(OpenTelemetry openTelemetry,
                                               AtmosphereFramework framework) {
        var tracing = new AtmosphereTracing(openTelemetry);
        framework.interceptor(tracing);
        return tracing;
    }

    /**
     * Nested configuration that only loads when {@code atmosphere-mcp} is on the
     * classpath.  Using a separate class with {@code @ConditionalOnClass(name = ...)}
     * prevents the JVM from resolving {@code McpTracing}/{@code McpHandler} at
     * class-load time when the MCP module is absent — so the {@code attach} helper
     * (which references those types) must live here, never on the outer class.
     *
     * <p>The {@code McpTracing} bean is auto-attached to every {@code McpHandler}'s
     * protocol handler via a framework startup hook, so MCP tool/resource/prompt
     * calls emit spans out of the box.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.mcp.runtime.McpTracing")
    @ConditionalOnBean(OpenTelemetry.class)
    static class McpTracingAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "mcpTracing")
        public org.atmosphere.mcp.runtime.McpTracing mcpTracing(OpenTelemetry openTelemetry,
                                                                AtmosphereFramework framework) {
            var tracing = new org.atmosphere.mcp.runtime.McpTracing(openTelemetry);
            framework.getAtmosphereConfig().startupHook(f -> attach(f, tracing));
            return tracing;
        }

        /** Attach an MCP tracer to every registered {@code McpHandler}'s protocol handler. */
        static void attach(AtmosphereFramework framework, org.atmosphere.mcp.runtime.McpTracing tracing) {
            for (var w : framework.getAtmosphereHandlers().values()) {
                if (w.atmosphereHandler() instanceof org.atmosphere.mcp.runtime.McpHandler h) {
                    h.protocolHandler().setTracing(tracing);
                }
            }
        }
    }

    /**
     * Nested configuration for A2A tracing — only loads when {@code atmosphere-a2a}
     * is on the classpath. The {@code attach} helper (which references A2A types) is
     * scoped here for the same class-load-isolation reason as the MCP config.
     *
     * <p>The {@code A2aTracing} bean is auto-attached to every {@code A2aHandler}'s
     * protocol handler via a framework startup hook, so A2A skill calls emit spans
     * out of the box.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.a2a.runtime.A2aTracing")
    @ConditionalOnBean(OpenTelemetry.class)
    static class A2aTracingAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "a2aTracing")
        public org.atmosphere.a2a.runtime.A2aTracing a2aTracing(OpenTelemetry openTelemetry,
                                                                AtmosphereFramework framework) {
            var tracing = new org.atmosphere.a2a.runtime.A2aTracing(openTelemetry);
            framework.getAtmosphereConfig().startupHook(f -> attach(f, tracing));
            return tracing;
        }

        /** Attach an A2A tracer to every registered {@code A2aHandler}'s protocol handler. */
        static void attach(AtmosphereFramework framework, org.atmosphere.a2a.runtime.A2aTracing tracing) {
            for (var w : framework.getAtmosphereHandlers().values()) {
                if (w.atmosphereHandler() instanceof org.atmosphere.a2a.runtime.A2aHandler h) {
                    h.protocolHandler().setTracing(tracing);
                }
            }
        }
    }

    /**
     * Nested configuration for AG-UI tracing — only loads when {@code atmosphere-agui}
     * is on the classpath. The {@code attach} helper (which references AG-UI types) is
     * scoped here for the same class-load-isolation reason as the MCP config.
     *
     * <p>The {@code AgUiTracing} bean is auto-attached to every {@code AgUiHandler}
     * via a framework startup hook, so AG-UI action calls emit spans out of the box.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.agui.runtime.AgUiTracing")
    @ConditionalOnBean(OpenTelemetry.class)
    static class AgUiTracingAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "agUiTracing")
        public org.atmosphere.agui.runtime.AgUiTracing agUiTracing(OpenTelemetry openTelemetry,
                                                                   AtmosphereFramework framework) {
            var tracing = new org.atmosphere.agui.runtime.AgUiTracing(openTelemetry);
            framework.getAtmosphereConfig().startupHook(f -> attach(f, tracing));
            return tracing;
        }

        /** Attach an AG-UI tracer to every registered {@code AgUiHandler}. */
        static void attach(AtmosphereFramework framework, org.atmosphere.agui.runtime.AgUiTracing tracing) {
            for (var w : framework.getAtmosphereHandlers().values()) {
                if (w.atmosphereHandler() instanceof org.atmosphere.agui.runtime.AgUiHandler h) {
                    h.setTracing(tracing);
                }
            }
        }
    }
}
