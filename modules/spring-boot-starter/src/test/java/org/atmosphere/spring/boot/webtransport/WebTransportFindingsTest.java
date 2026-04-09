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
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for WebTransport helper methods: query string parsing
 * and varint length calculation.
 */
class WebTransportFindingsTest {

    // ── varintLength tests ──────────────────────────────────────────────

    @Test
    void varintLength_1byte() {
        // 0x41 = 0b01_000001 → MSBs 01 → length 2 (wait, 01 means 2-byte varint)
        // Actually: 0x41 has MSBs 01, so 1 << 1 = 2 bytes total
        // But 0x00-0x3F have MSBs 00, so 1 << 0 = 1 byte
        ByteBuf buf = Unpooled.buffer().writeByte(0x25); // MSBs 00 → 1 byte
        assertEquals(1, invokeVarintLength(buf));
        buf.release();
    }

    @Test
    void varintLength_2bytes() {
        ByteBuf buf = Unpooled.buffer().writeByte(0x41); // MSBs 01 → 2 bytes
        assertEquals(2, invokeVarintLength(buf));
        buf.release();
    }

    @Test
    void varintLength_4bytes() {
        ByteBuf buf = Unpooled.buffer().writeByte(0x80); // MSBs 10 → 4 bytes
        assertEquals(4, invokeVarintLength(buf));
        buf.release();
    }

    @Test
    void varintLength_8bytes() {
        ByteBuf buf = Unpooled.buffer().writeByte(0xC0); // MSBs 11 → 8 bytes
        assertEquals(8, invokeVarintLength(buf));
        buf.release();
    }

    // ── applyPathAndQuery tests ─────────────────────────────────────────

    @Test
    void applyPathAndQuery_pathOnly() {
        var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance();
        ReactorNettyTransportServer_applyPathAndQuery(request, "/chat");
        assertEquals("/chat", request.getPathInfo());
        // AtmosphereRequest defaults to "" not null for query string
        assertEquals("", request.getQueryString());
    }

    @Test
    void applyPathAndQuery_withQuery() {
        var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance();
        ReactorNettyTransportServer_applyPathAndQuery(request, "/chat?room=lobby&user=test");
        assertEquals("/chat", request.getPathInfo());
        assertEquals("room=lobby&user=test", request.getQueryString());
    }

    @Test
    void applyPathAndQuery_emptyQuery() {
        var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance();
        ReactorNettyTransportServer_applyPathAndQuery(request, "/chat?");
        assertEquals("/chat", request.getPathInfo());
        assertEquals("", request.getQueryString());
    }

    // ── findNewline tests ────────────────────────────────────────────────

    @Test
    void findNewline_present() {
        ByteBuf buf = Unpooled.copiedBuffer("hello\nworld", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(5, invokeFindNewline(buf));
        buf.release();
    }

    @Test
    void findNewline_absent() {
        ByteBuf buf = Unpooled.copiedBuffer("no newline here", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(-1, invokeFindNewline(buf));
        buf.release();
    }

    @Test
    void findNewline_atStart() {
        ByteBuf buf = Unpooled.copiedBuffer("\nhello", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, invokeFindNewline(buf));
        buf.release();
    }

    // ── UTF-8 multibyte safety test ────────────────────────────────────

    @Test
    void multibyte_utf8_not_corrupted_across_chunks() {
        // The emoji 😀 is 4 bytes: F0 9F 98 80
        // If split across two chunks and decoded per-chunk, it corrupts.
        // The byte accumulator should handle this correctly.
        var emoji = "😀";
        var bytes = emoji.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(4, bytes.length, "Emoji should be 4 UTF-8 bytes");

        // Simulate framed message: emoji + newline
        var framed = (emoji + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Split into two chunks at byte boundary inside the emoji
        var chunk1 = new byte[2]; // first 2 bytes of 4-byte emoji
        var chunk2 = new byte[framed.length - 2]; // remaining bytes + newline
        System.arraycopy(framed, 0, chunk1, 0, 2);
        System.arraycopy(framed, 2, chunk2, 0, chunk2.length);

        // Accumulate in a CompositeByteBuf (same as production code)
        var accumulator = Unpooled.compositeBuffer();
        accumulator.addComponent(true, Unpooled.copiedBuffer(chunk1));
        accumulator.addComponent(true, Unpooled.copiedBuffer(chunk2));

        // Find newline and extract frame
        int nlIdx = invokeFindNewline(accumulator);
        assertEquals(4, nlIdx, "Newline should be at position 4 (after 4-byte emoji)");

        var frameBytes = new byte[nlIdx];
        accumulator.readBytes(frameBytes);
        var decoded = new String(frameBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(emoji, decoded, "Emoji should survive split across chunks");

        accumulator.release();
    }

    // ── AltSvcFilter tests ─────────────────────────────────────────────

    @Test
    void altSvcFilter_emitsHeader_whenServerRunning() throws Exception {
        var filter = new AltSvcFilter(4446, null); // null server = always emit
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        filter.doFilter(request, response, (req, res) -> {});
        assertEquals("h3=\":4446\"; ma=86400", response.getHeader("Alt-Svc"));
    }

    @Test
    void altSvcFilter_skipsHeader_whenServerNotRunning() throws Exception {
        var server = org.mockito.Mockito.mock(ReactorNettyTransportServer.class);
        org.mockito.Mockito.when(server.isRunning()).thenReturn(false);
        var filter = new AltSvcFilter(4446, server);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        filter.doFilter(request, response, (req, res) -> {});
        assertEquals(null, response.getHeader("Alt-Svc"),
                "Alt-Svc should NOT be emitted when server is not running");
    }

    // ── Reflective access to package-private/static methods ─────────────

    private static int invokeVarintLength(ByteBuf buf) {
        try {
            Method method = Class.forName(
                    "org.atmosphere.spring.boot.webtransport.ReactorNettyTransportServer$WebTransportBidiStreamHandler")
                    .getDeclaredMethod("varintLength", ByteBuf.class);
            method.setAccessible(true);
            return (int) method.invoke(null, buf);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int invokeFindNewline(ByteBuf buf) {
        try {
            Method method = Class.forName(
                    "org.atmosphere.spring.boot.webtransport.ReactorNettyTransportServer$WebTransportBidiStreamHandler")
                    .getDeclaredMethod("findNewline", ByteBuf.class);
            method.setAccessible(true);
            return (int) method.invoke(null, buf);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void ReactorNettyTransportServer_applyPathAndQuery(
            org.atmosphere.cpr.AtmosphereRequest request, String connectPath) {
        try {
            Method method = ReactorNettyTransportServer.class
                    .getDeclaredMethod("applyPathAndQuery",
                            org.atmosphere.cpr.AtmosphereRequest.class, String.class);
            method.setAccessible(true);
            method.invoke(null, request, connectPath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
