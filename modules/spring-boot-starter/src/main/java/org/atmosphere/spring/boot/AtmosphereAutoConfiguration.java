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

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereAnnotations;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.DefaultAnnotationProcessor;
import org.atmosphere.room.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Spring Boot auto-configuration for the Atmosphere framework. Scans for annotated handler
 * classes, registers the {@link org.atmosphere.cpr.AtmosphereServlet}, configures the
 * {@link org.atmosphere.cpr.AtmosphereObjectFactory}, and sets up JSR 356 WebSocket endpoints.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(AtmosphereServlet.class)
@ConditionalOnProperty(name = "atmosphere.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AtmosphereProperties.class)
@ImportRuntimeHints(AtmosphereRuntimeHints.class)
public class AtmosphereAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAutoConfiguration.class);

    // Source of truth: AtmosphereAnnotations.coreAnnotations()
    private static final List<Class<? extends Annotation>> ATMOSPHERE_ANNOTATIONS =
            AtmosphereAnnotations.coreAnnotations();

    @Bean
    @ConditionalOnMissingBean
    public SpringAtmosphereObjectFactory springAtmosphereObjectFactory(ApplicationContext applicationContext) {
        return new SpringAtmosphereObjectFactory(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereServlet atmosphereServlet(
            SpringAtmosphereObjectFactory objectFactory,
            AtmosphereProperties properties,
            @Qualifier("atmosphereExecutor") ObjectProvider<java.util.concurrent.ExecutorService> executorProvider) {
        // Pre-scan for Atmosphere annotations using Spring's classpath scanner.
        // Spring Boot's embedded containers do not process @HandlesTypes from
        // ServletContainerInitializer, so Atmosphere's built-in annotation scanning
        // receives no classes. We bridge this gap by scanning here and injecting
        // the results into the servlet context right before framework.init() reads them.
        Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap = scanAnnotations(properties);

        var servlet = new AnnotationAwareAtmosphereServlet(annotationMap, executorProvider.getIfAvailable());
        servlet.framework().objectFactory(objectFactory);
        return servlet;
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereFramework atmosphereFramework(AtmosphereServlet servlet) {
        return servlet.framework();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoomManager roomManager(AtmosphereFramework framework) {
        return RoomManager.getOrCreate(framework);
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereLifecycle atmosphereLifecycle(AtmosphereFramework framework,
                                                   ApplicationContext applicationContext) {
        return new AtmosphereLifecycle(framework, applicationContext);
    }

    /**
     * Serves {@code /.well-known/agent.json} with an array of all registered
     * A2A agent cards. Enables standard agent discovery per the A2A protocol.
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereWellKnownFilter")
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> atmosphereWellKnownFilter(
            AtmosphereFramework framework) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
                new WellKnownAgentFilter(framework));
        registration.addUrlPatterns("/.well-known/agent.json");
        registration.setOrder(-1);
        return registration;
    }

    /**
     * Serves the built-in AI console static assets from
     * {@code META-INF/resources/atmosphere/console/} before the Atmosphere
     * servlet (mapped to {@code /atmosphere/*}) can intercept them.
     * Registered in the base auto-configuration so ALL samples get the console,
     * not just those with {@code atmosphere-ai} on the classpath.
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereConsoleFilter")
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> atmosphereConsoleFilter(
            AtmosphereProperties properties,
            org.springframework.beans.factory.ObjectProvider<org.atmosphere.spring.boot.webtransport.ReactorNettyTransportServer> webTransportServer) {
        // The WebTransport sidecar port feeds the console CSP's connect-src at
        // request time (Runtime Truth): the HTTP/3 origin differs from the page
        // origin, so without it the CSP blocks the console's WT-first connect.
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
                new ConsoleResourceFilter(properties.getMcpSandboxOrigin(), () -> {
                    var server = webTransportServer.getIfAvailable();
                    return server != null && server.isRunning() ? server.port() : null;
                }));
        registration.addUrlPatterns("/atmosphere/console/*");
        registration.setOrder(0);
        return registration;
    }

    /**
     * Opt-in baseline security response headers for an application's own routes
     * (its root SPA + static assets). Off by default; enable with
     * {@code atmosphere.security-headers.enabled=true} (Correctness Invariant #6).
     *
     * <p>Deliberately skips {@code /atmosphere/*} so it never overwrites the
     * console's own strict CSP ({@link ConsoleResourceFilter#buildConsoleCsp}),
     * and emits ONLY {@code Content-Security-Policy: frame-ancestors ...} — no
     * {@code connect-src}/{@code script-src}. A {@code connect-src 'self' ws: wss:}
     * policy would block the different-origin WebTransport/HTTP-3 endpoint
     * (Alt-Svc, separate port) and force a transport downgrade.</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereSecurityHeadersFilter")
    @ConditionalOnProperty(name = "atmosphere.security-headers.enabled", havingValue = "true")
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> atmosphereSecurityHeadersFilter(
            AtmosphereProperties properties) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
                new SecurityHeadersFilter(properties.getSecurityHeaders().getFrameOptions()));
        registration.addUrlPatterns("/*");
        // Run early so headers are set before the app's root response commits; the
        // /atmosphere/* skip makes ordering vs. the console filter immaterial.
        registration.setOrder(-100);
        return registration;
    }

    /**
     * Emits baseline hardening headers on an application's own routes, skipping
     * {@code /atmosphere/*} (console/MCP/A2A/transport carry their own policies).
     * Plain {@link jakarta.servlet.Filter} — not {@code OncePerRequestFilter} —
     * because {@code spring-web} is only test-scoped in this module.
     */
    static class SecurityHeadersFilter implements jakarta.servlet.Filter {

        private final String frameOptions;
        private final String csp;

        SecurityHeadersFilter(String frameOptions) {
            this.frameOptions = (frameOptions == null || frameOptions.isBlank())
                    ? "DENY" : frameOptions.trim();
            // Keep X-Frame-Options and CSP frame-ancestors coherent so the two
            // anti-framing headers never contradict each other.
            var frameAncestors = "SAMEORIGIN".equalsIgnoreCase(this.frameOptions) ? "'self'" : "'none'";
            this.csp = "frame-ancestors " + frameAncestors;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response, jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            var httpReq = (jakarta.servlet.http.HttpServletRequest) request;
            var httpRes = (jakarta.servlet.http.HttpServletResponse) response;
            var path = httpReq.getRequestURI();
            // Only decorate the app's own routes; /atmosphere/* keeps its own policy.
            if (path == null || !path.startsWith("/atmosphere/")) {
                httpRes.setHeader("X-Content-Type-Options", "nosniff");
                httpRes.setHeader("Referrer-Policy", "no-referrer");
                httpRes.setHeader("X-Frame-Options", frameOptions);
                httpRes.setHeader("Content-Security-Policy", csp);
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Serves built-in console static assets from {@code META-INF/resources/atmosphere/console/}
     * before the Atmosphere servlet (mapped to {@code /atmosphere/*}) can intercept them.
     */
    static class ConsoleResourceFilter implements jakarta.servlet.Filter {

        private static final String CONSOLE_PREFIX = "/atmosphere/console";
        private static final String RESOURCE_BASE = "META-INF/resources/atmosphere/console/";

        // Configured MCP Apps sandbox origin (atmosphere.mcp-sandbox-origin),
        // or "" when unset. Threaded into the console HTML's frame-src below.
        private final String configuredSandboxOrigin;

        // Live WebTransport sidecar port supplier (null = sidecar not running).
        // Resolved per request so the CSP's connect-src reflects runtime truth.
        private final java.util.function.Supplier<Integer> webTransportPort;

        ConsoleResourceFilter(String configuredSandboxOrigin) {
            this(configuredSandboxOrigin, () -> null);
        }

        ConsoleResourceFilter(String configuredSandboxOrigin,
                              java.util.function.Supplier<Integer> webTransportPort) {
            this.configuredSandboxOrigin = configuredSandboxOrigin == null
                    ? "" : configuredSandboxOrigin.trim();
            this.webTransportPort = webTransportPort;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response,
                             jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            var httpReq = (jakarta.servlet.http.HttpServletRequest) request;
            var httpRes = (jakarta.servlet.http.HttpServletResponse) response;
            var path = httpReq.getRequestURI();

            if (!path.startsWith(CONSOLE_PREFIX)) {
                chain.doFilter(request, response);
                return;
            }

            var relativePath = path.substring(CONSOLE_PREFIX.length());
            if (relativePath.isEmpty() || relativePath.equals("/")) {
                relativePath = "/index.html";
            }

            if (relativePath.contains("..")) {
                httpRes.sendError(jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            var resourceName = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
            var resourcePath = RESOURCE_BASE + resourceName;

            java.io.InputStream resource = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resourcePath);
            if (resource != null) {
                try (resource) {
                    httpRes.setContentType(guessContentType(resourceName));
                    // Baseline hardening on every console resource: block MIME
                    // sniffing and suppress referrer leakage. Safe on the
                    // cross-origin-framed sandbox.html and on static assets.
                    httpRes.setHeader("X-Content-Type-Options", "nosniff");
                    httpRes.setHeader("Referrer-Policy", "no-referrer");
                    // The console SPA shell (index.html) carries no static <meta>
                    // CSP: the MCP Apps sandbox frames a DISTINCT sibling origin
                    // (localhost<->127.0.0.1, or atmosphere.mcp-sandbox-origin) for
                    // isolation, and that origin is only known at request time. A
                    // meta CSP can't express it, and CSP combines by intersection so
                    // a runtime header can't loosen a restrictive meta. So the full
                    // policy is emitted here, with frame-src widened to the resolvable
                    // sandbox origins.
                    //
                    // NOT applied to sandbox.html: the sandbox proxy runs an inline
                    // bootstrap <script> and builds its OWN inner-app CSP; its
                    // isolation is the sandboxed opaque origin, so a script-src 'self'
                    // header would break the proxy bridge.
                    if (resourceName.equals("index.html")) {
                        // Per-request nonce -> nonce-based strict CSP (no
                        // 'unsafe-inline'). The Vite build stamps a placeholder on
                        // the meta/script/link tags; we mint a fresh 128-bit value
                        // per response, substitute it into the served HTML, and
                        // match it in the CSP header. index.html is served no-store
                        // so a nonce is never reused; the hashed assets carry no
                        // nonce and stay cacheable.
                        var nonce = freshNonce();
                        var html = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                .replace(NONCE_PLACEHOLDER, nonce);
                        httpRes.setHeader("Content-Security-Policy",
                                buildConsoleCsp(httpReq.getScheme(),
                                        httpReq.getHeader("Host"), configuredSandboxOrigin, nonce,
                                        webTransportOrigin(httpReq.getHeader("Host"), webTransportPort.get())));
                        // Anti-clickjacking: the console is always the top frame (it
                        // frames the sandbox; nothing frames it). frame-ancestors
                        // 'none' in the CSP is the modern control; X-Frame-Options
                        // DENY covers legacy browsers. Deliberately NOT set on
                        // sandbox.html, which the MCP Apps proxy frames from a
                        // distinct sibling origin (localhost<->127.0.0.1).
                        httpRes.setHeader("X-Frame-Options", "DENY");
                        httpRes.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                        httpRes.getOutputStream().write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        resource.transferTo(httpRes.getOutputStream());
                    }
                }
                return;
            }

            chain.doFilter(request, response);
        }

        /** The literal nonce sentinel the Vite build stamps onto meta/script/link tags. */
        static final String NONCE_PLACEHOLDER = "__ATMO_CSP_NONCE__";
        private static final java.security.SecureRandom NONCE_RNG = new java.security.SecureRandom();

        /** A fresh, base64-encoded 128-bit CSP nonce for a single response. */
        static String freshNonce() {
            var b = new byte[16];
            NONCE_RNG.nextBytes(b);
            return java.util.Base64.getEncoder().encodeToString(b);
        }

        /**
         * The console's nonce-based strict Content-Security-Policy. {@code script-src}
         * and {@code style-src} carry the per-request {@code nonce}; {@code script-src}
         * adds {@code 'strict-dynamic'} so the nonced Vite entry propagates trust to its
         * hashed ES-module chunks (no {@code 'self'}, no {@code 'unsafe-inline'}).
         * {@code frame-src} lists the resolvable MCP Apps sandbox origins: {@code 'self'},
         * the dev loopback sibling ({@code localhost}<->{@code 127.0.0.1}, same
         * port/scheme), and the configured origin when set and not the current origin.
         * Package-visible for unit testing.
         */
        static String buildConsoleCsp(String scheme, String host, String configuredOrigin, String nonce) {
            return buildConsoleCsp(scheme, host, configuredOrigin, nonce, null);
        }

        static String buildConsoleCsp(String scheme, String host, String configuredOrigin, String nonce,
                                      String webTransportOrigin) {
            var frameSrc = new StringBuilder("'self'");
            var sibling = siblingOrigin(scheme, host);
            if (sibling != null) {
                frameSrc.append(' ').append(sibling);
            }
            if (configuredOrigin != null && !configuredOrigin.isBlank()) {
                var trimmed = configuredOrigin.trim();
                var current = host != null && !host.isBlank() ? scheme + "://" + host : null;
                if (!trimmed.equals(current)) {
                    frameSrc.append(' ').append(trimmed);
                }
            }
            // The HTTP/3 sidecar binds its own port, so its origin differs from
            // the page origin — without an explicit connect-src entry the CSP
            // blocks the console's WebTransport-first connect. Present only when
            // the sidecar genuinely runs (Runtime Truth), null otherwise.
            var connectSrc = new StringBuilder("'self' ws: wss:");
            if (webTransportOrigin != null && !webTransportOrigin.isBlank()) {
                connectSrc.append(' ').append(webTransportOrigin);
            }
            return "default-src 'self'; "
                    + "script-src 'nonce-" + nonce + "' 'strict-dynamic'; "
                    + "style-src 'self' 'nonce-" + nonce + "'; "
                    + "img-src 'self' data:; font-src 'self' data:; "
                    + "connect-src " + connectSrc + "; "
                    + "frame-src " + frameSrc + "; object-src 'none'; "
                    + "base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
        }

        /**
         * The HTTP/3 sidecar's origin as the browser will address it: the page
         * host's hostname (the console derives the WT URL from
         * {@code location.hostname}) with the sidecar's actually-bound port,
         * always https. {@code null} when the sidecar isn't running.
         */
        static String webTransportOrigin(String host, Integer wtPort) {
            if (wtPort == null || host == null || host.isBlank()) {
                return null;
            }
            var hostname = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
            if (hostname.isBlank()) {
                return null;
            }
            return "https://" + hostname + ":" + wtPort;
        }

        /**
         * The loopback sibling origin: when the page is served from
         * {@code localhost} the sandbox is framed from {@code 127.0.0.1} (and
         * vice versa), same scheme and port. Returns {@code null} when the host
         * is neither loopback alias (no dev sibling to allow).
         */
        static String siblingOrigin(String scheme, String host) {
            if (host == null || host.isBlank() || scheme == null || scheme.isBlank()) {
                return null;
            }
            var colon = host.indexOf(':');
            var name = colon >= 0 ? host.substring(0, colon) : host;
            var port = colon >= 0 ? host.substring(colon) : "";
            String siblingName;
            if (name.equals("localhost")) {
                siblingName = "127.0.0.1";
            } else if (name.equals("127.0.0.1")) {
                siblingName = "localhost";
            } else {
                return null;
            }
            return scheme + "://" + siblingName + port;
        }

        private String guessContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }

    /**
     * Serves {@code /.well-known/agent.json} by scanning the framework's handler
     * registry for A2A handlers and returning an array of their agent cards.
     */
    static class WellKnownAgentFilter implements jakarta.servlet.Filter {

        private final AtmosphereFramework framework;

        WellKnownAgentFilter(AtmosphereFramework framework) {
            this.framework = framework;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response,
                             jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            var httpReq = (jakarta.servlet.http.HttpServletRequest) request;
            var httpRes = (jakarta.servlet.http.HttpServletResponse) response;

            if (!"GET".equalsIgnoreCase(httpReq.getMethod())) {
                chain.doFilter(request, response);
                return;
            }

            // Collect agent card JSON from all registered A2A handlers
            var cardJsonList = new java.util.ArrayList<String>();
            for (var entry : framework.getAtmosphereHandlers().entrySet()) {
                try {
                    var wrapper = entry.getValue();
                    var handlerField = wrapper.getClass().getDeclaredField("atmosphereHandler");
                    handlerField.setAccessible(true);
                    var handler = handlerField.get(wrapper);
                    if (!"org.atmosphere.a2a.runtime.A2aHandler".equals(
                            handler.getClass().getName())) {
                        continue;
                    }
                    var phField = handler.getClass().getDeclaredField("protocolHandler");
                    phField.setAccessible(true);
                    var protocolHandler = phField.get(handler);
                    var cardMethod = protocolHandler.getClass().getMethod("agentCardJson");
                    var json = (String) cardMethod.invoke(protocolHandler);
                    if (json != null) {
                        cardJsonList.add(json);
                    }
                } catch (ReflectiveOperationException e) {
                    logger.trace("Failed to extract agent card from {}", entry.getKey(), e);
                }
            }

            if (cardJsonList.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }

            httpRes.setStatus(200);
            httpRes.setContentType("application/json; charset=utf-8");
            if (cardJsonList.size() == 1) {
                httpRes.getWriter().write(cardJsonList.getFirst());
            } else {
                httpRes.getWriter().write("[" + String.join(",", cardJsonList) + "]");
            }
            httpRes.getWriter().flush();
        }
    }

    private Map<Class<? extends Annotation>, Set<Class<?>>> scanAnnotations(
            AtmosphereProperties properties) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        for (Class<? extends Annotation> annotation : ATMOSPHERE_ANNOTATIONS) {
            scanner.addIncludeFilter(new AnnotationTypeFilter(annotation));
        }

        Set<Class<?>> classes = new HashSet<>();

        // Scan Atmosphere's annotation processor packages.
        // The core processors live in org.atmosphere.annotation; extension modules
        // (e.g. atmosphere-ai) place their processors in org.atmosphere.ai.processor etc.
        // Scanning the root org.atmosphere package ensures all framework-provided
        // @AtmosphereAnnotation processors are discovered regardless of module.
        scanPackage(scanner, "org.atmosphere", classes);

        // Scan user-configured packages
        var userPackages = new java.util.ArrayList<String>();
        if (properties.getPackages() != null) {
            for (String pkg : properties.getPackages().split(",")) {
                var trimmed = pkg.trim();
                if (!trimmed.isEmpty()) {
                    userPackages.add(trimmed);
                    scanPackage(scanner, trimmed, classes);
                }
            }
        }

        // Second pass: discover custom annotation types from @AtmosphereAnnotation processors
        // and re-scan user packages for classes annotated with those custom annotations.
        var customAnnotationTypes = new HashSet<Class<? extends Annotation>>();
        for (Class<?> clazz : classes) {
            var aa = clazz.getAnnotation(AtmosphereAnnotation.class);
            if (aa != null) {
                var target = aa.value();
                boolean isCore = false;
                for (var core : ATMOSPHERE_ANNOTATIONS) {
                    if (core.equals(target)) {
                        isCore = true;
                        break;
                    }
                }
                if (!isCore) {
                    customAnnotationTypes.add(target);
                }
            }
        }

        if (!customAnnotationTypes.isEmpty()) {
            var customScanner = new ClassPathScanningCandidateComponentProvider(false);
            for (var customAnnotation : customAnnotationTypes) {
                customScanner.addIncludeFilter(new AnnotationTypeFilter(customAnnotation));
            }
            for (String pkg : userPackages) {
                scanPackage(customScanner, pkg, classes);
            }
            logger.debug("Discovered {} custom Atmosphere annotation types: {}",
                    customAnnotationTypes.size(), customAnnotationTypes);
        }

        if (!classes.isEmpty()) {
            logger.info("Atmosphere Spring Boot scanner found {} annotated classes", classes.size());
        }

        // Build the same map structure as AnnotationScanningServletContainerInitializer
        Map<Class<? extends Annotation>, Set<Class<?>>> classesByAnnotation = new HashMap<>();
        for (Class<?> clazz : classes) {
            for (Annotation annotation : clazz.getAnnotations()) {
                classesByAnnotation.computeIfAbsent(annotation.annotationType(),
                        k -> new HashSet<>()).add(clazz);
            }
        }
        return classesByAnnotation;
    }

    private void scanPackage(ClassPathScanningCandidateComponentProvider scanner,
                             String packageName, Set<Class<?>> classes) {
        for (BeanDefinition bd : scanner.findCandidateComponents(packageName)) {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                logger.warn("Could not load Atmosphere annotated class: {}", bd.getBeanClassName(), e);
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "atmosphereServletRegistration")
    public ServletRegistrationBean<AtmosphereServlet> atmosphereServletRegistration(
            AtmosphereServlet servlet, AtmosphereProperties properties) {

        ServletRegistrationBean<AtmosphereServlet> registration =
                new ServletRegistrationBean<>(servlet, properties.getServletPath());
        registration.setName("atmosphereServlet");
        registration.setLoadOnStartup(properties.getOrder());
        registration.setAsyncSupported(true);

        Map<String, String> initParams = registration.getInitParameters();

        initParams.put(ApplicationConfig.DISABLE_ATMOSPHERE_INITIALIZER, "true");

        if (properties.getPackages() != null) {
            initParams.put(ApplicationConfig.ANNOTATION_PACKAGE, properties.getPackages());
        }

        initParams.put(ApplicationConfig.PROPERTY_SESSION_SUPPORT,
                String.valueOf(properties.isSessionSupport()));

        if (properties.getBroadcasterClass() != null) {
            initParams.put(ApplicationConfig.BROADCASTER_CLASS, properties.getBroadcasterClass());
        }

        if (properties.getBroadcasterCacheClass() != null) {
            initParams.put(ApplicationConfig.BROADCASTER_CACHE, properties.getBroadcasterCacheClass());
        }

        if (properties.getWebsocketSupport() != null) {
            initParams.put(ApplicationConfig.WEBSOCKET_SUPPORT,
                    String.valueOf(properties.getWebsocketSupport()));
        }

        if (properties.getHeartbeatInterval() != null) {
            initParams.put(ApplicationConfig.HEARTBEAT_INTERVAL_IN_SECONDS,
                    String.valueOf(properties.getHeartbeatInterval().toSeconds()));
        }

        initParams.putAll(properties.getInitParams());

        return registration;
    }

    /**
     * Named subclass of {@link AtmosphereServlet} that injects the pre-scanned
     * annotation map into the servlet context before framework initialization.
     * Using a named class (instead of an anonymous one) ensures stable class
     * naming for Spring AOT / GraalVM native image support.
     */
    static class AnnotationAwareAtmosphereServlet extends AtmosphereServlet {

        private final Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap;
        private final java.util.concurrent.ExecutorService managedExecutor;

        AnnotationAwareAtmosphereServlet(
                Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap,
                java.util.concurrent.ExecutorService managedExecutor) {
            this.annotationMap = annotationMap;
            this.managedExecutor = managedExecutor;
        }

        @Override
        public void init(ServletConfig sc) throws ServletException {
            // Set the annotation map just before framework.init() reads it.
            // This must happen here (not in a ServletContextInitializer) because
            // Atmosphere's AnnotationScanningServletContainerInitializer runs
            // after Spring's initializers and overwrites the attribute with an
            // empty map.
            sc.getServletContext().setAttribute(
                    DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE, annotationMap);

            // If a container-managed ExecutorService is available (e.g., from
            // Jakarta Concurrency 3.1's @ManagedExecutorDefinition with VT
            // support), pre-seed it into Atmosphere's config properties. The
            // ExecutorsFactory checks these properties before creating its own
            // executors, so this causes Atmosphere to use the container's
            // executor instead of creating a new VT pool.
            if (managedExecutor != null) {
                var props = framework().getAtmosphereConfig().properties();
                props.put(org.atmosphere.util.ExecutorsFactory.BROADCASTER_THREAD_POOL,
                        managedExecutor);
                props.put(org.atmosphere.util.ExecutorsFactory.ASYNC_WRITE_THREAD_POOL,
                        managedExecutor);
                // Mark as external so ExecutorsFactory.shutdown() doesn't terminate them
                props.put(org.atmosphere.util.ExecutorsFactory.BROADCASTER_THREAD_POOL
                        + org.atmosphere.util.ExecutorsFactory.EXTERNAL_MARKER, Boolean.TRUE);
                props.put(org.atmosphere.util.ExecutorsFactory.ASYNC_WRITE_THREAD_POOL
                        + org.atmosphere.util.ExecutorsFactory.EXTERNAL_MARKER, Boolean.TRUE);
                logger.info("Using container-managed ExecutorService for Atmosphere: {}",
                        managedExecutor.getClass().getName());
            }

            super.init(sc);
        }
    }
}
