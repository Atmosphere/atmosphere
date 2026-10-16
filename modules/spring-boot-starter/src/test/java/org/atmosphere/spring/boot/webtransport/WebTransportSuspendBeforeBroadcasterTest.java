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
package org.atmosphere.spring.boot.webtransport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.cpr.WebTransportProcessorFactory;
import org.atmosphere.room.RoomManager;
import org.atmosphere.room.RoomProtocolInterceptor;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A WebTransport session joins broadcasters only once it is suspended, as a
 * WebSocket does. The sweep of spring-boot-chat logged
 * {@code DefaultBroadcaster: AtmosphereResource <uuid> is not suspended ...
 * Suspend first} on every WebTransport connect, between {@code @Ready} and the
 * Room Protocol join. The join ran on the resource the WebSocket processor
 * creates for one frame, which has the connection's uuid but is never
 * suspended; WebSocket, which the WebTransport bridge delegates to, did the
 * same.
 */
class WebTransportSuspendBeforeBroadcasterTest {

    /** WT_BIDI_STREAM signal (0x41 as a 2-byte varint) + session id 0. */
    private static final byte[] BIDI_HEADER = {0x40, 0x41, 0x00};

    static final AtomicReference<AtmosphereResource> READY = new AtomicReference<>();

    @ManagedService(path = "/atmosphere/wt-suspend")
    public static final class ChatService {

        @Ready
        public void onReady(AtmosphereResource r) {
            READY.set(r);
        }
    }

    private AtmosphereFramework framework;
    private EmbeddedChannel quicConnection;
    private EmbeddedChannel connectStream;
    private EmbeddedChannel bidiStream;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger broadcasterLogger;

    @BeforeEach
    void setUp() throws Exception {
        READY.set(null);
        broadcasterLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultBroadcaster.class);
        appender = new ListAppender<>();
        appender.start();
        broadcasterLogger.addAppender(appender);

        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(ChatService.class);
        framework.addInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false");
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
        var rooms = new RoomProtocolInterceptor();
        rooms.configure(framework.getAtmosphereConfig());
        framework.interceptor(rooms);

        var processor = new DefaultWebTransportProcessor();
        processor.configure(framework.getAtmosphereConfig());
        WebTransportProcessorFactory.getDefault().processors().put(framework, processor);

        var sessions = new ConcurrentHashMap<Channel, ReactorNettyTransportServer.WebTransportConnectionState>();
        quicConnection = new EmbeddedChannel();
        connectStream = new EmbeddedChannel(quicConnection, DefaultChannelId.newInstance(), true, false,
                new ReactorNettyTransportServer.AtmosphereHttp3Handler(framework, sessions));
        // The query atmosphere.js puts on its WebTransport CONNECT.
        var connect = new DefaultHttp3HeadersFrame();
        connect.headers().method("CONNECT").protocol("webtransport").scheme("https")
                .authority("localhost").path("/atmosphere/wt-suspend"
                        + "?X-Atmosphere-tracking-id=0&X-Atmosphere-Framework=5.0.0"
                        + "&X-Atmosphere-Transport=webtransport&X-atmo-protocol=true");
        connectStream.writeInbound(connect);

        bidiStream = new EmbeddedChannel(quicConnection, DefaultChannelId.newInstance(), true, false,
                new ReactorNettyTransportServer.WebTransportBidiStreamHandler(framework, sessions));
        bidiStream.writeInbound(Unpooled.wrappedBuffer(BIDI_HEADER));
    }

    @AfterEach
    void tearDown() {
        broadcasterLogger.detachAppender(appender);
        WebTransportProcessorFactory.getDefault().processors().remove(framework);
        bidiStream.finishAndReleaseAll();
        connectStream.finishAndReleaseAll();
        quicConnection.finishAndReleaseAll();
        framework.destroy();
    }

    private List<String> notSuspendedWarnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("is not suspended"))
                .toList();
    }

    @Test
    void roomJoinOverWebSocketFindsTheConnectionSuspended() throws IOException {
        // The same join over a plain WebSocket, through the processor the
        // WebTransport bridge delegates to.
        var config = framework.getAtmosphereConfig();
        var socket = new WebSocket(config) {
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
        };
        var request = AtmosphereRequestImpl.newInstance()
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .pathInfo("/atmosphere/wt-suspend")
                .queryString("X-Atmosphere-tracking-id=0&X-Atmosphere-Framework=5.0.0"
                        + "&X-Atmosphere-Transport=websocket&X-atmo-protocol=true");
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        READY.set(null);
        processor.open(socket, request, AtmosphereResponseImpl.newInstance(config, request, socket));
        var resource = READY.get();
        assertNotNull(resource, "@Ready must run on the WebSocket connect");

        processor.invokeWebSocketProtocol(socket,
                "{\"type\":\"join\",\"room\":\"lobby\",\"memberId\":\"bob\"}");

        assertTrue(RoomManager.getOrCreate(framework).room("lobby").contains(resource),
                "the join reached the room");
        assertEquals(List.of(), notSuspendedWarnings(),
                "no broadcaster may receive the WebSocket connection before it is suspended");
    }

    @Test
    void roomJoinOverWebTransportFindsTheSessionSuspended() {
        var resource = READY.get();
        assertNotNull(resource, "@Ready must run on the WebTransport connect");
        assertTrue(resource.isSuspended(), "the session is suspended once @Ready ran");

        bidiStream.writeInbound(Unpooled.copiedBuffer(
                "{\"type\":\"join\",\"room\":\"lobby\",\"memberId\":\"alice\"}\n", StandardCharsets.UTF_8));

        assertTrue(RoomManager.getOrCreate(framework).room("lobby").contains(resource),
                "the join reached the room");
        assertEquals(List.of(), notSuspendedWarnings(),
                "no broadcaster may receive the WebTransport session before it is suspended");
    }
}
