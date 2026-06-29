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

import org.atmosphere.room.PresenceEvent;
import org.atmosphere.room.Room;
import org.atmosphere.room.RoomManager;
import org.atmosphere.room.RoomMember;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sample-level delivery test that proves the Atmosphere Rooms <em>presence</em>
 * API (advertised by the Atmosphere&nbsp;4 blog as "a rooms-and-presence API")
 * actually tracks membership end-to-end through the running Spring Boot server.
 *
 * <p>Unlike {@link ChatRoomsIntegrationTest} — which only verifies bean wiring
 * and that the lobby room is pre-created with history — this test drives two
 * <strong>real</strong> WebSocket subscribers (via the wAsync Java client) that
 * speak the on-the-wire Room Protocol the browser uses, then asserts the
 * <strong>observable</strong> membership/presence state:</p>
 *
 * <ul>
 *   <li>An "observer" subscriber joins and is the sole member.</li>
 *   <li>"alice" joins → the observer receives a wire {@code presence/join}
 *       frame for alice, the server-side {@link Room#memberInfo()} and
 *       {@link Room#size()} advance to two, the {@code GET /api/rooms} REST
 *       view lists both members, and a server-side {@link PresenceEvent} of
 *       type {@code JOIN} fires for alice.</li>
 *   <li>alice leaves → the observer receives a wire {@code presence/leave}
 *       frame, membership falls back to just the observer, the REST view no
 *       longer lists alice, and a {@code LEAVE} {@link PresenceEvent} fires.</li>
 * </ul>
 *
 * <p>This is the Java/JVM mirror of the browser-side Playwright coverage in
 * {@code modules/integration-tests/e2e/presence-count.spec.ts} and
 * {@code rooms-api.spec.ts}; it asserts membership <em>state</em>, never mere
 * bean existence.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomPresenceDeliveryTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private ChatRoomsController controller;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void presenceTracksRoomMembershipOverTheWire() throws Exception {
        // A dedicated room keeps this test isolated from the shared "lobby"
        // (so it never perturbs ChatRoomsIntegrationTest's empty-lobby assertion).
        var roomName = "presence-delivery-" + System.nanoTime();
        var observerId = "observer-" + System.nanoTime();
        var aliceId = "alice-" + System.nanoTime();

        // Pre-create the room so a presence listener can observe events on the
        // very same singleton instance the interceptor resolves by name.
        Room room = roomManager.room(roomName);
        List<PresenceEvent> serverEvents = new CopyOnWriteArrayList<>();
        room.onPresence(serverEvents::add);

        assertThat(room.size()).as("room starts empty").isZero();
        assertThat(memberIds(room)).as("no members before any join").isEmpty();

        var observerMessages = new CopyOnWriteArrayList<String>();
        var aliceMessages = new CopyOnWriteArrayList<String>();
        Socket observer = null;
        Socket alice = null;
        try {
            // --- observer joins first and is the only member ---
            observer = openSubscriber("observer", observerMessages);
            observer.fire(joinFrame(roomName, observerId));
            await(() -> hasJoinAck(observerMessages), 15_000, "observer join_ack");
            await(() -> memberIds(room).contains(observerId), 15_000, "observer tracked as member");

            assertThat(room.size()).as("one member after observer joins").isEqualTo(1);
            assertThat(memberIds(room)).containsExactly(observerId);

            // --- alice joins → presence is delivered AND tracked ---
            alice = openSubscriber("alice", aliceMessages);
            alice.fire(joinFrame(roomName, aliceId));

            // 1) Wire delivery: the observer must receive a presence/join for alice.
            await(() -> sawPresence(observerMessages, "join", aliceId), 15_000,
                    "observer receives presence/join for alice");

            // 2) alice's own join_ack must enumerate the current membership (both).
            await(() -> joinAckMembers(aliceMessages).contains(aliceId)
                    && joinAckMembers(aliceMessages).contains(observerId), 15_000,
                    "alice join_ack lists both members");

            // 3) Server-side membership advances to two and the REST view agrees.
            await(() -> memberIds(room).contains(aliceId), 15_000, "alice tracked as member");
            assertThat(room.size()).as("two members after alice joins").isEqualTo(2);
            assertThat(memberIds(room)).containsExactlyInAnyOrder(observerId, aliceId);
            assertThat(restMemberIds(roomName))
                    .as("GET /api/rooms reflects both members")
                    .containsExactlyInAnyOrder(observerId, aliceId);

            // 4) A server-side JOIN PresenceEvent fired carrying alice's identity.
            assertThat(serverEvents).anyMatch(e -> e.type() == PresenceEvent.Type.JOIN
                    && e.memberInfo() != null && aliceId.equals(e.memberInfo().id()));

            // --- alice leaves → presence/leave is delivered AND membership shrinks ---
            alice.fire(leaveFrame(roomName));

            // 5) Wire delivery: the observer must receive a presence/leave for alice.
            await(() -> sawPresence(observerMessages, "leave", aliceId), 15_000,
                    "observer receives presence/leave for alice");

            // 6) Server-side membership falls back to just the observer.
            await(() -> !memberIds(room).contains(aliceId), 15_000, "alice no longer a member");
            assertThat(room.size()).as("back to one member after alice leaves").isEqualTo(1);
            assertThat(memberIds(room)).containsExactly(observerId);
            assertThat(restMemberIds(roomName))
                    .as("GET /api/rooms drops alice after she leaves")
                    .containsExactly(observerId);

            // 7) A server-side LEAVE PresenceEvent fired.
            assertThat(serverEvents).anyMatch(e -> e.type() == PresenceEvent.Type.LEAVE);
        } finally {
            closeQuietly(alice);
            closeQuietly(observer);
            // Disconnecting the observer must also untrack it; verify the
            // presence registry drains to empty on raw socket close, then tidy up.
            await(() -> room.isEmpty(), 15_000, "room drains to empty on disconnect");
            roomManager.destroy(roomName);
        }
    }

    // --- wAsync subscriber helpers ---

    private Socket openSubscriber(String label, List<String> sink) throws Exception {
        var client = AtmosphereClient.newClient();
        var options = client.newOptionsBuilder().reconnect(false).build();
        var request = client.newRequestBuilder()
                .uri("ws://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var openLatch = new CountDownLatch(1);
        var socket = client.create(options);
        socket.on(Event.OPEN, (Function<Object>) o -> openLatch.countDown())
              .on(Event.MESSAGE, (Function<Object>) m -> {
                  var msg = m.toString().strip();
                  if (!msg.isEmpty()) {
                      sink.add(msg);
                  }
              })
              .open(request);

        assertThat(openLatch.await(15, TimeUnit.SECONDS))
                .as(label + " WebSocket connects").isTrue();
        return socket;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (RuntimeException ignored) {
                // best-effort close in teardown
            }
        }
    }

    // --- Room Protocol frame builders ---

    private String joinFrame(String room, String memberId) {
        return mapper.writeValueAsString(Map.of(
                "type", "join",
                "room", room,
                "memberId", memberId,
                "metadata", Map.of("via", "wasync")));
    }

    private String leaveFrame(String room) {
        return mapper.writeValueAsString(Map.of("type", "leave", "room", room));
    }

    // --- membership / presence observation ---

    private static List<String> memberIds(Room room) {
        return room.memberInfo().values().stream().map(RoomMember::id).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> restMemberIds(String roomName) {
        var entry = controller.listRooms().stream()
                .filter(m -> roomName.equals(m.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("room not exposed by /api/rooms: " + roomName));
        var details = (List<Map<String, Object>>) entry.get("memberDetails");
        return details.stream().map(d -> (String) d.get("id")).toList();
    }

    private boolean hasJoinAck(List<String> messages) {
        return messages.stream().anyMatch(raw -> "join_ack".equals(typeOf(raw)));
    }

    private List<String> joinAckMembers(List<String> messages) {
        List<String> ids = new ArrayList<>();
        for (var raw : messages) {
            var node = parse(raw);
            if (node != null && "join_ack".equals(text(node, "type"))) {
                var arr = node.get("members");
                if (arr != null && arr.isArray()) {
                    ids.clear();
                    for (JsonNode member : arr) {
                        var id = text(member, "id");
                        if (id != null) {
                            ids.add(id);
                        }
                    }
                }
            }
        }
        return ids;
    }

    private boolean sawPresence(List<String> messages, String action, String memberId) {
        for (var raw : messages) {
            var node = parse(raw);
            if (node != null
                    && "presence".equals(text(node, "type"))
                    && action.equals(text(node, "action"))
                    && memberId.equals(text(node, "memberId"))) {
                return true;
            }
        }
        return false;
    }

    private String typeOf(String raw) {
        var node = parse(raw);
        return node == null ? null : text(node, "type");
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return (value != null && value.isString()) ? value.stringValue() : null;
    }

    // --- polling ---

    private static void await(BooleanSupplier condition, long timeoutMillis, String description)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out after " + timeoutMillis + "ms waiting for: " + description);
    }
}
