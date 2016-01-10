/*
 * Copyright 2015 Jason Burgess
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
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

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
        f.setBroadcasterFactory(new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", f.getAtmosphereConfig()));
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
        public void removeAllAtmosphereResource(AtmosphereResource r) {

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
}
