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
    public AtmosphereAdmin atmosphereAdmin(
            AtmosphereFramework framework,
            org.springframework.beans.factory.ObjectProvider<ControlAuthorizer> authorizerProvider,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.admin.require-principal:true}") boolean requirePrincipalDefault) {
        // Micrometer wiring is in MicrometerAdminConfiguration (below) —
        // keeping it in a separate @ConditionalOnClass class avoids
        // TypeNotPresentException when Micrometer is not on the classpath.
        var admin = new AtmosphereAdmin(framework, 1000);
        // Install the authorizer in priority order:
        //   1. user-supplied @Bean ControlAuthorizer (wins always)
        //   2. REQUIRE_PRINCIPAL when atmosphere.admin.require-principal=true (default)
        //   3. DENY_ALL fallback — fail-closed per Correctness Invariant #6
        var custom = authorizerProvider.getIfAvailable();
        if (custom != null) {
            admin.setAuthorizer(custom);
            logger.info("Atmosphere Admin authorizer: {} (custom @Bean)",
                    custom.getClass().getName());
        } else if (requirePrincipalDefault) {
            admin.setAuthorizer(ControlAuthorizer.REQUIRE_PRINCIPAL);
            logger.info("Atmosphere Admin authorizer: REQUIRE_PRINCIPAL (default). "
                    + "Supply a @Bean ControlAuthorizer for fine-grained role/scope checks.");
        } else {
            admin.setAuthorizer(ControlAuthorizer.DENY_ALL);
            logger.warn("Atmosphere Admin authorizer: DENY_ALL — all mutating admin "
                    + "actions will be rejected. Set atmosphere.admin.require-principal=true "
                    + "or supply a @Bean ControlAuthorizer to enable mutations.");
        }
        return admin;
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
     * Validates {@code X-Atmosphere-Auth} on every {@code /api/admin/*}
     * request via the Spring-supplied {@code TokenValidator} bean and
     * surfaces the resolved principal to Spring MVC. {@code AuthInterceptor}
     * only runs inside the Atmosphere handler chain, which does not
     * cover {@code /api/admin/*} (that path is served by Spring MVC's
     * DispatcherServlet). Without this filter the admin REST endpoints
     * reject the token-carrying writes with 401 because
     * {@code guardWrite}'s principal lookup finds nothing.
     *
     * <p>Only registered when a {@code TokenValidator} bean is present —
     * applications without the Atmosphere auth stack keep the legacy
     * "bring your own Spring Security filter chain" path.</p>
     */
    @Bean
    @ConditionalOnBean(org.atmosphere.auth.TokenValidator.class)
    public FilterRegistrationBean<AdminApiAuthFilter> adminApiAuthFilter(
            org.atmosphere.auth.TokenValidator validator,
            org.springframework.core.env.Environment env) {
        var reg = new FilterRegistrationBean<>(new AdminApiAuthFilter(validator, env));
        reg.addUrlPatterns("/api/admin/*");
        reg.setOrder(0);
        return reg;
    }

    /**
     * Surfaces the {@code X-Atmosphere-Auth}-resolved principal onto
     * Spring MVC requests so {@code AtmosphereAdminEndpoint.guardWrite}
     * can enforce authn+authz. Absent tokens or invalid tokens leave
     * the principal unset so guardWrite still returns 401 (default deny,
     * Correctness Invariant #6).
     */
    static class AdminApiAuthFilter implements Filter {

        private final org.atmosphere.auth.TokenValidator validator;
        private final org.springframework.core.env.Environment env;

        AdminApiAuthFilter(org.atmosphere.auth.TokenValidator validator,
                           org.springframework.core.env.Environment env) {
            this.validator = validator;
            this.env = env;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            var httpReq = (HttpServletRequest) request;
            var token = httpReq.getHeader("X-Atmosphere-Auth");
            if (token == null || token.isBlank()) {
                // Fall back to the ?X-Atmosphere-Auth= query string
                // since browsers cannot set custom headers on WebSocket /
                // EventSource upgrades; the REST surface supports it too
                // so test fixtures that share the token-in-query flow
                // work.
                token = httpReq.getParameter("X-Atmosphere-Auth");
            }
            java.security.Principal principal = null;
            if (token != null && !token.isBlank()
                    && validator.validate(token)
                            instanceof org.atmosphere.auth.TokenValidator.Valid valid) {
                principal = valid.principal();
                httpReq.setAttribute("org.atmosphere.auth.principal", principal);
            }
            // Opt-in read auth gate: when
            // atmosphere.admin.http-read-auth-required=true, reject
            // anonymous reads (GET/HEAD/OPTIONS) too. Default false so
            // existing demo consoles keep working; production operators
            // flip the flag. Writes go through guardWrite on the
            // endpoint and aren't double-gated here.
            if (principal == null && isReadAuthRequired() && isReadMethod(httpReq)) {
                var httpRes = (HttpServletResponse) response;
                httpRes.setStatus(401);
                httpRes.setContentType("application/json");
                httpRes.getWriter().write(
                        "{\"error\":\"Admin read operations require authentication\","
                        + "\"hint\":\"Send X-Atmosphere-Auth header or disable "
                        + "atmosphere.admin.http-read-auth-required\"}");
                httpRes.getWriter().flush();
                return;
            }
            if (principal != null) {
                chain.doFilter(new AuthenticatedHttpRequest(httpReq, principal), response);
                return;
            }
            chain.doFilter(request, response);
        }

        private boolean isReadAuthRequired() {
            return Boolean.parseBoolean(
                    env.getProperty("atmosphere.admin.http-read-auth-required", "false"));
        }

        private static boolean isReadMethod(HttpServletRequest req) {
            var method = req.getMethod();
            return "GET".equalsIgnoreCase(method)
                    || "HEAD".equalsIgnoreCase(method)
                    || "OPTIONS".equalsIgnoreCase(method);
        }
    }

    /**
     * Wraps the servlet request so {@code getUserPrincipal()} returns the
     * token-resolved principal. Spring MVC, Jakarta Security, and the
     * admin endpoint's {@code guardWrite} all read the standard servlet
     * method — wrapping is simpler than duplicating the lookup at every
     * site.
     */
    static class AuthenticatedHttpRequest
            extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final java.security.Principal principal;

        AuthenticatedHttpRequest(HttpServletRequest req, java.security.Principal principal) {
            super(req);
            this.principal = principal;
        }

        @Override
        public java.security.Principal getUserPrincipal() {
            return principal;
        }
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

        /**
         * Wires the agent-to-agent flow viewer backing the
         * {@code /api/admin/flow} endpoints. Reads the Spring-bridged
         * {@link org.atmosphere.coordinator.journal.CoordinationJournal} bean
         * so it sees the same event stream as the coordinator pipeline
         * (NOOP fallback keeps the endpoint usable when no bean is present).
         */
        @Bean
        org.atmosphere.admin.flow.FlowController atmosphereAdminFlowController(
                AtmosphereAdmin admin,
                org.springframework.beans.factory.ObjectProvider<
                        org.atmosphere.coordinator.journal.CoordinationJournal> journalProvider) {
            var journal = journalProvider.getIfAvailable(
                    () -> org.atmosphere.coordinator.journal.CoordinationJournal.NOOP);
            var controller = new org.atmosphere.admin.flow.FlowController(journal);
            admin.setFlowController(controller);
            logger.debug("Atmosphere Admin: Flow controller wired (journal={})",
                    journal.getClass().getSimpleName());
            return controller;
        }
    }
}
