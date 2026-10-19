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

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.cpr.WebTransportProcessorFactory;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * A client connected over WebTransport is reported as WebTransport. The
 * spring-boot-mcp-server sweep had {@code list_users} answer
 * {@code "transport":"WEBSOCKET"} for a Console whose own log said
 * {@code Connecting to WebTransport}: the session is bridged onto the
 * WebSocket processor, which rewrites {@code X-Atmosphere-Transport} to
 * {@code websocket}, and every report read {@code transport().name()}.
 * The bridged resource keeps the WebSocket semantics it runs with; the report
 * reads {@link Utils#transportName(AtmosphereResource)}.
 */
class WebTransportReportedTransportTest {

    /** WT_BIDI_STREAM signal (0x41 as a 2-byte varint) + session id 0. */
    private static final byte[] BIDI_HEADER = {0x40, 0x41, 0x00};

    static final AtomicReference<AtmosphereResource> READY = new AtomicReference<>();

    @ManagedService(path = "/atmosphere/wt-transport")
    public static final class ChatService {

        @Ready
        public void onReady(AtmosphereResource r) {
            READY.set(r);
        }

        @Message
        public String onMessage(String message) {
            return message;
        }
    }

    private final List<String> inspected = new CopyOnWriteArrayList<>();
    private AtmosphereFramework framework;
    private EmbeddedChannel quicConnection;
    private EmbeddedChannel connectStream;
    private EmbeddedChannel bidiStream;

    @BeforeEach
    void setUp() throws Exception {
        READY.set(null);
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
        // Records what a report reads for every resource the framework
        // dispatches: the connection's at open, then one per frame.
        framework.interceptor(new AtmosphereInterceptorAdapter() {
            @Override
            public Action inspect(AtmosphereResource r) {
                inspected.add(Utils.transportName(r));
                return Action.CONTINUE;
            }
        });

        var processor = new DefaultWebTransportProcessor();
        processor.configure(framework.getAtmosphereConfig());
        WebTransportProcessorFactory.getDefault().processors().put(framework, processor);

        var sessions = new ConcurrentHashMap<Channel, ReactorNettyTransportServer.WebTransportConnectionState>();
        quicConnection = new EmbeddedChannel();
        connectStream = new EmbeddedChannel(quicConnection, DefaultChannelId.newInstance(), true, false,
                new ReactorNettyTransportServer.AtmosphereHttp3Handler(framework, sessions));
        var connect = new DefaultHttp3HeadersFrame();
        connect.headers().method("CONNECT").protocol("webtransport").scheme("https")
                .authority("localhost").path("/atmosphere/wt-transport"
                        + "?X-Atmosphere-tracking-id=0&X-Atmosphere-Framework=5.0.0"
                        + "&X-Atmosphere-Transport=webtransport&X-atmo-protocol=true");
        connectStream.writeInbound(connect);

        bidiStream = new EmbeddedChannel(quicConnection, DefaultChannelId.newInstance(), true, false,
                new ReactorNettyTransportServer.WebTransportBidiStreamHandler(framework, sessions));
        bidiStream.writeInbound(Unpooled.wrappedBuffer(BIDI_HEADER));
    }

    @AfterEach
    void tearDown() {
        WebTransportProcessorFactory.getDefault().processors().remove(framework);
        bidiStream.finishAndReleaseAll();
        connectStream.finishAndReleaseAll();
        quicConnection.finishAndReleaseAll();
        framework.destroy();
    }

    @Test
    void webTransportSessionIsReportedAsWebTransport() {
        var resource = READY.get();
        assertNotNull(resource, "@Ready must run on the WebTransport connect");

        assertEquals("WEBTRANSPORT", Utils.transportName(resource),
                "a report must name the transport the client is on");
        assertEquals(AtmosphereResource.TRANSPORT.WEBSOCKET, resource.transport(),
                "the bridged session keeps the WebSocket semantics it runs with");
    }

    @Test
    void framesOfAWebTransportSessionAreReportedAsWebTransport() {
        inspected.clear();
        bidiStream.writeInbound(Unpooled.copiedBuffer("hello\n", StandardCharsets.UTF_8));

        assertFalse(inspected.isEmpty(), "the frame reached the interceptor chain");
        assertEquals(List.of("WEBTRANSPORT"), inspected.stream().distinct().toList(),
                "the resource a frame is dispatched on reports the session's transport");
    }

    @Test
    void webSocketConnectionIsStillReportedAsWebSocket() throws IOException {
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
                .pathInfo("/atmosphere/wt-transport")
                .queryString("X-Atmosphere-tracking-id=0&X-Atmosphere-Framework=5.0.0"
                        + "&X-Atmosphere-Transport=websocket&X-atmo-protocol=true");
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        READY.set(null);
        processor.open(socket, request, AtmosphereResponseImpl.newInstance(config, request, socket));
        var resource = READY.get();
        assertNotNull(resource, "@Ready must run on the WebSocket connect");

        assertEquals("WEBSOCKET", Utils.transportName(resource));
    }
}
