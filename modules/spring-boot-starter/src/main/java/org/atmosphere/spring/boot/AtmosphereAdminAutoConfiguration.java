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

import org.atmosphere.admin.AdminEventHandler;
import org.atmosphere.admin.AdminEventProducer;
import org.atmosphere.admin.AdminMcpBridge;
import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.admin.ControlAuthorizer;
import org.atmosphere.admin.a2a.TaskController;
import org.atmosphere.admin.ai.AiRuntimeController;
import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.admin.metrics.MetricsController;
import org.atmosphere.admin.mcp.McpController;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;

/**
 * Auto-configuration that wires the Atmosphere Admin control plane when
 * {@code atmosphere-admin} is on the classpath.
 *
 * <p>Creates the {@link AtmosphereAdmin} facade and optional subsystem
 * controllers (coordinator, A2A tasks, AI runtimes, MCP registry) based
 * on classpath detection.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(AtmosphereAdmin.class)
@ConditionalOnBean(AtmosphereFramework.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "atmosphere.admin.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AtmosphereProperties.class)
public class AtmosphereAdminAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAdminAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereAdmin atmosphereAdmin(AtmosphereFramework framework) {
        // Micrometer wiring is in MicrometerAdminConfiguration (below) —
        // keeping it in a separate @ConditionalOnClass class avoids
        // TypeNotPresentException when Micrometer is not on the classpath.
        return new AtmosphereAdmin(framework, 1000);
    }

    /** Wires Micrometer metrics into admin — only loaded when Micrometer is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(AtmosphereAdmin.class)
    static class MicrometerAdminConfiguration implements org.springframework.beans.factory.SmartInitializingSingleton {
        private final AtmosphereAdmin admin;
        private final org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;

        MicrometerAdminConfiguration(AtmosphereAdmin admin,
                org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider) {
            this.admin = admin;
            this.meterRegistryProvider = meterRegistryProvider;
        }

        @Override
        public void afterSingletonsInstantiated() {
            var meterRegistry = meterRegistryProvider.getIfAvailable();
            if (meterRegistry != null) {
                admin.setMetricsController(new MetricsController(meterRegistry));
                logger.debug("Atmosphere Admin: Metrics controller wired (Micrometer)");
            }
        }
    }

    /**
     * Deferred admin setup: registers the event WebSocket handler and installs
     * the event producer AFTER the servlet container starts (and therefore after
     * {@code AtmosphereServlet.init()} creates the {@code BroadcasterFactory}).
     */
    @Bean
    @ConditionalOnBean(AtmosphereAdmin.class)
    public org.springframework.context.SmartLifecycle atmosphereAdminLifecycle(
            AtmosphereFramework framework) {
        return new org.springframework.context.SmartLifecycle() {
            private volatile boolean running;
            private volatile AdminEventProducer producer;

            @Override
            public void start() {
                if (running) return; // idempotent
                try {
                    var handler = new AdminEventHandler();
                    java.util.List<org.atmosphere.cpr.AtmosphereInterceptor> interceptors =
                            new java.util.LinkedList<>();
                    org.atmosphere.annotation.AnnotationUtil
                            .defaultManagedServiceInterceptors(framework, interceptors);
                    framework.addAtmosphereHandler(
                            AdminEventHandler.ADMIN_BROADCASTER_ID, handler, interceptors);
                    producer = new AdminEventProducer(framework);
                    producer.install();
                    running = true;
                    logger.info("Atmosphere Admin dashboard at /atmosphere/admin/");
                    logger.info("Atmosphere Admin REST API at /api/admin/*");
                } catch (Exception e) {
                    logger.warn("Admin event setup failed (app continues without admin events): {}",
                            e.getMessage());
                }
            }

            @Override
            public void stop() {
                running = false;
                if (producer != null) {
                    producer.uninstall();
                    producer = null;
                }
                framework.removeAtmosphereHandler(AdminEventHandler.ADMIN_BROADCASTER_ID);
            }

            @Override
            public boolean isRunning() { return running; }

            @Override
            public int getPhase() { return Integer.MAX_VALUE - 2; }
        };
    }

    @Bean
    public FilterRegistrationBean<AdminResourceFilter> adminResourceFilter() {
        var reg = new FilterRegistrationBean<>(new AdminResourceFilter());
        reg.addUrlPatterns("/atmosphere/admin/*");
        reg.setOrder(-1);
        return reg;
    }

    /**
     * Serves admin dashboard static assets from
     * {@code META-INF/resources/atmosphere/admin/}.
     */
    static class AdminResourceFilter implements Filter {

        private static final String PREFIX = "/atmosphere/admin";
        private static final String RESOURCE_BASE = "META-INF/resources/atmosphere/admin/";

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            var httpReq = (HttpServletRequest) request;
            var httpRes = (HttpServletResponse) response;
            var path = httpReq.getRequestURI();

            if (!path.startsWith(PREFIX)) {
                chain.doFilter(request, response);
                return;
            }

            var relativePath = path.substring(PREFIX.length());
            if (relativePath.isEmpty() || relativePath.equals("/")) {
                relativePath = "/index.html";
            }

            if (relativePath.contains("..")) {
                httpRes.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            // Don't intercept the event stream WebSocket or REST API paths
            if (relativePath.startsWith("/events")) {
                chain.doFilter(request, response);
                return;
            }

            var resourceName = relativePath.startsWith("/")
                    ? relativePath.substring(1) : relativePath;
            var resourcePath = RESOURCE_BASE + resourceName;

            InputStream resource = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resourcePath);
            if (resource != null) {
                try (resource) {
                    httpRes.setContentType(guessContentType(resourceName));
                    resource.transferTo(httpRes.getOutputStream());
                }
                return;
            }

            chain.doFilter(request, response);
        }

        private String guessContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            return "application/octet-stream";
        }
    }

    /**
     * Registers admin operations as MCP tools when an McpRegistry bean is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(name = "mcpRegistry")
    @ConditionalOnProperty(name = "atmosphere.admin.mcp-tools", matchIfMissing = true)
    static class AdminMcpBridgeConfiguration {

        @Bean
        AdminMcpBridge atmosphereAdminMcpBridge(
                AtmosphereAdmin admin,
                org.atmosphere.mcp.registry.McpRegistry mcpRegistry,
                AtmosphereProperties properties,
                @org.springframework.beans.factory.annotation.Autowired(required = false)
                ControlAuthorizer customAuthorizer) {
            // Read tools (list, describe) keep ALLOW_ALL because they're
            // information-only. Write tools (scale, kill, reload) default to
            // REQUIRE_PRINCIPAL so the admin MCP surface isn't a no-auth
            // mutation channel (Correctness Invariant #6 — default deny).
            // Applications that expose admin writes intentionally can inject
            // their own ControlAuthorizer bean to override.
            ControlAuthorizer writeAuthorizer = customAuthorizer != null
                    ? customAuthorizer
                    : ControlAuthorizer.REQUIRE_PRINCIPAL;
            var bridge = new AdminMcpBridge(admin, mcpRegistry,
                    customAuthorizer != null ? customAuthorizer : ControlAuthorizer.ALLOW_ALL);
            bridge.registerReadTools();
            if (Boolean.TRUE.toString().equalsIgnoreCase(
                    properties.getAdminMcpWriteTools())) {
                // Rebuild the bridge with the stricter authorizer before
                // mounting write tools so ALLOW_ALL never reaches a
                // mutation surface.
                var writeBridge = new AdminMcpBridge(admin, mcpRegistry, writeAuthorizer);
                writeBridge.registerWriteTools();
            }
            return bridge;
        }
    }

    /**
     * Wires the AI runtime controller when the AI module is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.ai.AgentRuntimeResolver")
    static class AiAdminConfiguration {

        @Bean
        AiRuntimeController atmosphereAdminAiRuntimeController(AtmosphereAdmin admin) {
            var controller = new AiRuntimeController();
            admin.setAiRuntimeController(controller);
            logger.debug("Atmosphere Admin: AI runtime controller wired");
            return controller;
        }
    }

    /**
     * Wires the MCP controller when an McpRegistry bean is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(name = "mcpRegistry")
    static class McpAdminConfiguration {

        @Bean
        McpController atmosphereAdminMcpController(AtmosphereAdmin admin,
                                                    org.atmosphere.mcp.registry.McpRegistry mcpRegistry) {
            var controller = new McpController(mcpRegistry);
            admin.setMcpController(controller);
            logger.debug("Atmosphere Admin: MCP controller wired");
            return controller;
        }
    }

    /**
     * Wires the A2A task controller when a TaskManager bean is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(name = "taskManager")
    static class A2aAdminConfiguration {

        @Bean
        TaskController atmosphereAdminTaskController(AtmosphereAdmin admin,
                                                      org.atmosphere.a2a.runtime.TaskManager taskManager) {
            var controller = new TaskController(taskManager);
            admin.setTaskController(controller);
            logger.debug("Atmosphere Admin: A2A task controller wired");
            return controller;
        }
    }

    /**
     * Wires the coordinator controller when the coordinator module is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.coordinator.fleet.AgentFleet")
    static class CoordinatorAdminConfiguration {

        @Bean
        CoordinatorController atmosphereAdminCoordinatorController(AtmosphereAdmin admin) {
            // Fleet instances are injected into coordinator handlers at startup,
            // not stored in a global bean. The controller is created with empty
            // fleets and populated later via the framework startup hook.
            var controller = new CoordinatorController(
                    java.util.Map.of(),
                    org.atmosphere.coordinator.journal.CoordinationJournal.NOOP);
            admin.setCoordinatorController(controller);
            logger.debug("Atmosphere Admin: Coordinator controller wired");
            return controller;
        }
    }
}
