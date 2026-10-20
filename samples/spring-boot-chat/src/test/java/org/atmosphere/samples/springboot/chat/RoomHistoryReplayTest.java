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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A room with history replays it to whoever joins, over a real WebSocket: a
 * member joining for the first time (no {@code sinceId}) gets the last
 * messages the room keeps, and a member coming back with a {@code sinceId}
 * cursor gets what came after it. Each once, in order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomHistoryReplayTest {

    private static final Pattern DATA = Pattern.compile("\"type\":\"message\".*\"data\":\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private RoomManager roomManager;

    @Test
    void aNewMemberGetsTheRoomsLastMessagesOnceInOrder() throws Exception {
        String room = "history-new-" + System.nanoTime();
        roomManager.room(room).enableHistory(5);

        var talkerFrames = new CopyOnWriteArrayList<String>();
        var talker = open("0", talkerFrames);
        try {
            joinAndAwait(talker, talkerFrames, room, "talker", null);
            for (int i = 1; i <= 7; i++) {
                talker.sendText(broadcast(room, "m" + i), true);
            }
            await(() -> lastHistoryId(room) == 7, "the room records 7 messages");

            var newcomerFrames = new CopyOnWriteArrayList<String>();
            var newcomer = open("0", newcomerFrames);
            try {
                joinAndAwait(newcomer, newcomerFrames, room, "newcomer", null);
                await(() -> messages(newcomerFrames).size() >= 5,
                        "the newcomer is replayed the last 5 messages: " + newcomerFrames);
                Thread.sleep(300);
                assertThat(messages(newcomerFrames)).containsExactly("m3", "m4", "m5", "m6", "m7");
            } finally {
                newcomer.abort();
            }
        } finally {
            talker.abort();
        }
    }

    @Test
    void aMemberComingBackWithACursorGetsWhatItMissedOnce() throws Exception {
        String room = "history-back-" + System.nanoTime();
        roomManager.room(room).enableHistory(10);

        var talkerFrames = new CopyOnWriteArrayList<String>();
        var talker = open("0", talkerFrames);
        var firstFrames = new CopyOnWriteArrayList<String>();
        var first = open("0", firstFrames);
        try {
            joinAndAwait(talker, talkerFrames, room, "talker", null);
            String uuid = firstFrames.get(0).split("\\|")[0];
            joinAndAwait(first, firstFrames, room, "listener", null);
            talker.sendText(broadcast(room, "seen"), true);
            await(() -> messages(firstFrames).contains("seen"), "the listener receives the first message");

            // The listener leaves; the room keeps what it misses in its cache too.
            first.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
            await(() -> roomManager.room(room).size() == 1, "the server notices the listener left");
            talker.sendText(broadcast(room, "missed-1"), true);
            talker.sendText(broadcast(room, "missed-2"), true);
            await(() -> roomHistory(room) == 3, "the room records the missed messages");

            var backFrames = new CopyOnWriteArrayList<String>();
            var back = open(uuid, backFrames);
            try {
                back.sendText(join(room, "listener", 1L), true);
                await(() -> messages(backFrames).size() >= 2,
                        "the listener is replayed what it missed: " + backFrames);
                Thread.sleep(300);
                assertThat(messages(backFrames)).containsExactly("missed-1", "missed-2");
            } finally {
                back.abort();
            }
        } finally {
            first.abort();
            talker.abort();
        }
    }

    private int roomHistory(String room) {
        return ((org.atmosphere.room.DefaultRoom) roomManager.room(room)).historySince(0).size();
    }

    private long lastHistoryId(String room) {
        var entries = ((org.atmosphere.room.DefaultRoom) roomManager.room(room)).historySince(0);
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).id();
    }

    private void joinAndAwait(WebSocket ws, List<String> frames, String room, String member, Long sinceId)
            throws Exception {
        await(() -> !frames.isEmpty(), member + " handshake");
        ws.sendText(join(room, member, sinceId), true);
        await(() -> frames.stream().anyMatch(f -> f.contains("join_ack")), member + " join_ack");
    }

    private static List<String> messages(List<String> frames) {
        return frames.stream()
                .map(DATA::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .toList();
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
