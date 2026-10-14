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
package org.atmosphere.container;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A browser leaving the page closes with going-away (1001) and no reason. The
 * servlet containers close with going-away too when they end a connection
 * themselves, and say why: Jetty 12 on an idle timeout ("Connection Idle
 * Timeout", a client whose network vanished) and on a graceful stop ("Container
 * being shut down"), Tomcat when the web application stops. Those are not the
 * client leaving and must reach {@code @Disconnect} as cancelled (Invariant #5).
 */
class JSR356EndpointGoingAwayTest {

    /** The flags {@code @Disconnect} saw: {closedByClient, cancelled}. */
    static final AtomicReference<boolean[]> SEEN = new AtomicReference<>();

    @ManagedService(path = "/going-away")
    public static final class RoomService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) {
            SEEN.set(new boolean[] {event.isClosedByClient(), event.isCancelled()});
        }
    }

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() throws Exception {
        SEEN.set(null);
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(RoomService.class);
        framework.addInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false");
        framework.addInitParameter(ApplicationConfig.JSR356_MAPPING_PATH, "");
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res)
                    throws IOException, ServletException {
                return suspended(req, res);
            }

            @Override
            public boolean supportWebSocket() {
                return true;
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

    /** Opens a connection through the container endpoint, then lets the container report its close. */
    private boolean[] openThenClose(CloseReason reason) {
        var endpoint = new JSR356Endpoint(framework,
                WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework));
        var handshake = mock(HandshakeRequest.class);
        when(handshake.getHeaders()).thenReturn(new HashMap<>());
        endpoint.handshakeRequest(handshake);

        var session = mock(Session.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getRequestURI()).thenReturn(URI.create("/going-away"));
        when(session.getAsyncRemote()).thenReturn(mock(RemoteEndpoint.Async.class));
        var config = mock(EndpointConfig.class);
        when(config.getUserProperties()).thenReturn(Map.of());
        endpoint.onOpen(session, config);
        assertEquals(1, framework.atmosphereFactory().findAll().size(), "the connection must be open");

        endpoint.onClose(session, reason);
        var seen = SEEN.get();
        assertNotNull(seen, "@Disconnect must run for " + reason);
        return seen;
    }

    @ParameterizedTest
    @ValueSource(strings = {"Connection Idle Timeout", "Container being shut down",
            "The web application is stopping"})
    void containerGoingAwayIsCancelled(String containerReason) {
        var seen = openThenClose(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, containerReason));
        assertFalse(seen[0], "the container ended the connection (" + containerReason + "): the client did not leave");
        assertTrue(seen[1], "a connection the container ended is an unexpected disconnect");
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 1001})
    void peerCloseWithoutReasonIsTheClientLeaving(int code) {
        var seen = openThenClose(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), ""));
        assertTrue(seen[0], "a " + code + " close from the browser is the client leaving");
        assertFalse(seen[1], "a " + code + " close from the browser is not an unexpected disconnect");
    }
}
