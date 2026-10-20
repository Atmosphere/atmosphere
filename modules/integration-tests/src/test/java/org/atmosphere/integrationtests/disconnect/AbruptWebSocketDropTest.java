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
package org.atmosphere.integrationtests.disconnect;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A WebSocket member whose TCP connection is reset, with no close frame, while
 * the room streams to it reaches the application's {@code @Disconnect} exactly
 * once, with its resource, in a real container.
 *
 * <p>The 2026-10-20 classroom re-test dropped a member mid-answer and its
 * {@code @Disconnect} came with no resource ("Ignoring disconnect because
 * resource is null"): the room never counted the member out.</p>
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbruptWebSocketDropTest {

    private static final int DROPS = 40;

    private EmbeddedAtmosphereServer server;

    @BeforeAll
    void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.disconnect");
        server.start();
    }

    @AfterAll
    void tearDown() throws Exception {
        server.close();
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @Test
    void anAbruptDropMidStreamReachesDisconnectOnceWithItsResource() throws Exception {
        AbruptDropHandler.DISCONNECTS.clear();
        var members = new ArrayList<String>();
        for (var i = 0; i < DROPS; i++) {
            var member = UUID.randomUUID().toString();
            members.add(member);
            try (var socket = new Socket("localhost", server.getPort())) {
                socket.setSoTimeout(5_000);
                var in = socket.getInputStream();
                var out = socket.getOutputStream();
                handshake(in, out, member);
                sendText(out, "stream");
                readFramesUntil(in, "frame-", 5 + ThreadLocalRandom.current().nextInt(40));
                // Reset the connection: no close frame, as a network loss.
                socket.setSoLinger(true, 0);
            }
        }

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (AbruptDropHandler.DISCONNECTS.size() < DROPS && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        // Let a duplicate dispatch, if any, land too.
        Thread.sleep(1_000);

        var seen = AbruptDropHandler.DISCONNECTS;
        assertTrue(!seen.contains("null"),
                "a @Disconnect came without its resource: " + seen);
        for (var member : members) {
            assertEquals(1, seen.stream().filter(member::equals).count(),
                    "member " + member + " must reach @Disconnect exactly once: " + seen);
        }
    }

    private static void handshake(InputStream in, OutputStream out, String member) throws IOException {
        var key = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().substring(0, 16)
                .getBytes(StandardCharsets.US_ASCII));
        var request = "GET /abrupt-drop?X-Atmosphere-tracking-id=" + member
                + "&X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0 HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        var head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            var b = in.read();
            if (b < 0) {
                throw new IOException("closed during the handshake: " + head);
            }
            head.append((char) b);
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) {
            throw new IOException("handshake refused: " + head);
        }
    }

    private static void sendText(OutputStream out, String text) throws IOException {
        var payload = text.getBytes(StandardCharsets.UTF_8);
        var mask = new byte[4];
        ThreadLocalRandom.current().nextBytes(mask);
        out.write(0x81);
        out.write(0x80 | payload.length);
        out.write(mask);
        for (var i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i % 4]);
        }
        out.flush();
    }

    /** Reads server frames until {@code count} of them contained {@code marker}. */
    private static void readFramesUntil(InputStream in, String marker, int count) throws IOException {
        var matched = 0;
        while (matched < count) {
            var first = in.read();
            var second = in.read();
            if (first < 0 || second < 0) {
                throw new IOException("closed while streaming");
            }
            long length = second & 0x7f;
            if (length == 126) {
                length = (in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (var i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            var payload = in.readNBytes((int) length);
            if (new String(payload, StandardCharsets.UTF_8).contains(marker)) {
                matched++;
            }
        }
    }
}
