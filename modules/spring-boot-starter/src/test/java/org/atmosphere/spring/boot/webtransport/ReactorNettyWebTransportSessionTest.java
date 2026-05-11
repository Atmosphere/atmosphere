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

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReactorNettyWebTransportSession} — the QUIC stream
 * bridge into Atmosphere's session model.
 *
 * <p>Drives the session through Netty's {@link EmbeddedChannel} so the
 * outbound writes can be inspected as raw {@link ByteBuf} frames. Closes the
 * coverage gap on the WebTransport client-write path: text framing (newline
 * delimiter), binary preservation (no framing), close idempotence (CAS), and
 * post-close write rejection — all directly observable from the channel's
 * outbound queue.</p>
 */
class ReactorNettyWebTransportSessionTest {

    private AtmosphereConfig config;
    private AtmosphereFramework framework;
    private EmbeddedChannel channel;
    private ReactorNettyWebTransportSession session;

    @BeforeEach
    void setUp() {
        framework = new AtmosphereFramework(true, false);
        config = framework.getAtmosphereConfig();
        channel = new EmbeddedChannel();
        session = new ReactorNettyWebTransportSession(config, channel);
    }

    @AfterEach
    void tearDown() {
        try {
            channel.finishAndReleaseAll();
        } catch (Exception ignore) {
            // EmbeddedChannel cleanup best-effort.
        }
        framework.destroy();
    }

    @Test
    void textWriteAppendsNewlineDelimiter() throws IOException {
        session.write("hello world");
        var frame = channel.<ByteBuf>readOutbound();
        assertNotNull(frame, "channel must have an outbound frame after write");
        var bytes = new byte[frame.readableBytes()];
        frame.readBytes(bytes);
        assertEquals("hello world\n", new String(bytes, StandardCharsets.UTF_8),
                "text writes must terminate with \\n so the client can reframe on QUIC stream boundaries");
        frame.release();
    }

    @Test
    void textWriteUtf8MultibytePreserved() throws IOException {
        // QUIC streams are byte-oriented; ensure UTF-8 multibyte chars round-trip
        // without re-encoding artefacts. Closes a class of regression where a
        // codec would naively call .getBytes() on the platform charset.
        session.write("héllo 🌍");
        var frame = channel.<ByteBuf>readOutbound();
        var bytes = new byte[frame.readableBytes()];
        frame.readBytes(bytes);
        assertEquals("héllo 🌍\n", new String(bytes, StandardCharsets.UTF_8));
        frame.release();
    }

    @Test
    void binaryWriteDoesNotAppendNewline() throws IOException {
        var payload = new byte[]{0x01, 0x02, 0x0A, 0x03}; // 0x0A = '\n' embedded
        session.write(payload, 0, payload.length);
        var frame = channel.<ByteBuf>readOutbound();
        assertEquals(4, frame.readableBytes(),
                "binary writes must preserve exact byte count — no framing added");
        var bytes = new byte[frame.readableBytes()];
        frame.readBytes(bytes);
        assertArrayEquals(payload, bytes);
        frame.release();
    }

    @Test
    void binaryWriteHonorsOffsetAndLength() throws IOException {
        var source = new byte[]{0x10, 0x20, 0x30, 0x40, 0x50};
        session.write(source, 1, 3); // expect 0x20, 0x30, 0x40
        var frame = channel.<ByteBuf>readOutbound();
        var bytes = new byte[frame.readableBytes()];
        frame.readBytes(bytes);
        assertArrayEquals(new byte[]{0x20, 0x30, 0x40}, bytes,
                "binary write must respect (offset, length) slice arguments");
        frame.release();
    }

    @Test
    void isOpenReflectsChannelActiveState() {
        assertTrue(session.isOpen(), "session must be open while channel is active");
        channel.close().syncUninterruptibly();
        assertFalse(session.isOpen(), "session must be closed once channel is closed");
    }

    @Test
    void closeIsIdempotent() {
        session.close();
        // Second close MUST NOT throw and MUST NOT re-trigger channel.close().
        assertDoesNotThrow(() -> session.close());
        assertFalse(session.isOpen());
    }

    @Test
    void writeAfterCloseThrowsIoException() {
        session.close();
        var ex = assertThrows(IOException.class, () -> session.write("nope"),
                "writes must fail closed once the session is closed (Invariant #2 terminal-path)");
        assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    void binaryWriteAfterCloseThrowsIoException() {
        session.close();
        assertThrows(IOException.class,
                () -> session.write(new byte[]{1, 2, 3}, 0, 3));
    }

    @Test
    void closeWithErrorCodeAndReasonDelegatesToClose() {
        session.close(42, "test reason");
        assertFalse(session.isOpen());
        // Idempotent still holds for the error variant.
        assertDoesNotThrow(() -> session.close(43, "second"));
    }

    @Test
    void channelAccessorReturnsUnderlyingChannel() {
        assertSame(channel, session.channel(),
                "channel() must surface the exact Netty channel passed to the constructor");
    }
}
