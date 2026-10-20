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
package org.atmosphere.samples.quarkus.chat;

import org.atmosphere.config.service.ManagedService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.OptionalLong;

import static org.atmosphere.cpr.ApplicationConfig.CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat closes a connection it has heard nothing from for {@code MAX_INACTIVE}
 * ms. A quiet but live browser must not be closed every {@code MAX_INACTIVE} ms:
 * the chat has to ask the client for a heartbeat that arrives well within that
 * window, or every idle Console tab is disconnected and reconnects. The
 * {@code @ManagedService} config is what the framework hands the
 * HeartbeatInterceptor, whose client interval the handshake announces.
 */
class ChatIdleTimeoutHeartbeatTest {

    private static OptionalLong setting(String name) {
        return Arrays.stream(Chat.class.getAnnotation(ManagedService.class).atmosphereConfig())
                .filter(kv -> kv.startsWith(name + "="))
                .mapToLong(kv -> Long.parseLong(kv.substring(kv.indexOf('=') + 1)))
                .findFirst();
    }

    @Test
    void clientHeartbeatKeepsAQuietClientWithinTheIdleTimeout() {
        long maxInactiveMillis = setting(MAX_INACTIVE)
                .orElseThrow(() -> new AssertionError("the chat sets " + MAX_INACTIVE));
        assertTrue(maxInactiveMillis > 0, "the chat evicts idle connections");

        long clientHeartbeatMillis = setting(CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS).orElse(0) * 1000;
        assertTrue(clientHeartbeatMillis > 0,
                "the handshake must ask the client for a heartbeat (0 disables it)");
        assertTrue(clientHeartbeatMillis * 2 <= maxInactiveMillis,
                "at least two client heartbeats (" + clientHeartbeatMillis
                        + " ms) must fit in the " + maxInactiveMillis + " ms idle timeout");
    }
}
