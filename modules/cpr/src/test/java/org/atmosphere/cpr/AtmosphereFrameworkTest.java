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
package org.atmosphere.cpr;

import org.atmosphere.util.ServletContextFactory;
import org.atmosphere.interceptor.AndroidAtmosphereInterceptor;
import org.atmosphere.interceptor.CacheHeadersInterceptor;
import org.atmosphere.interceptor.CorsInterceptor;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.interceptor.IdleResourceInterceptor;
import org.atmosphere.interceptor.JavaScriptProtocol;
import org.atmosphere.interceptor.OnDisconnectInterceptor;
import org.atmosphere.interceptor.PaddingAtmosphereInterceptor;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.atmosphere.interceptor.WebSocketMessageSuspendInterceptor;
import org.atmosphere.websocket.protocol.SimpleHttpProtocol;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;

public class AtmosphereFrameworkTest {

    /**
     * <p>
     * Test interceptor installation through meta service file declaration.
     * </p>
     *
     * @throws Exception if test fails
     */
    @Test
    public void testInterceptorInstalledByMetaService() throws Exception {
        final AtmosphereFramework framework = new AtmosphereFramework().addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        framework.addAtmosphereHandler("/*", mock(AtmosphereHandler.class));

        // Where the file describing the interceptor to install is stored
        framework.metaServicePath = "META-INF/test-services/";
        framework.init();
        final AsynchronousProcessor processor = new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public org.atmosphere.cpr.Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return action(req, res);
            }
        };

        framework.setAsyncSupport(processor);
        final AtomicReference<Object> value = new AtomicReference<Object>();

        // Intercepts interceptor call
        final AtmosphereRequest r = new AtmosphereRequestImpl.Builder().request(new HttpServletRequestWrapper(new AtmosphereRequestImpl.NoOpsRequest()) {
            @Override
            public void setAttribute(String name, Object o) {
                if (MyInterceptor.class.getName().equals(name)) {
                    value.set(o);
                }
                super.setAttribute(name, o);
            }
        }).build();
        processor.action(r, AtmosphereResponseImpl.newInstance());
        assertNotNull(value.get());

        // The interceptor must be installed and called one time.
        assertEquals(1, value.get());
    }

    @Test
    public void testBroadcasterFactory() throws Exception {
        AtmosphereFramework f = new AtmosphereFramework();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", f.getAtmosphereConfig());
        f.setBroadcasterFactory(factory);
        assertNotNull(f.getBroadcasterFactory());
    }

    @Test
    public void testServletContextFactory() throws ServletException {
        AtmosphereFramework f = new AtmosphereFramework().addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        f.init();
        assertNotNull(ServletContextFactory.getDefault().getServletContext());
    }

    @Test
    public void testReload() throws ServletException {
        AtmosphereFramework f = new AtmosphereFramework().addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        f.init();
        f.destroy();
        f.init();
        assertNotNull(f.getBroadcasterFactory());
    }

    @Test
    public void testAtmosphereServlet() throws ServletException {
        AtmosphereServlet s = new MyAtmosphereServlet();
        s.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                if (ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356.equals(name)) {
                    return "true";
                }
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
        assertNotNull(s);
    }

    @Test
    public void testAtmosphereFrameworkListener() throws ServletException {
        AtmosphereServlet s = new MyAtmosphereServlet();
        final AtomicInteger count = new AtomicInteger();
        s.framework().frameworkListener(new AtmosphereFrameworkListener() {
            @Override
            public void onPreInit(AtmosphereFramework f) {
                count.incrementAndGet();
            }

            @Override
            public void onPostInit(AtmosphereFramework f) {
                count.incrementAndGet();
            }

            @Override
            public void onPreDestroy(AtmosphereFramework f) {
                count.incrementAndGet();
            }

            @Override
            public void onPostDestroy(AtmosphereFramework f) {
                count.incrementAndGet();
            }
        });

        s.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                if (ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356.equals(name)) {
                    return "true";
                }
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
        s.destroy();
        assertEquals(count.get(), 4);
    }

    public class MyAtmosphereServlet extends AtmosphereServlet {

        @Override
        public void init(ServletConfig config) throws ServletException {

            super.init(config);
            framework().setBroadcasterFactory(new MyBroadcasterFactory());
        }

    }

    /**
     * An interceptor for {@link #testInterceptorInstalledByMetaService()}.
     */
    public static final class MyInterceptor implements AtmosphereInterceptor {

        /**
         * Counts number of calls.
         */
        private int call;

        @Override
        public Action inspect(final AtmosphereResource r) {
            r.getRequest().setAttribute(MyInterceptor.class.getName(), ++call);
            return Action.CONTINUE;
        }

        @Override
        public void postInspect(final AtmosphereResource r) {
        }

        @Override
        public void destroy() {
        }

        @Override
        public void configure(final AtmosphereConfig config) {
        }
    }

    public static final class MyBroadcasterFactory implements BroadcasterFactory {

        @Override
        public void configure(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
        }

        @Override
        public Broadcaster get() {
            return null;
        }

        @Override
        public Broadcaster get(Object id) {
            return null;
        }

        @Override
        public <T extends Broadcaster> T get(Class<T> c, Object id) {
            return null;
        }

        @Override
        public void destroy() {

        }

        @Override
        public boolean add(Broadcaster b, Object id) {
            return false;
        }

        @Override
        public boolean remove(Broadcaster b, Object id) {
            return false;
        }

        @Override
        public <T extends Broadcaster> T lookup(Class<T> c, Object id) {
            return null;
        }

        @Override
        public <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull) {
            return null;
        }

        @Override
        public <T extends Broadcaster> T lookup(Object id) {
            return null;
        }

        @Override
        public <T extends Broadcaster> T lookup(Object id, boolean createIfNull) {
            return null;
        }

        @Override
        public boolean remove(Object id) {
            return false;
        }

        @Override
        public Collection<Broadcaster> lookupAll() {
            return null;
        }

        @Override
        public BroadcasterFactory addBroadcasterListener(BroadcasterListener b) {
            return this;
        }

        @Override
        public BroadcasterFactory removeBroadcasterListener(BroadcasterListener b) {
            return this;
        }

        @Override
        public Collection<BroadcasterListener> broadcasterListeners() {
            return null;
        }
    }

    @Test
    public void testIsInit() throws ServletException {
        AtmosphereFramework f = new AtmosphereFramework().addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        f.init();

        final AtomicBoolean b = new AtomicBoolean();
        f.getAtmosphereConfig().startupHook(new AtmosphereConfig.StartupHook() {
                    @Override
                    public void started(AtmosphereFramework framework) {
                        b.set(true);
                    }
                });

        assertTrue(b.get());
    }

    @DataProvider(name = "autodetectBroadcaster")
    private Object[][] autodetectBroadcasterDataProvider() {
        return new Object[][]{{null, true}, {"true", true}, {"false", false}};
    }

    @Test(dataProvider = "autodetectBroadcaster")
    public void autodetectBroadcaster(String autodetectBroadcasterConfig, boolean expectedAutodetect) {
        ServletConfig servletConfig = mock(ServletConfig.class);
        when(servletConfig.getInitParameter(ApplicationConfig.AUTODETECT_BROADCASTER)).thenReturn(autodetectBroadcasterConfig);

        AtmosphereFramework framework = new AtmosphereFramework();
        framework.servletConfig = servletConfig;

        boolean actualAutodetect = framework.autodetectBroadcaster();
        assertEquals(actualAutodetect, expectedAutodetect);
    }

    @Test
    public void autodetectBroadcasterServletConfigIsNull() {
        AtmosphereFramework framework = new AtmosphereFramework();

        boolean actualAutodetect = framework.autodetectBroadcaster();
        assertTrue(actualAutodetect);
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testDefaultInterceptorsImmutable() {
        AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.add(CorsInterceptor.class);
    }

    @Test
    public void testDefaultInterceptorsContents() {
        assertEquals(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.size(), 10);
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(CorsInterceptor.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(CacheHeadersInterceptor.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(PaddingAtmosphereInterceptor.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(AndroidAtmosphereInterceptor.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(HeartbeatInterceptor.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(SSEAtmosphereInterceptor.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(JavaScriptProtocol.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(WebSocketMessageSuspendInterceptor.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(OnDisconnectInterceptor.class));
        assertTrue(AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(IdleResourceInterceptor.class));
    }

    @Test
    public void testWebSocketProtocolInitParamPreventsAutoDetection() throws Exception {
        ServletConfig servletConfig = mock(ServletConfig.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);
        when(servletConfig.getInitParameter(ApplicationConfig.WEBSOCKET_PROTOCOL))
                .thenReturn(SimpleHttpProtocol.class.getName());
        when(servletContext.getInitParameter(ApplicationConfig.WEBSOCKET_PROTOCOL))
                .thenReturn(SimpleHttpProtocol.class.getName());

        AtmosphereFramework framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        framework.addAtmosphereHandler("/*", mock(AtmosphereHandler.class));
        framework.init(servletConfig);

        assertEquals(framework.getWebSocketProtocol().getClass(), SimpleHttpProtocol.class);
    }
}
