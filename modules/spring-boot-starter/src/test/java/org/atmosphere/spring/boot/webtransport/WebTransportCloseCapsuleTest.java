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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.WebTransportProcessorFactory;
import org.atmosphere.webtransport.WebTransportProcessor;
import org.atmosphere.webtransport.WebTransportSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The browser ends a WebTransport session with a CLOSE_WEBTRANSPORT_SESSION
 * capsule (RFC 9297 capsule type 0x2843) on the extended-CONNECT stream. The
 * server newline-framed that stream's DATA like application text and flushed
 * the unterminated capsule as a final message on input shutdown, so every tab
 * close reached {@code @Message} / {@code @Prompt} as {@code "hC\u0004..."}
 * (a billed LLM call on AI endpoints), and the CONNECT stream and the bidi
 * stream both closed the session. The byte sequences are the ones the release
 * sweep captured from Chrome.
 */
class WebTransportCloseCapsuleTest {

    /** {@code transport.close({closeCode: 0})}: type 0x2843, length 4, code 0. */
    private static final byte[] CLOSE_NO_REASON = {0x68, 0x43, 0x04, 0x00, 0x00, 0x00, 0x00};

    /** {@code transport.close({closeCode: 0, reason: 'Client disconnect'})}: length 21. */
    private static final byte[] CLOSE_WITH_REASON = concat(
            new byte[] {0x68, 0x43, 0x15, 0x00, 0x00, 0x00, 0x00},
            "Client disconnect".getBytes(StandardCharsets.UTF_8));

    /** DRAIN_WEBTRANSPORT_SESSION (0x78ae as a 4-byte varint), empty value. */
    private static final byte[] DRAIN = {(byte) 0x80, 0x00, 0x78, (byte) 0xae, 0x00};

    /** WT_BIDI_STREAM signal (0x41 as a 2-byte varint) + session id 0. */
    private static final byte[] BIDI_HEADER = {0x40, 0x41, 0x00};

    private AtmosphereFramework framework;
    private WebTransportProcessor processor;
    private EmbeddedChannel quicConnection;
    private EmbeddedChannel connectStream;
    private EmbeddedChannel bidiStream;

    @BeforeEach
    void setUp() {
        framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereConfig()).thenReturn(mock(AtmosphereConfig.class));
        processor = mock(WebTransportProcessor.class);
        WebTransportProcessorFactory.getDefault().processors().put(framework, processor);

        var sessions = new ConcurrentHashMap<Channel, ReactorNettyTransportServer.WebTransportConnectionState>();
        quicConnection = new EmbeddedChannel();
        connectStream = new EmbeddedChannel(quicConnection, DefaultChannelId.newInstance(), true, false,
                new ReactorNettyTransportServer.AtmosphereHttp3Handler(framework, sessions));
        var connect = new DefaultHttp3HeadersFrame();
        connect.headers().method("CONNECT").protocol("webtransport").scheme("https")
                .authority("localhost").path("/atmosphere/chat");
        connectStream.writeInbound(connect);

        bidiStream = new EmbeddedChannel(quicConnection, DefaultChannelId.newInstance(), true, false,
                new ReactorNettyTransportServer.WebTransportBidiStreamHandler(framework, sessions));
        bidiStream.writeInbound(Unpooled.wrappedBuffer(BIDI_HEADER));
        bidiStream.writeInbound(Unpooled.copiedBuffer("hello\n", StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        WebTransportProcessorFactory.getDefault().processors().remove(framework);
        bidiStream.finishAndReleaseAll();
        connectStream.finishAndReleaseAll();
        quicConnection.finishAndReleaseAll();
    }

    static Stream<byte[]> closeCapsules() {
        return Stream.of(CLOSE_NO_REASON, CLOSE_WITH_REASON);
    }

    @ParameterizedTest
    @MethodSource("closeCapsules")
    void closeCapsuleIsNeverDispatchedAndClosesTheSessionOnce(byte[] capsule) {
        connectStream.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(capsule)));
        // What follows a client close: FIN on the CONNECT stream, the bidi stream goes away.
        connectStream.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        bidiStream.close();

        assertEquals(List.of("hello"), dispatchedMessages(),
                "only the framed application message may reach the processor");
        verify(processor, never()).invokeWebTransportProtocol(any(), any(byte[].class), anyInt(), anyInt());
        verify(processor, times(1)).close(any(), anyInt());
    }

    @Test
    void closeCapsuleSplitAcrossDataFramesClosesTheSessionOnArrival() {
        connectStream.writeInbound(new DefaultHttp3DataFrame(
                Unpooled.wrappedBuffer(CLOSE_WITH_REASON, 0, 3)));
        verify(processor, never()).close(any(), anyInt());

        connectStream.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(
                CLOSE_WITH_REASON, 3, CLOSE_WITH_REASON.length - 3)));
        verify(processor, times(1)).close(any(), anyInt());
        assertFalse(bidiStream.isActive(), "a CLOSE capsule ends the session's bidi stream");

        connectStream.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        bidiStream.close();

        assertEquals(List.of("hello"), dispatchedMessages());
        verify(processor, times(1)).close(any(), anyInt());
    }

    @Test
    void drainCapsuleIsNotDataAndDoesNotCloseTheSession() {
        connectStream.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(DRAIN)));

        verify(processor, never()).close(any(), anyInt());
        bidiStream.writeInbound(Unpooled.copiedBuffer("after-drain\n", StandardCharsets.UTF_8));

        connectStream.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        bidiStream.close();

        assertEquals(List.of("hello", "after-drain"), dispatchedMessages());
        verify(processor, times(1)).close(any(), anyInt());
    }

    @Test
    void oversizedCapsuleClosesTheSessionWithoutBufferingIt() {
        // Type 0x2843, declared length 131072 (4-byte varint 0x80020000): over
        // the 65536-byte bound, so the server must not wait to buffer it.
        connectStream.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(
                new byte[] {0x68, 0x43, (byte) 0x80, 0x02, 0x00, 0x00, 0x00})));

        assertEquals(List.of("hello"), dispatchedMessages());
        verify(processor, times(1)).close(any(), anyInt());
    }

    @Test
    void repeatedCloseOfTheSameSessionReachesTheProcessorOnce() {
        // No capsule at all: FIN on the CONNECT stream, then the bidi stream
        // closes. Both paths end the same session.
        connectStream.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        bidiStream.close();

        verify(processor, times(1)).close(any(), anyInt());
        assertEquals(List.of("hello"), dispatchedMessages());
    }

    private List<String> dispatchedMessages() {
        return mockingDetails(processor).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("invokeWebTransportProtocol")
                        && i.getArguments().length == 2
                        && i.getArguments()[0] instanceof WebTransportSession)
                .map(i -> (String) i.getArguments()[1])
                .toList();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }
}
