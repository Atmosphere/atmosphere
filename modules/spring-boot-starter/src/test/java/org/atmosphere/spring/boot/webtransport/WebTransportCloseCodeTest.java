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
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamResetException;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.WebTransportProcessorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The WebTransport sidecar reports how a session ended through the close code
 * it hands the processor, which reads 1000/1001 as the client leaving. A close
 * the peer made on purpose (a CLOSE_WEBTRANSPORT_SESSION capsule, a FIN on the
 * CONNECT stream, atmosphere.js's FIN on its bidi stream) must reach
 * {@code @Disconnect} as closed by the client; a stream or connection that just
 * went away is a dropped client and must stay cancelled (Invariant #5).
 */
class WebTransportCloseCodeTest {

    /** {@code transport.close({closeCode: 0, reason: 'Client disconnect'})}. */
    private static final byte[] CLOSE_CAPSULE = concat(
            new byte[] {0x68, 0x43, 0x15, 0x00, 0x00, 0x00, 0x00},
            "Client disconnect".getBytes(StandardCharsets.UTF_8));

    /** WT_BIDI_STREAM signal (0x41 as a 2-byte varint) + session id 0. */
    private static final byte[] BIDI_HEADER = {0x40, 0x41, 0x00};

    /** WEBTRANSPORT_SESSION_GONE, the reset code of a stream whose session ended. */
    private static final long SESSION_GONE = 0x170d7b68L;

    /** The flags {@code @Disconnect} saw: {closedByClient, cancelled}. */
    static final AtomicReference<boolean[]> SEEN = new AtomicReference<>();

    @ManagedService(path = "/atmosphere/wt-close")
    public static final class ChatService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) {
            SEEN.set(new boolean[] {event.isClosedByClient(), event.isCancelled()});
        }
    }

    private AtmosphereFramework framework;
    private EmbeddedChannel quicConnection;
    private EmbeddedChannel connectStream;
    private EmbeddedChannel bidiStream;

    @BeforeEach
    void setUp() throws Exception {
        SEEN.set(null);
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
        var processor = new DefaultWebTransportProcessor();
        processor.configure(framework.getAtmosphereConfig());
        WebTransportProcessorFactory.getDefault().processors().put(framework, processor);

        var sessions = new ConcurrentHashMap<Channel, ReactorNettyTransportServer.WebTransportConnectionState>();
        quicConnection = new EmbeddedChannel();
        connectStream = new EmbeddedChannel(quicConnection, DefaultChannelId.newInstance(), true, false,
                new ReactorNettyTransportServer.AtmosphereHttp3Handler(framework, sessions));
        var connect = new DefaultHttp3HeadersFrame();
        connect.headers().method("CONNECT").protocol("webtransport").scheme("https")
                .authority("localhost").path("/atmosphere/wt-close");
        connectStream.writeInbound(connect);

        bidiStream = new EmbeddedChannel(quicConnection, DefaultChannelId.newInstance(), true, false,
                new ReactorNettyTransportServer.WebTransportBidiStreamHandler(framework, sessions));
        bidiStream.writeInbound(Unpooled.wrappedBuffer(BIDI_HEADER));
        assertNull(SEEN.get(), "the session must be open");
    }

    @AfterEach
    void tearDown() {
        WebTransportProcessorFactory.getDefault().processors().remove(framework);
        bidiStream.finishAndReleaseAll();
        connectStream.finishAndReleaseAll();
        quicConnection.finishAndReleaseAll();
        framework.destroy();
    }

    private void assertClosedByTheClient(String how) {
        var seen = SEEN.get();
        assertNotNull(seen, "@Disconnect must run after " + how);
        assertTrue(seen[0], how + " is the client leaving");
        assertFalse(seen[1], how + " is not an unexpected disconnect");
    }

    private void assertCancelled(String how) {
        var seen = SEEN.get();
        assertNotNull(seen, "@Disconnect must run after " + how);
        assertFalse(seen[0], how + " is not the client leaving");
        assertTrue(seen[1], how + " is an unexpected disconnect");
    }

    @Test
    void closeCapsuleIsTheClientLeaving() {
        connectStream.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(CLOSE_CAPSULE)));
        assertClosedByTheClient("a CLOSE_WEBTRANSPORT_SESSION capsule with code 0");
    }

    @Test
    void connectStreamFinIsTheClientLeaving() {
        // A FIN without a capsule is a CLOSE with code 0 (draft-ietf-webtrans-http3).
        connectStream.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        assertClosedByTheClient("a FIN on the CONNECT stream");
    }

    @Test
    void bidiStreamFinIsTheClientLeaving() {
        // atmosphere.js disconnect(): writer.close() sends the FIN before the capsule.
        bidiStream.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        assertClosedByTheClient("a FIN on the bidi stream");
        assertFalse(bidiStream.isActive(), "the server closes the stream the peer finished");
    }

    @Test
    void droppedBidiStreamIsCancelled() {
        // No FIN, no capsule: the stream went away (connection lost, idle timeout).
        bidiStream.close();
        assertCancelled("a dropped bidi stream");
    }

    @Test
    void lostConnectStreamIsCancelled() {
        connectStream.close();
        assertCancelled("a CONNECT stream that went away");
    }

    @Test
    void sessionGoneResetLeavesTheVerdictToTheConnectStream() {
        // The peer closed its session: its streams are reset with WEBTRANSPORT_SESSION_GONE
        // and the CONNECT stream carries how the session ended.
        bidiStream.pipeline().fireExceptionCaught(new QuicStreamResetException("session gone", SESSION_GONE));
        assertNull(SEEN.get(), "a session-gone reset does not decide how the session ended");

        connectStream.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(CLOSE_CAPSULE)));
        assertClosedByTheClient("a capsule after the session-gone reset");
    }

    @Test
    void resetStreamIsCancelled() {
        bidiStream.pipeline().fireExceptionCaught(new QuicStreamResetException("reset", 0x10L));
        assertCancelled("a reset bidi stream");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
