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
package org.atmosphere.session.frames;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.session.DurableSession;
import org.atmosphere.session.DurableSessionInterceptor;
import org.atmosphere.session.InMemorySessionStore;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Every WebSocket message frame goes through the interceptor chain. The
 * durable session belongs to the connection, so a frame must neither create
 * another session nor register another save-on-disconnect listener on the
 * resource: one WebSocket, one session, one listener.
 */
class DurableSessionWebSocketFramesTest {

    @ManagedService(path = "/durable")
    public static final class ChatService {

        @Message
        public String onMessage(String message) {
            return message;
        }
    }

    /** Counts the sessions created, by token. */
    static final class CountingStore extends InMemorySessionStore {

        final Set<String> tokens = ConcurrentHashMap.newKeySet();

        @Override
        public void save(DurableSession session) {
            tokens.add(session.token());
            super.save(session);
        }
    }

    private AtmosphereFramework framework;
    private CountingStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new CountingStore();
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(ChatService.class);
        framework.addInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false");
        framework.interceptor(new DurableSessionInterceptor(store));
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res)
                    throws IOException, ServletException {
                return suspended(req, res);
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

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    @Test
    void messageFramesKeepTheConnectionsSingleDurableSession() throws Exception {
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        var webSocket = new NoopWebSocket(framework);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo("/durable").build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        var resource = (AtmosphereResourceImpl) webSocket.resource();
        int listenersAfterOpen = resource.atmosphereResourceEventListener().size();
        assertEquals(1, store.tokens.size(), "the WebSocket open creates the connection's session");

        for (int i = 0; i < 3; i++) {
            processor.invokeWebSocketProtocol(webSocket, "message-" + i);
        }

        assertEquals(1, store.tokens.size(), "message frames must not create more durable sessions");
        assertEquals(listenersAfterOpen, resource.atmosphereResourceEventListener().size(),
                "message frames must not register more save-on-disconnect listeners");
    }

    private static final class NoopWebSocket extends WebSocket {

        NoopWebSocket(AtmosphereFramework framework) {
            super(framework.getAtmosphereConfig());
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebSocket write(String s) {
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) {
            return this;
        }

        @Override
        public void close() {
        }
    }
}
