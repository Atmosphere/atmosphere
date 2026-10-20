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

import org.atmosphere.room.DefaultRoom;
import org.atmosphere.room.RoomManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lobby records history from the first message a client can send: it is
 * set up before the server takes connections. Set up on ApplicationReadyEvent,
 * it came seconds after the port opened, the first messages were not recorded,
 * and a member re-joining with a sinceId cursor was replayed nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LobbyReadyBeforeTrafficTest {

    /** The lobby's history size when the web server started taking connections. */
    static final AtomicReference<Integer> historyWhenServing = new AtomicReference<>();

    @TestConfiguration
    static class Probe {
        @Bean
        ApplicationListener<WebServerInitializedEvent> lobbyProbe(RoomManager roomManager) {
            return event -> historyWhenServing.set(roomManager.exists("lobby")
                    && roomManager.room("lobby") instanceof DefaultRoom lobby ? lobby.historySize() : 0);
        }
    }

    @Autowired
    private RoomManager roomManager;

    @Test
    void theLobbyKeepsHistoryBeforeTheServerTakesConnections() {
        assertThat(historyWhenServing.get())
                .as("lobby history size when the server started serving")
                .isEqualTo(50);
        assertThat(roomManager.room("lobby")).isInstanceOf(DefaultRoom.class);
    }
}
