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
package org.atmosphere.cpr;

import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.atmosphere.websocket.WebSocketHandlerAdapter;
import org.atmosphere.websocket.WebSocketProcessor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.DISCONNECT;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class WebSocketProcessorTest {

    private AtmosphereFramework framework;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return action(req, res);
            }
        });
        framework.addInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false");
        framework.init(new ServletConfig() {
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
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }

    @AfterMethod
    public void destroy() throws Throwable {
        framework.destroy();
    }

    @Test
    public void basicWorkflow() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.getBroadcaster().addAtmosphereResource(resource.suspend());
                resource.getResponse().write(resource.getRequest().getReader().readLine());
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                event.getResource().write(event.getMessage().toString().getBytes());
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");
        framework.getBroadcasterFactory().lookup("/*").broadcast("yoBroadcast").get();

        assertEquals(b.toString(), "yoCometyoWebSocketyoBroadcast");

    }

    @Test
    public void encodeURLProxyTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);
        final AtomicReference<String> url = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                url.set(resource.getResponse().encodeRedirectURL("http://127.0.0.1:8080"));
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");

        assertEquals(url.get(), "http://127.0.0.1:8080");

    }

    @Test
    public void basicBackwardCompatbileWorkflow() throws Throwable {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        framework.getBroadcasterFactory().remove("/*");

        framework.addInitParameter(ApplicationConfig.BACKWARD_COMPATIBLE_WEBSOCKET_BEHAVIOR, "true")
                .addAtmosphereHandler("/*", new AtmosphereHandler() {

                    @Override
                    public void onRequest(AtmosphereResource resource) throws IOException {
                        resource.getBroadcaster().addAtmosphereResource(resource);
                        resource.getResponse().write(resource.getRequest().getReader().readLine());
                    }

                    @Override
                    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                        event.getResource().write(event.getMessage().toString().getBytes());
                    }

                    @Override
                    public void destroy() {
                    }
                });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");
        framework.getBroadcasterFactory().lookup("/*").broadcast("yoBroadcast").get();

        assertEquals(b.toString(), "yoCometyoWebSocketyoBroadcastyoBroadcast");

    }

    @Test
    public void basicWebSocketCookieTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<Cookie> cValue = new AtomicReference<Cookie>();
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                Cookie[] c = event.getResource().getRequest().getCookies();
                cValue.set(c[0]);
            }

            @Override
            public void destroy() {
            }
        });
        Set<Cookie> c = new HashSet<Cookie>();
        c.add(new Cookie("yo", "man"));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().cookies(c).pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(cValue.get());

        Cookie i = c.iterator().next();
        assertEquals(i.getName(), cValue.get().getName());
        assertEquals(i.getValue(), cValue.get().getValue());
    }

    @Test
    public void onDisconnectAtmosphereRequestAttribute() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);
        final AtomicReference<String> uuid = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.addEventListener(new WebSocketEventListenerAdapter() {
                    @Override
                    public void onDisconnect(WebSocketEvent event) {
                        uuid.set((String) event.webSocket().resource().getRequest().getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID));
                    }
                });
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");
        processor.notifyListener(w, new WebSocketEventListener.WebSocketEvent("Disconnect", DISCONNECT, w));

        assertNotNull(uuid.get());
        assertEquals(uuid.get(), request.getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID));
    }

    @Test
    public void inClosePhaseTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);
        final AtomicBoolean closed = new AtomicBoolean();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(final AtmosphereResource resource) throws IOException {
                resource.addEventListener(new WebSocketEventListenerAdapter() {
                    @Override
                    public void onClose(WebSocketEvent event) {
                        closed.set(true);
                    }
                });

                AtmosphereResourceEventImpl.class.cast(resource.getAtmosphereResourceEvent()).isClosedByClient(true);
                AsynchronousProcessor.class.cast(framework.getAsyncSupport()).completeLifecycle(resource, false);
                processor.close(w, 1005);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));

        assertFalse(closed.get());
    }

    @Test
    public void onCloseAtmosphereRequestAttribute() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);
        final AtomicReference<String> uuid = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.addEventListener(new WebSocketEventListenerAdapter() {
                    @Override
                    public void onClose(WebSocketEvent event) {
                        uuid.set((String) event.webSocket().resource().getRequest().getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID));
                    }
                });
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");
        processor.notifyListener(w, new WebSocketEventListener.WebSocketEvent("Close", WebSocketEventListener.WebSocketEvent.TYPE.CLOSE, w));

        assertNotNull(uuid.get());
        assertEquals(uuid.get(), request.getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID));
    }

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

    @Test
    public void basicProgrammaticAPIWorkflow() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        framework.addWebSocketHandler("/*", new WebSocketProcessor.WebSocketHandlerProxy(new WebSocketHandlerAdapter() {

            @Override
            public void onTextMessage(WebSocket webSocket, String data) throws IOException {
                webSocket.write(data);
            }

            @Override
            public void onOpen(WebSocket webSocket) throws IOException {
                webSocket.write(webSocket.resource().getRequest().getReader().readLine());
            }
        }));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");
        framework.getBroadcasterFactory().lookup("/*").broadcast("yoBroadcast").get();

        assertEquals(b.toString(), "yoCometyoWebSocketyoBroadcast");

    }

    @Test
    public void undetectedCloseWebSocketTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<Cookie> cValue = new AtomicReference<Cookie>();
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                Cookie[] c = event.getResource().getRequest().getCookies();
                cValue.set(c[0]);
            }

            @Override
            public void destroy() {
            }
        });
        Map<String, String> m = new HashMap<String, String>();
        m.put(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.WEBSOCKET_TRANSPORT);

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().headers(m).pathInfo("/a").build();
        request.setAttribute(FrameworkConfig.WEBSOCKET_MESSAGE, null);
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));

        final AtomicBoolean dirtyDisconnect = new AtomicBoolean();
        request.setAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID, w.resource().uuid());
        m.put(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.WEBSOCKET_TRANSPORT);
        request.headers(m);
        AtmosphereResource dup = framework.getAtmosphereConfig().resourcesFactory()
                .create(framework.config, w.resource().uuid(), request).suspend();
        w.resource(dup);
        dup.addEventListener(new AtmosphereResourceEventListenerAdapter.OnDisconnect() {
            @Override
            public void onDisconnect(AtmosphereResourceEvent event) {
                if (event.isCancelled()) dirtyDisconnect.set(true);
            }
        });
        request.setAttribute(FrameworkConfig.WEBSOCKET_MESSAGE, null);

        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));

        r.get().getBroadcaster().broadcast("yo").get();
        assertTrue(dirtyDisconnect.get());
    }
}
