/*
 * Copyright 2015 Jean-Francois Arcand
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
package org.atmosphere.annotation;

import org.atmosphere.config.service.Delete;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Post;
import org.atmosphere.config.service.Put;
import org.atmosphere.config.service.Ready;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResourceSessionFactory;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultMetaBroadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.MetaBroadcaster;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.util.ExcludeSessionBroadcaster;
import org.atmosphere.util.SimpleBroadcaster;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketFactory;
import org.atmosphere.websocket.WebSocketHandlerAdapter;
import org.atmosphere.websocket.WebSocketProcessor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter.OnSuspend;
import static org.atmosphere.cpr.HeaderConfig.LONG_POLLING_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class ManagedAtmosphereHandlerTest {
    private AtmosphereFramework framework;
    private static final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
    private static final AtomicReference<String> message = new AtomicReference<String>();


    public final class ArrayBaseWebSocket extends WebSocket {

        private final OutputStream outputStream;

        public ArrayBaseWebSocket(OutputStream outputStream) {
            super(framework.getAtmosphereConfig());
            this.outputStream = outputStream;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebSocket write(String s) throws IOException {
            outputStream.write(s.getBytes());
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) throws IOException {
            outputStream.write(b, offset, length);
            return this;
        }

        @Override
        public void close() {
        }
    }

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName());
        framework.addAnnotationPackage(ManagedGet.class);
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {

            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return suspended(req, res);
            }

            public void action(AtmosphereResourceImpl r) {
                try {
                    resumed(r.getRequest(), r.getResponse());
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ServletException e) {
                    e.printStackTrace();
                }
            }
        }).init(new ServletConfig() {
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
                return ApplicationConfig.CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS.equals(name) ? "10" : null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }

    @AfterMethod
    public void after() {
        r.set(null);
        framework.destroy();
    }

    @ManagedService(path = "/a")
    public final static class ManagedGet {
        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.suspend();
        }
    }

    @Test
    public void testGet() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        r.get().resume();

        assertNotNull(r.get());
    }

    @ManagedService(path = "/b")
    public final static class ManagedPost {
        @Post
        public void post(AtmosphereResource resource) {
            resource.suspend();
            r.set(resource);
        }
    }

    @Test
    public void testPost() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/b").method("POST").body("test").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();

    }

    @Test
    public void testBinaryPost() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/b").method("POST").body("test".getBytes()).build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();

    }

    @ManagedService(path = "/c")
    public final static class ManagedDelete {
        @Delete
        public void delete(AtmosphereResource resource) {
            resource.suspend();
            r.set(resource);
        }
    }

    @Test
    public void testDelete() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/c").method("DELETE").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
    }

    @ManagedService(path = "/d")
    public final static class ManagedPut {
        @Put
        public void put(AtmosphereResource resource) {
            resource.suspend();
            r.set(resource);
        }
    }

    @Test
    public void testPut() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/d").method("PUT").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
    }

    @ManagedService(path = "/e")
    public final static class ManagedMessage {

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.addEventListener(new OnSuspend() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/e").method("POST").body("message").build();

                    try {
                        event.getResource().getAtmosphereConfig().framework().doCometSupport(request, AtmosphereResponseImpl.newInstance());
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ServletException e) {
                        e.printStackTrace();
                    }
                }
            }).suspend();

        }

        @Message
        public void message(String m) {
            message.set(m);
        }
    }

    @Test
    public void testMessage() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/e").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertEquals(message.get(), "message");

    }

    @ManagedService(path = "/k")
    public final static class ManagedMessageWithResource {

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.addEventListener(new OnSuspend() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/k").method("POST").body("message").build();

                    try {
                        event.getResource().getAtmosphereConfig().framework().doCometSupport(request, AtmosphereResponseImpl.newInstance());
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ServletException e) {
                        e.printStackTrace();
                    }
                }
            }).suspend();

        }

        @Message
        public void message(AtmosphereResource resource, String m) {
            message.set(m);
            assertSame(resource, r.get());
        }
    }

    @Test
    public void testMessageWithResource() throws IOException, ServletException {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/k").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertEquals(message.get(), "message");
    }

    @ManagedService(path = "/j")
    public final static class ManagedSuspend {
        @Get
        public void get(AtmosphereResource resource) {
            // Normally we don't need that, this will be done using an Interceptor.
            resource.suspend();
        }

        @Ready
        public void suspend(AtmosphereResource resource) {
            r.set(resource);
        }
    }

    @Test
    public void testSuspend() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/j").method("GET").build();
        request.header(X_ATMOSPHERE_TRANSPORT, LONG_POLLING_TRANSPORT);
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
    }

    public final static class I extends AtmosphereInterceptorAdapter {
        @Override
        public PRIORITY priority() {
            return InvokationOrder.FIRST_BEFORE_DEFAULT;
        }

        @Override
        public String toString() {
            return "XXX";
        }
    }

    @ManagedService(path = "/priority", interceptors = I.class)
    public final static class Priority {
        @Get
        public void get(AtmosphereResource resource) {
            // Normally we don't need that, this will be done using an Interceptor.
            resource.suspend();
        }

        @Ready
        public void suspend(AtmosphereResource resource) {
            r.set(resource);
        }
    }

    @Test
    public void testPriority() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/priority").method("GET").build();
        request.header(X_ATMOSPHERE_TRANSPORT, LONG_POLLING_TRANSPORT);
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(framework.getAtmosphereHandlers().get("/priority").interceptors.getFirst().toString(), "XXX");

        assertNotNull(r.get());
    }

    @ManagedService(path = "/override", broadcaster = ExcludeSessionBroadcaster.class)
    public final static class OverrideBroadcaster {
        @Get
        public void get(AtmosphereResource resource) {
            // Normally we don't need that, this will be done using an Interceptor.
            resource.suspend();
        }

        @Ready
        public void suspend(AtmosphereResource resource) {
            r.set(resource);
        }
    }

    @Test
    public void testOverrideBroadcaster() throws IOException, ServletException {
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName());

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/override").method("GET").build();
        request.header(X_ATMOSPHERE_TRANSPORT, LONG_POLLING_TRANSPORT);
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertNotNull(r.get());
        assertEquals(r.get().getBroadcaster().getClass().getName(), SimpleBroadcaster.class.getName());

    }

    @ManagedService(path = "/readerInjection")
    public final static class ReaderInjection {
        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.addEventListener(new OnSuspend() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/readerInjection").method("POST").body("message").build();

                    try {
                        event.getResource().getAtmosphereConfig().framework().doCometSupport(request, AtmosphereResponseImpl.newInstance());
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ServletException e) {
                        e.printStackTrace();
                    }
                }
            }).suspend();

        }

        @Message
        public void message(Reader reader) {
            try {
                message.set(new BufferedReader(reader).readLine());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void testReaderMessage() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/readerInjection").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertEquals(message.get(), "message");

    }

    @ManagedService(path = "/inputStreamInjection")
    public final static class InputStreamInjection {
        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.addEventListener(new OnSuspend() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/inputStreamInjection").method("POST").body("message").build();

                    try {
                        event.getResource().getAtmosphereConfig().framework().doCometSupport(request, AtmosphereResponseImpl.newInstance());
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ServletException e) {
                        e.printStackTrace();
                    }
                }
            }).suspend();

        }

        @Message
        public void message(InputStream reader) {
            try {
                message.set(new BufferedReader(new InputStreamReader(reader)).readLine());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void testInputStreamMessage() throws IOException, ServletException {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/inputStreamInjection").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertEquals(message.get(), "message");
    }

    @ManagedService(path = "/heartbeat")
    public final static class Heartbeat {
        static final String paddingData = new String(new HeartbeatInterceptor().getPaddingBytes());

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
        }

        @org.atmosphere.config.service.Heartbeat
        public void heartbeat(AtmosphereResourceEvent resource) {
            message.set(paddingData);
        }
    }

    @Test
    public void testHeartbeat() throws IOException, ServletException {
        // Open connection
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .pathInfo("/heartbeat")
                .method("GET")
                .build();

        request.header(X_ATMOSPHERE_TRANSPORT, WEBSOCKET_TRANSPORT);
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        // Check suspend
        final AtmosphereResource res = r.get();
        assertNotNull(res);

        // Send heartbeat
        request = new AtmosphereRequestImpl.Builder()
                .pathInfo("/heartbeat")
                .method("POST")
                .body(Heartbeat.paddingData)
                .build();
        request.header(X_ATMOSPHERE_TRANSPORT, WEBSOCKET_TRANSPORT);
        request.setAttribute(HeartbeatInterceptor.INTERCEPTOR_ADDED, "");
        res.initialize(res.getAtmosphereConfig(), res.getBroadcaster(), request, AtmosphereResponseImpl.newInstance(), framework.getAsyncSupport(), res.getAtmosphereHandler());
        request.setAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE, res);
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(message.get());
        assertEquals(message.get(), Heartbeat.paddingData);
    }

    @ManagedService(path = "/injectAnnotation")
    public final static class InjectAnnotation {

        @Inject
        private AtmosphereConfig config;
        @Inject
        private AtmosphereFramework f;
        @Inject
        private AtmosphereResourceFactory resourceFactory;
        @Inject
        private BroadcasterFactory bFactory;
        @Inject
        private DefaultMetaBroadcaster m;
        @Inject
        private AtmosphereResourceSessionFactory sessionFactory;
        @Inject
        private MetaBroadcaster metaBroadcaster;

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.addEventListener(new OnSuspend() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/injectAnnotation").method("POST").body("message").build();

                    try {
                        event.getResource().getAtmosphereConfig().framework().doCometSupport(request, AtmosphereResponseImpl.newInstance());
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ServletException e) {
                        e.printStackTrace();
                    }
                }
            }).suspend();

        }

        @Message
        public void message(String s) {
            message.set(config.toString());
            message.set(f.toString());
            message.set(resourceFactory.toString());
            message.set(bFactory.toString());
            message.set(m.toString());
            message.set(sessionFactory.toString());
            message.set(metaBroadcaster.toString());
        }
    }

    @Test
    public void testInjectAnnotation() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/injectAnnotation").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertEquals(message.get(), framework.metaBroadcaster().toString());

    }

    @ManagedService(path = "/postConstruct")
    public final static class PostConstructAnnotation {

        @Inject
        private AtmosphereFramework framework;

        @PostConstruct
        private void postConstruct() {
            if (message.get() == "postConstruct") message.set("error");
            message.set("postConstruct");
        }

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.addEventListener(new OnSuspend() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/postConstruct").method("POST").body("message").build();

                    try {
                        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ServletException e) {
                        e.printStackTrace();
                    }
                }
            }).suspend();

        }

        @Message
        public void message(String s) {
            message.set(message.get() + s);
        }
    }

    @Test
    public void testPostConstruct() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/postConstruct").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertEquals(message.get(), "postConstructmessage");

    }

    public final static class Interceptor implements AtmosphereInterceptor {

        public static boolean invoked = false;

        @Override
        public Action inspect(AtmosphereResource r) {
            invoked = true;
            return Action.CONTINUE;
        }

        @Override
        public void postInspect(AtmosphereResource r) {

        }

        @Override
        public void destroy() {

        }

        @Override
        public void configure(AtmosphereConfig config) {

        }
    }

    @WebSocketHandlerService(path = "/websocketfactory", interceptors = {Interceptor.class })
    public final static class WebSocketfactoryTest extends WebSocketHandlerAdapter {

        @Inject
        public WebSocketFactory factory;

        @Override
        public void onOpen(WebSocket webSocket) throws IOException {
            WebSocket w = factory.find(webSocket.resource().uuid());
            r.set(w == null ? null : webSocket.resource());
        }
    }

    @Test
    public void testWebSocketFactory() throws IOException, ServletException {

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/websocketfactory").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));

        assertNotNull(r.get());
        assertTrue(Interceptor.invoked);

    }

    @ManagedService(path = "/named")
    public final static class NamedService {

        @Inject
        @Named("/test")
        private Broadcaster broadcaster;

        @Get
        public void get(AtmosphereResource resource) {
            resource.setBroadcaster(broadcaster);
            r.set(resource);
        }

        @Message
        public void message(String s) {
            message.set(message.get() + s);
        }
    }

    @Test
    public void testNamed() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/named").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(r.get().getBroadcaster());
        assertEquals(r.get().getBroadcaster().getID(), "/test");

    }

}
