/*
 * Copyright 2012 Jean-Francois Arcand
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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.protocol.SimpleHttpProtocol;
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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class WebSocketProcessorTest {

    private AtmosphereFramework framework;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
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

    @Test
    public void basicWorkflow() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .newWebSocketProcessor(w);

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.getBroadcaster().addAtmosphereResource(resource);
                resource.getResponse().write(resource.getRequest().getReader().readLine());
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                event.write(event.getMessage().toString().getBytes());
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequest.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(request);
        processor.invokeWebSocketProtocol("yoWebSocket");
        BroadcasterFactory.getDefault().lookup("/*").broadcast("yoBroadcast").get();

        assertEquals(b.toString(), "yoCometyoWebSocketyoBroadcastyoBroadcast");

    }

    @Test
    public void basicWebSocketCookieTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<Cookie> cValue = new AtomicReference<Cookie>();
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .newWebSocketProcessor(new ArrayBaseWebSocket(b));

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

        AtmosphereRequest request = new AtmosphereRequest.Builder().cookies(c).pathInfo("/a").build();
        processor.open(request);

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
                .newWebSocketProcessor(w);
        final AtomicReference<String> uuid = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.addEventListener(new WebSocketEventListenerAdapter() {
                    @Override
                    public void onDisconnect(WebSocketEvent event) {
                        uuid.set((String) event.webSocket().resource().getRequest().getAttribute(FrameworkConfig.WEBSOCKET_ATMOSPHERE_RESOURCE));
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

        AtmosphereRequest request = new AtmosphereRequest.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(request);
        processor.invokeWebSocketProtocol("yoWebSocket");
        processor.notifyListener(new WebSocketEventListener.WebSocketEvent("Disconnect", WebSocketEventListener.WebSocketEvent.TYPE.DISCONNECT, w));

        assertNotNull(uuid.get());
    }

    @Test
    public void onCloseAtmosphereRequestAttribute() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .newWebSocketProcessor(w);
        final AtomicReference<String> uuid = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.addEventListener(new WebSocketEventListenerAdapter() {
                    @Override
                    public void onClose(WebSocketEvent event) {
                        uuid.set((String) event.webSocket().resource().getRequest().getAttribute(FrameworkConfig.WEBSOCKET_ATMOSPHERE_RESOURCE));
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

        AtmosphereRequest request = new AtmosphereRequest.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(request);
        processor.invokeWebSocketProtocol("yoWebSocket");
        processor.notifyListener(new WebSocketEventListener.WebSocketEvent("Close", WebSocketEventListener.WebSocketEvent.TYPE.CLOSE, w));

        assertNotNull(uuid.get());
    }

    public final static class ArrayBaseWebSocket extends WebSocket {

        private final OutputStream outputStream;

        public ArrayBaseWebSocket(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket redirect(AtmosphereResponse r, String location) throws IOException {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket write(AtmosphereResponse r, String data) throws IOException {
            outputStream.write(data.getBytes());
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket write(AtmosphereResponse r, byte[] data) throws IOException {
            outputStream.write(data);
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
            outputStream.write(data, offset, length);
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close(AtmosphereResponse r) throws IOException {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket flush(AtmosphereResponse r) throws IOException {
            return this;
        }
    }
}
