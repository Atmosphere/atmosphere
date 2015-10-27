/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.interceptor.TrackMessageSizeB64Interceptor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class TrackMessageSizeInterceptorTest {

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
    public void testTrackMessageSizeEnabled() throws Exception {
        testTrackMessageSize(true, new TrackMessageSizeInterceptor(), "7|yoComet11|yoWebSocket");
    }

    @Test
    public void testTrackMessageSizeDisabled() throws Exception {
        testTrackMessageSize(false, new TrackMessageSizeInterceptor(),  "yoCometyoWebSocket");
    }

    @Test
    public void testTrackMessageSizeB64Enabled() throws Exception {
        testTrackMessageSize(true, new TrackMessageSizeB64Interceptor(), "12|eW9Db21ldA==16|eW9XZWJTb2NrZXQ=");
    }

    @Test
    public void testTrackMessageSizeB64Disabled() throws Exception {
        testTrackMessageSize(false, new TrackMessageSizeB64Interceptor(),  "yoCometyoWebSocket");
    }

    private void testTrackMessageSize(boolean enabled, AtmosphereInterceptor icp, String expected) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        framework.interceptor(icp);
        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
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

        Map<String, String> reqheaders = new HashMap<String, String>();
        if (enabled) {
            reqheaders.put(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE, "true");
        }
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().destroyable(false).headers(reqheaders).body("yoComet").pathInfo("/a").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        processor.invokeWebSocketProtocol(w, "yoWebSocket");
        assertEquals(b.toString(), expected);
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
