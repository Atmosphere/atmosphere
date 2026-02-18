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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.room.DefaultRoom;
import org.atmosphere.room.RoomManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatRoomsIntegrationTest {

    @Autowired
    private AtmosphereFramework framework;

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private ChatRoomsController controller;

    // --- Framework wiring ---

    @Test
    void frameworkIsRunning() {
        assertThat(framework).isNotNull();
        assertThat(framework.isDestroyed()).isFalse();
    }

    @Test
    void roomManagerBeanIsCreated() {
        assertThat(roomManager).isNotNull();
    }

    // --- Lobby room setup ---

    @Test
    void lobbyRoomIsPreCreated() {
        assertThat(roomManager.exists("lobby")).isTrue();
        assertThat(roomManager.room("lobby").isDestroyed()).isFalse();
    }

    @Test
    void lobbyRoomHasHistoryEnabled() {
        var room = roomManager.room("lobby");
        assertThat(room).isInstanceOf(DefaultRoom.class);
        assertThat(((DefaultRoom) room).historySize()).isEqualTo(50);
    }

    // --- REST controller ---

    @Test
    void controllerIsInjected() {
        assertThat(controller).isNotNull();
    }

    @Test
    void listRoomsReturnsLobby() {
        var rooms = controller.listRooms();
        assertThat(rooms).isNotEmpty();

        var lobby = rooms.stream()
                .filter(r -> "lobby".equals(r.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("lobby not found"));

        assertThat(lobby.get("destroyed")).isEqualTo(false);
        assertThat(lobby.get("members")).isInstanceOf(Number.class);
        assertThat(lobby).containsKey("memberDetails");
    }

    @Test
    void listRoomsReturnsEmptyMembersForLobby() {
        var rooms = controller.listRooms();
        var lobby = rooms.stream()
                .filter(r -> "lobby".equals(r.get("name")))
                .findFirst()
                .orElseThrow();

        // No one has joined via protocol yet, so memberDetails should be empty
        assertThat((java.util.List<?>) lobby.get("memberDetails")).isEmpty();
    }
}
