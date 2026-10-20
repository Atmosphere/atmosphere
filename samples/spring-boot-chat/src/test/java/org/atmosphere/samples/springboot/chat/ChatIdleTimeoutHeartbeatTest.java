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
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * The chat closes a connection it has heard nothing from for {@code MAX_INACTIVE}
 * ms. A quiet but live browser must not be closed every {@code MAX_INACTIVE} ms:
 * the handshake has to ask the client for a heartbeat that arrives well within
 * that window, or every idle Console tab is disconnected and reconnects.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatIdleTimeoutHeartbeatTest {

    @Autowired
    private AtmosphereFramework framework;

    @LocalServerPort
    private int port;

    @Test
    void clientHeartbeatKeepsAQuietClientWithinTheIdleTimeout() {
        long maxInactiveMillis = Long.parseLong(framework.getAtmosphereConfig().getInitParameter(MAX_INACTIVE));
        assertThat(maxInactiveMillis).as("the chat evicts idle connections").isPositive();

        var heartbeat = framework.findInterceptor(HeartbeatInterceptor.class);
        assertThat(heartbeat).as("the HeartbeatInterceptor announces the client heartbeat").isPresent();
        long clientHeartbeatMillis = heartbeat.get().clientHeartbeatFrequencyInSeconds() * 1000L;

        assertThat(clientHeartbeatMillis)
                .as("the handshake must ask the client for a heartbeat (0 disables it)")
                .isPositive();
        assertThat(clientHeartbeatMillis * 2)
                .as("at least two client heartbeats must fit in the %d ms idle timeout", maxInactiveMillis)
                .isLessThanOrEqualTo(maxInactiveMillis);
    }

    @Test
    void aClientHeartbeatResetsTheIdleClock() throws Exception {
        String uuid = UUID.randomUUID().toString();
        var webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/atmosphere/chat"
                        + "?X-Atmosphere-Transport=websocket&X-Atmosphere-tracking-id=" + uuid),
                        new WebSocket.Listener() { })
                .get(10, TimeUnit.SECONDS);
        try {
            var resource = (AtmosphereResourceImpl) awaitResource(uuid);
            // Nothing came from the client for 100 s: the next sweep would close it.
            long stale = System.currentTimeMillis() - 100_000;
            resource.getRequest(false).setAttribute(MAX_INACTIVE, stale);

            String padding = new String(framework.findInterceptor(HeartbeatInterceptor.class)
                    .orElseThrow().getPaddingBytes());
            webSocket.sendText(padding, true).get(5, TimeUnit.SECONDS);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (idleSince(resource) == stale && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(idleSince(resource))
                    .as("the client heartbeat must reset the idle clock")
                    .isGreaterThan(stale);
        } finally {
            webSocket.abort();
        }
    }

    private Object awaitResource(String uuid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var found = framework.atmosphereFactory().findResource(uuid);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no resource for " + uuid);
    }

    private static long idleSince(AtmosphereResourceImpl resource) {
        return (Long) resource.getRequest(false).getAttribute(MAX_INACTIVE);
    }
}
