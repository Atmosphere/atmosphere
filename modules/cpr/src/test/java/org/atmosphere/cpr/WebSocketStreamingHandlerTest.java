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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketStreamingHandler;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class WebSocketStreamingHandlerTest {

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
        registerWebSocketHandler("/*", new EchoHandler());

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");

        assertEquals(b.toString(), "yoWebSocket");
    }

    private void registerWebSocketHandler(String path, WebSocketStreamingHandler w) {

        WebSocketProcessorFactory.getDefault()
                        .getWebSocketProcessor(framework).registerWebSocketHandler(path,
                new WebSocketProcessor.WebSocketHandlerProxy(framework.getBroadcasterFactory().lookup(path, true).getClass(), w));
    }

    @Test
    public void invalidPathHandler() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);
        registerWebSocketHandler("/a", new EchoHandler());

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("yoComet").pathInfo("/abcd").build();
        try {
            processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
            fail();
        } catch (Exception ex) {
            assertEquals(ex.getClass(), AtmosphereMappingException.class);
        }
    }

    @Test
    public void multipleWebSocketHandler() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        registerWebSocketHandler("/a", new EchoHandler());
        registerWebSocketHandler("/b", new EchoHandler());

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("a").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "a");

        assertEquals(b.toString(), "a");

        request = new AtmosphereRequestImpl.Builder().destroyable(false).body("b").pathInfo("/b").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "b");

        // The WebSocketHandler is shared.
        assertEquals(b.toString(), "ab");
    }

    @Test
    public void multipleWebSocketAndHandler() throws IOException, ServletException, ExecutionException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        registerWebSocketHandler("/a", new EchoHandler());
        registerWebSocketHandler("/b", new EchoHandler() {
            @Override
            public void onTextStream(WebSocket webSocket, Reader reader) throws IOException {
                webSocket.write("2" + drainReader(reader));
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).body("a").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "a");

        assertEquals(b.toString(), "a");
        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
        final WebSocket w2 = new ArrayBaseWebSocket(b2);
        request = new AtmosphereRequestImpl.Builder().destroyable(false).body("b").pathInfo("/b").build();
        processor.open(w2, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w2, "b");

        // The WebSocketHandler is shared.
        assertEquals(b2.toString(), "2b");
    }

    public static class EchoHandler implements WebSocketStreamingHandler {
        @Override
        public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) throws IOException {
        }

        @Override
        public void onTextMessage(WebSocket webSocket, String data) throws IOException {
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

        @Override
        public void onBinaryStream(WebSocket webSocket, InputStream inputStream) throws IOException {
        }

        @Override
        public void onTextStream(WebSocket webSocket, Reader reader) throws IOException {
            webSocket.write(drainReader(reader));
        }
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

    protected static String drainReader(Reader r) throws IOException {
        CharBuffer cb = CharBuffer.allocate(8192);
        int read = 0;
        while (read > -1) {
            cb.position(cb.position() + read);
            read = r.read(cb.array(), cb.position(), cb.remaining());
        }
        cb.flip();
        return cb.toString();
    }


}
