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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.util.SimpleBroadcaster;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProcessor;

import jakarta.inject.Inject;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class WebSocketHandlerTest {

    private AtmosphereFramework framework;

    @BeforeEach
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
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName());
    }

    @AfterEach
    public void destroy() throws Throwable {
        framework.destroy();
    }

    @Test
    public void basicWorkflow() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);
        registerWebSocketHandler("/*", new WebSocketProcessor.WebSocketHandlerProxy(new EchoHandler()));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");

        assertEquals("yoWebSocket", b.toString());
    }

    @Test
    public void invalidPathHandler() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);
        registerWebSocketHandler("/a", new WebSocketProcessor.WebSocketHandlerProxy(new EchoHandler()));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/abcd").build();
        try {
            processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
            fail();
        } catch (Exception ex) {
            assertEquals(AtmosphereMappingException.class, ex.getClass());
        }
    }

    @Test
    public void multipleWebSocketHandler() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        registerWebSocketHandler("/a", new WebSocketProcessor.WebSocketHandlerProxy(new EchoHandler()));
        registerWebSocketHandler("/b", new WebSocketProcessor.WebSocketHandlerProxy(new EchoHandler()));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("a").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "a");

        assertEquals("a", b.toString());

        request = new AtmosphereRequestImpl.Builder().destroyable(false).body("b").pathInfo("/b").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "b");

        // The WebSocketHandler is shared.
        assertEquals("ab", b.toString());
    }

    @Test
    public void multipleWebSocketAndHandler() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        registerWebSocketHandler("/a", new WebSocketProcessor.WebSocketHandlerProxy(new EchoHandler()));
        registerWebSocketHandler("/b", new WebSocketProcessor.WebSocketHandlerProxy(new EchoHandler() {
            @Override
            public void onTextMessage(WebSocket webSocket, String data) throws IOException {
                webSocket.write("2" + data);
            }
        }));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("a").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "a");

        assertEquals("a", b.toString());
        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
        final WebSocket w2 = new ArrayBaseWebSocket(b2);
        request = new AtmosphereRequestImpl.Builder().destroyable(false).body("b").pathInfo("/b").build();
        processor.open(w2, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w2, "b");

        // The WebSocketHandler is shared.
        assertEquals("2b", b2.toString());
    }

    public static class EchoHandler implements WebSocketHandler {

        @Inject
        private AtmosphereRequest request;

        @Override
        public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) throws IOException {
            webSocket.write(data, offset, length);
        }

        @Override
        public void onTextMessage(WebSocket webSocket, String data) throws IOException {
            webSocket.write(data);
        }

        @Override
        public void onOpen(WebSocket webSocket) throws IOException {
        }

        @Override
        public void onClose(WebSocket webSocket) {
        }

        @Override
        public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        }
    }

    private void registerWebSocketHandler(String path, WebSocketProcessor.WebSocketHandlerProxy w) {
        WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework).registerWebSocketHandler(path, w);
    }

    @Test
    public void testInjection() throws IOException, ServletException, ExecutionException, InterruptedException {
        EchoHandler e = new EchoHandler();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);
        registerWebSocketHandler("/*", new WebSocketProcessor.WebSocketHandlerProxy(e));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");

        assertNotNull(e.request);
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
}
