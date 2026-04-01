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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the WebTransport findings fixes: UTF-8 boundary detection
 * and query string parsing.
 */
class WebTransportFindingsTest {

    // ── findUtf8Boundary tests ──────────────────────────────────────────

    @Test
    void findUtf8Boundary_emptyArray() {
        assertEquals(0, findUtf8Boundary(new byte[0]));
    }

    @Test
    void findUtf8Boundary_asciiOnly() {
        assertEquals(5, findUtf8Boundary("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void findUtf8Boundary_complete2ByteChar() {
        // "é" = 0xC3 0xA9 (2-byte UTF-8)
        byte[] data = "café".getBytes(StandardCharsets.UTF_8);
        assertEquals(data.length, findUtf8Boundary(data));
    }

    @Test
    void findUtf8Boundary_incomplete2ByteChar() {
        // Simulate a split: "caf" + first byte of "é" (0xC3)
        byte[] full = "café".getBytes(StandardCharsets.UTF_8);
        // "caf" = 3 bytes, "é" = 0xC3 0xA9, so full[3] = 0xC3
        byte[] truncated = new byte[4]; // "caf" + 0xC3 (missing 0xA9)
        System.arraycopy(full, 0, truncated, 0, 4);
        // Should return 3 — everything before the incomplete "é"
        assertEquals(3, findUtf8Boundary(truncated));
    }

    @Test
    void findUtf8Boundary_complete3ByteChar() {
        // "€" = 0xE2 0x82 0xAC (3-byte UTF-8)
        byte[] data = "€".getBytes(StandardCharsets.UTF_8);
        assertEquals(3, findUtf8Boundary(data));
    }

    @Test
    void findUtf8Boundary_incomplete3ByteChar_1of3() {
        // Just the lead byte of a 3-byte sequence
        byte[] data = {(byte) 0xE2};
        assertEquals(0, findUtf8Boundary(data));
    }

    @Test
    void findUtf8Boundary_incomplete3ByteChar_2of3() {
        // Lead + 1 continuation of a 3-byte sequence
        byte[] data = {(byte) 0xE2, (byte) 0x82};
        assertEquals(0, findUtf8Boundary(data));
    }

    @Test
    void findUtf8Boundary_complete4ByteEmoji() {
        // "😀" = 0xF0 0x9F 0x98 0x80 (4-byte UTF-8)
        byte[] data = "😀".getBytes(StandardCharsets.UTF_8);
        assertEquals(4, findUtf8Boundary(data));
    }

    @Test
    void findUtf8Boundary_incomplete4ByteEmoji() {
        // "hi" + first 2 bytes of emoji
        byte[] data = {'h', 'i', (byte) 0xF0, (byte) 0x9F};
        // Should return 2 — everything before the incomplete emoji
        assertEquals(2, findUtf8Boundary(data));
    }

    @Test
    void findUtf8Boundary_mixedWithTrailingAscii() {
        // Complete multi-byte followed by ASCII
        byte[] data = "€x".getBytes(StandardCharsets.UTF_8);
        assertEquals(data.length, findUtf8Boundary(data));
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

    private static int findUtf8Boundary(byte[] data) {
        try {
            // Access the package-private static method via reflection
            Method method = Class.forName(
                    "org.atmosphere.spring.boot.webtransport.ReactorNettyTransportServer$WebTransportBidiStreamHandler")
                    .getDeclaredMethod("findUtf8Boundary", byte[].class);
            method.setAccessible(true);
            return (int) method.invoke(null, data);
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
