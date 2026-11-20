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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.session.DurableSession;
import org.atmosphere.session.DurableSessionInterceptor;
import org.atmosphere.session.InMemorySessionStore;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A durable session over WebSocket survives a server restart.
 *
 * <ul>
 *   <li>The token reached the client only as an upgrade response header, which a
 *       WebSocket client cannot read, so its reconnect carried no token and got a
 *       new session. A client that asks now gets it in band.</li>
 *   <li>A session still connected when the server stopped was saved by the close
 *       the framework gives every resource, which first removes it from its
 *       broadcasters: the store kept {@code broadcasters=[]}.</li>
 *   <li>A restored session re-joined its broadcasters while the request was
 *       inspected, before the resource was suspended, and every restore logged
 *       DefaultBroadcaster's "is not suspended ... Suspend first".</li>
 * </ul>
 */
class DurableSessionWebSocketRestartTest {

    @ManagedService(path = "/durable")
    public static final class ChatService {

        @Message
        public String onMessage(String message) {
            return message;
        }
    }

    /** Keeps what it saved after the interceptor closes it. */
    static final class RecordingStore extends InMemorySessionStore {

        final Map<String, DurableSession> lastSaved = new ConcurrentHashMap<>();

        @Override
        public void save(DurableSession session) {
            lastSaved.put(session.token(), session);
            super.save(session);
        }
    }

    /** The wire names, spelled out: they are what an atmosphere.js client sends and parses. */
    private static final String INBAND_PARAM = "X-Atmosphere-Session-Token-Inband";
    private static final String TOKEN_FRAME = "X-Atmosphere-Session-Token=";

    private final ListAppender<ILoggingEvent> broadcasterLog = new ListAppender<>();
    private final Logger defaultBroadcasterLogger = (Logger) LoggerFactory.getLogger(DefaultBroadcaster.class);
    private AtmosphereFramework framework;
    private RecordingStore store;

    @BeforeEach
    void setUp() throws Exception {
        broadcasterLog.start();
        defaultBroadcasterLogger.addAppender(broadcasterLog);
        store = new RecordingStore();
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
        defaultBroadcasterLogger.detachAppender(broadcasterLog);
        framework.destroy();
    }

    @Test
    void aWebSocketClientThatAsksGetsTheTokenInBand() throws Exception {
        var webSocket = open(Map.of(INBAND_PARAM, "true"));

        assertEquals(1, store.lastSaved.size(), "the open creates one session");
        var token = store.lastSaved.keySet().iterator().next();
        assertTrue(webSocket.frames.contains(TOKEN_FRAME + token),
                "the token must be written in band: " + webSocket.frames);
    }

    @Test
    void aWebSocketClientThatDoesNotAskGetsNoTokenFrame() throws Exception {
        var webSocket = open(Map.of());

        assertTrue(webSocket.frames.stream()
                        .noneMatch(f -> f.startsWith(TOKEN_FRAME)),
                "an older client would hand the frame to the application: " + webSocket.frames);
    }

    @Test
    void aRestoredSessionRejoinsItsBroadcastersOnceSuspendedWithoutTheSuspendFirstWarning() throws Exception {
        var other = framework.getBroadcasterFactory().lookup("/other", true);
        store.save(DurableSession.create("token-1", "old-resource").withBroadcasters(Set.of("/other")));

        var webSocket = open(Map.of(DurableSessionInterceptor.SESSION_TOKEN_HEADER, "token-1",
                INBAND_PARAM, "true"));
        var resource = (AtmosphereResourceImpl) webSocket.resource();

        assertTrue(other.getAtmosphereResources().contains(resource),
                "the restored session must re-join its broadcaster");
        var warnings = broadcasterLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("Suspend first"))
                .toList();
        assertTrue(warnings.isEmpty(), "the restore must not add an unsuspended resource: " + warnings);
        assertTrue(webSocket.frames.contains(TOKEN_FRAME + "token-1"),
                "a restored session's token is delivered in band too: " + webSocket.frames);
    }

    @Test
    void aSessionStillConnectedWhenTheServerStopsKeepsItsBroadcasters() throws Exception {
        var webSocket = open(Map.of());
        var resource = (AtmosphereResourceImpl) webSocket.resource();
        framework.getBroadcasterFactory().lookup("/other", true).addAtmosphereResource(resource);
        var token = store.lastSaved.keySet().iterator().next();

        framework.destroy();

        var saved = store.lastSaved.get(token);
        assertTrue(saved.broadcasters().contains("/other"),
                "the stop must save the live connection's broadcasters, not the emptied ones: " + saved);
        assertFalse(saved.broadcasters().isEmpty());
    }

    private RecordingWebSocket open(Map<String, String> headers) throws Exception {
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        var webSocket = new RecordingWebSocket(framework);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo("/durable").headers(headers).build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        return webSocket;
    }

    private static final class RecordingWebSocket extends WebSocket {

        final List<String> frames = new CopyOnWriteArrayList<>();

        RecordingWebSocket(AtmosphereFramework framework) {
            super(framework.getAtmosphereConfig());
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebSocket write(String s) {
            frames.add(s);
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) {
            frames.add(new String(b, offset, length, StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public void close() {
        }
    }
}
