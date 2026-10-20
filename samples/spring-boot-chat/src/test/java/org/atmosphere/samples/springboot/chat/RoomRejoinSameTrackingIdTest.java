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
package org.atmosphere.samples.springboot.chat;

import org.atmosphere.room.RoomManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Console page in room mode reads the protocol handshake, so it reconnects
 * under the tracking id the server gave it. After a network loss the server
 * may still hold the old connection under that id (it has not noticed it is
 * gone) when the page comes back and re-joins with its {@code sinceId}
 * cursor: what it missed must reach the new connection, not the dead one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomRejoinSameTrackingIdTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RoomManager roomManager;

    @Test
    void aRejoinUnderTheSameTrackingIdGetsTheMissedHistoryWhileTheOldConnectionLingers() throws Exception {
        String room = "rejoin-" + System.nanoTime();
        roomManager.room(room).enableHistory(10);

        var talkerFrames = new CopyOnWriteArrayList<String>();
        var talker = open("0", talkerFrames);
        await(() -> !talkerFrames.isEmpty(), "talker handshake");
        talker.sendText(join(room, "talker", null), true);
        await(() -> contains(talkerFrames, "join_ack"), "talker join_ack");

        var firstFrames = new CopyOnWriteArrayList<String>();
        var first = open("0", firstFrames);
        await(() -> !firstFrames.isEmpty(), "listener handshake");
        String uuid = firstFrames.get(0).split("\\|")[0];
        first.sendText(join(room, "listener", null), true);
        await(() -> contains(firstFrames, "join_ack"), "listener join_ack");

        talker.sendText(broadcast(room, "seen"), true);
        await(() -> contains(firstFrames, "\"data\":\"seen\""), "listener receives the first message");

        // The listener's network drops; the server still holds its connection.
        talker.sendText(broadcast(room, "missed"), true);

        var secondFrames = new CopyOnWriteArrayList<String>();
        var second = open(uuid, secondFrames);
        try {
            second.sendText(join(room, "listener", 1L), true);
            // The server notices the old connection is gone only now.
            first.sendClose(WebSocket.NORMAL_CLOSURE, "");
            await(() -> contains(secondFrames, "\"data\":\"missed\""),
                    "the re-joined connection receives the missed message: " + secondFrames);
            assertThat(secondFrames.stream().filter(f -> f.contains("\"data\":\"seen\"")))
                    .as("what the cursor says it had is not replayed").isEmpty();
        } finally {
            first.abort();
            second.abort();
            talker.abort();
        }
    }

    private WebSocket open(String trackingId, List<String> frames) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/atmosphere/chat"
                        + "?X-Atmosphere-tracking-id=" + trackingId
                        + "&X-Atmosphere-Framework=5.0.0&X-Atmosphere-Transport=websocket"
                        + "&Content-Type=application/json&X-atmo-protocol=true"),
                        new WebSocket.Listener() {
                            private final StringBuilder text = new StringBuilder();

                            @Override
                            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                text.append(data);
                                if (last) {
                                    if (!text.toString().isBlank()) {
                                        frames.add(text.toString());
                                    }
                                    text.setLength(0);
                                }
                                ws.request(1);
                                return null;
                            }
                        })
                .get(10, TimeUnit.SECONDS);
    }

    private static String join(String room, String memberId, Long sinceId) {
        return "{\"type\":\"join\",\"room\":\"" + room + "\",\"memberId\":\"" + memberId + "\""
                + (sinceId != null ? ",\"sinceId\":" + sinceId : "") + "}";
    }

    private static String broadcast(String room, String data) {
        return "{\"type\":\"broadcast\",\"room\":\"" + room + "\",\"data\":\"" + data + "\"}";
    }

    private static boolean contains(List<String> frames, String needle) {
        return frames.stream().anyMatch(f -> f.contains(needle));
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }
}
