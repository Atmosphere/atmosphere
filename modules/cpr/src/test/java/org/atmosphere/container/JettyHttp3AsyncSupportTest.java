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
package org.atmosphere.container;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Targeted tests for {@link JettyHttp3AsyncSupport} — the alternate Jetty 12
 * HTTP/3 backend (Reactor Netty's sidecar is the default for Spring Boot).
 *
 * <p>Unit-testing this class is bounded by JSR356AsyncSupport's constructor
 * which requires a {@link ServerContainer} on the servlet context. We mock
 * the minimum surface so the constructor doesn't blow up, then assert the
 * HTTP/3-specific behavior: graceful no-op when no Jetty {@code Server} is
 * resolvable, container-name suffix, and {@code supportWebTransport()}
 * returning false in that case (Invariant #5: Runtime Truth — capabilities
 * track actual runtime state, not classpath presence).</p>
 *
 * <p>Full HTTP/3 integration is exercised by E2E tests; this fills the unit
 * gap so a refactor of the resolveJettyServer / supportWebTransport path
 * doesn't regress without a CI signal.</p>
 */
class JettyHttp3AsyncSupportTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private ServletContext servletContext;
    private ServerContainer serverContainer;

    @BeforeEach
    void setUp() {
        framework = new AtmosphereFramework(true, false);
        // Spy the framework's config so we can override getServletContext()
        // without calling the real method (which NPEs in test mode because
        // no real ServletConfig is wired up). Mockito.doReturn skips the
        // real-method invocation, unlike when(...).thenReturn which calls
        // first and then replaces.
        config = Mockito.spy(framework.getAtmosphereConfig());
        servletContext = mock(ServletContext.class);
        serverContainer = mock(ServerContainer.class);

        // Minimum scaffolding so JSR356AsyncSupport's constructor passes.
        when(servletContext.getAttribute(ServerContainer.class.getName()))
                .thenReturn(serverContainer);
        when(servletContext.getServerInfo()).thenReturn("Mock/1.0");
        when(servletContext.getContextPath()).thenReturn("");
        when(serverContainer.getDefaultMaxBinaryMessageBufferSize()).thenReturn(8192);
        when(serverContainer.getDefaultMaxTextMessageBufferSize()).thenReturn(8192);
        when(serverContainer.getDefaultMaxSessionIdleTimeout()).thenReturn(0L);
        Mockito.doReturn(servletContext).when(config).getServletContext();

        // getContainerName() bottoms out at config.getServletConfig().getServletContext().getServerInfo()
        // — without a stub the spy returns null and a NullPointerException
        // bubbles up. Plug it with a minimal ServletConfig that hands back
        // the same mocked context.
        var servletConfig = mock(ServletConfig.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);
        Mockito.doReturn(servletConfig).when(config).getServletConfig();

        // Pre-set the JSR356 mapping path so JSR356AsyncSupport's constructor
        // doesn't try to derive it via IOUtils.guestServletPath (which throws
        // when the config has no real servletConfig). AtmosphereConfig reads
        // init params through framework.getServletConfig() which is null in
        // test mode, so we stub the spy directly.
        Mockito.doReturn("/atmosphere").when(config).getInitParameter(
                org.atmosphere.cpr.ApplicationConfig.JSR356_MAPPING_PATH);
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    @Test
    void constructorIsGracefulWhenNoJettyServerResolvable() {
        // No Jetty Server attribute → the HTTP/3 connector branch logs WARN
        // and skips. The constructor MUST NOT throw — apps without Jetty on
        // the classpath should still boot via the JSR356 base class.
        when(servletContext.getAttribute("org.eclipse.jetty.server.Server")).thenReturn(null);

        var support = assertDoesNotThrow(() -> new JettyHttp3AsyncSupport(config),
                "Constructor MUST swallow missing-Jetty-Server gracefully (Invariant #1: do not throw past the user's reach)");

        assertNotNull(support);
        assertFalse(support.supportWebTransport(),
                "supportWebTransport() MUST return false when http3Connector failed to bind — "
                        + "Invariant #5: capabilities track actual runtime state");
    }

    @Test
    void containerNameIncludesHttp3Suffix() {
        when(servletContext.getAttribute("org.eclipse.jetty.server.Server")).thenReturn(null);

        var support = new JettyHttp3AsyncSupport(config);

        var name = support.getContainerName();
        assertNotNull(name);
        assertTrue(name.endsWith(" with Jetty HTTP/3"),
                "container name must surface the HTTP/3 augmentation so observability "
                        + "tools can identify the runtime — got: " + name);
    }

    @Test
    void portInitParameterIsHonored() throws Exception {
        // resolveHttp3Port is private; verify behavior via reflection so a
        // refactor that breaks the init-param contract is caught here.
        when(servletContext.getAttribute("org.eclipse.jetty.server.Server")).thenReturn(null);
        when(config.getInitParameter("atmosphere.http3.port")).thenReturn("9443");

        var support = new JettyHttp3AsyncSupport(config);
        var method = JettyHttp3AsyncSupport.class.getDeclaredMethod("resolveHttp3Port", AtmosphereConfig.class);
        method.setAccessible(true);
        int resolved = (int) method.invoke(support, config);

        assertEquals(9443, resolved,
                "atmosphere.http3.port init-param MUST override the default 4443 port");
    }

    @Test
    void portFallsBackToDefaultWhenInitParameterAbsent() throws Exception {
        when(servletContext.getAttribute("org.eclipse.jetty.server.Server")).thenReturn(null);
        when(config.getInitParameter("atmosphere.http3.port")).thenReturn(null);

        var support = new JettyHttp3AsyncSupport(config);
        var method = JettyHttp3AsyncSupport.class.getDeclaredMethod("resolveHttp3Port", AtmosphereConfig.class);
        method.setAccessible(true);
        int resolved = (int) method.invoke(support, config);

        assertEquals(4443, resolved,
                "default HTTP/3 port (well-known QUIC dev port) must be returned when no init-param is set");
    }

    @Test
    void supportsWebTransportFlagDoesNotThrowEvenOnRepeatedCalls() {
        when(servletContext.getAttribute("org.eclipse.jetty.server.Server")).thenReturn(null);
        var support = new JettyHttp3AsyncSupport(config);

        // Idempotence sanity: the flag is a simple field check but we want to
        // pin "no surprises on repeated reads" — the value MUST stay false
        // for the lifetime of an instance that never bound an HTTP/3 connector.
        for (int i = 0; i < 5; i++) {
            assertFalse(support.supportWebTransport(),
                    "supportWebTransport() MUST stay false for an instance that never bound (call " + i + ")");
        }
    }

    @Test
    void resolveJettyServerReturnsNullWhenContextHasNoServerAttribute() throws Exception {
        when(servletContext.getAttribute("org.eclipse.jetty.server.Server")).thenReturn(null);
        // Reflection lookup also fails because there's no real ServletContextHandler;
        // covered by the stubbed context that doesn't dispatch to that codepath.

        var support = new JettyHttp3AsyncSupport(config);
        var method = JettyHttp3AsyncSupport.class.getDeclaredMethod("resolveJettyServer", ServletContext.class);
        method.setAccessible(true);

        var result = method.invoke(support, servletContext);

        assertNull(result,
                "resolveJettyServer must return null when the context lacks the Jetty attribute "
                        + "AND the reflective fallback also fails — Invariant #5: do not lie about presence");
    }

    @Test
    void doesNotThrowWhenContextAttributeIsWrongType() {
        // Defensive: a future framework version (or a bug in user code) might
        // put a non-Server object on the well-known attribute. The constructor
        // MUST treat that as "no Jetty Server" and proceed gracefully.
        when(servletContext.getAttribute("org.eclipse.jetty.server.Server"))
                .thenReturn("not-a-server-instance");

        assertDoesNotThrow(() -> new JettyHttp3AsyncSupport(config));
    }

    @Test
    void instanceIsAJsr356AsyncSupport() {
        when(servletContext.getAttribute("org.eclipse.jetty.server.Server")).thenReturn(null);
        var support = new JettyHttp3AsyncSupport(config);

        // Pinning the class hierarchy: the Spring Boot starter's
        // capability-detection path uses instanceof JSR356AsyncSupport to
        // decide whether to install the WebSocket bridge. A refactor that
        // breaks the inheritance would silently downgrade clients.
        assertTrue(support instanceof JSR356AsyncSupport,
                "JettyHttp3AsyncSupport MUST extend JSR356AsyncSupport so existing "
                        + "WebSocket capability checks keep finding it");
    }

}
