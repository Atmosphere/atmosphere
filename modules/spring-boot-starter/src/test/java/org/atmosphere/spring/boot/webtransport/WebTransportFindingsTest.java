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
