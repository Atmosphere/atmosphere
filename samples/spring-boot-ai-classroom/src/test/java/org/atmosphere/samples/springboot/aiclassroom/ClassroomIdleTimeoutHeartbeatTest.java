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
package org.atmosphere.samples.springboot.aiclassroom;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * A member that goes silent without closing (a laptop lid, a network that
 * drops packets: the server keeps writing into a half-open socket) must leave
 * its room. The classroom closes a connection it has heard nothing from for
 * {@code MAX_INACTIVE} ms, and its handshake asks each client for a heartbeat
 * well within that window, so a quiet but live student is never closed.
 *
 * <p>The re-test of 2026-10-20 kept a WebSocket half-open with DevTools
 * Offline: with no client heartbeat and no idle timeout, the server wrote into
 * it for minutes and the other page kept counting the member.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false", "atmosphere.web-transport.enabled=false" })
class ClassroomIdleTimeoutHeartbeatTest {

    @Autowired
    private AtmosphereFramework framework;

    @LocalServerPort
    private int port;

    @Test
    void theHandshakeAsksForAHeartbeatWellWithinTheIdleTimeout() {
        var maxInactive = framework.getAtmosphereConfig().getInitParameter(MAX_INACTIVE);
        assertThat(maxInactive).as("the classroom evicts silent members").isNotNull();
        long maxInactiveMillis = Long.parseLong(maxInactive);
        assertThat(maxInactiveMillis).isPositive();

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
    void aSilentMemberIsEvictedAndLeavesTheRoom() throws Exception {
        var classmateFrames = new CopyOnWriteArrayList<String>();
        var silent = UUID.randomUUID().toString();
        var classmate = connect(UUID.randomUUID().toString(), classmateFrames);
        var member = connect(silent, new CopyOnWriteArrayList<>());
        try {
            var resource = (AtmosphereResourceImpl) awaitResource(silent);
            awaitFrame(classmateFrames, "\"action\":\"join\"", 2);

            // Nothing heard from the member for longer than the idle timeout.
            long maxInactiveMillis = Long.parseLong(framework.getAtmosphereConfig().getInitParameter(MAX_INACTIVE));
            resource.getRequest(false).setAttribute(MAX_INACTIVE,
                    System.currentTimeMillis() - maxInactiveMillis - 10_000);

            awaitFrame(classmateFrames, "\"action\":\"leave\"", 1);
            assertThat(classmateFrames.stream().filter(f -> f.contains("\"action\":\"leave\"")))
                    .as("the room is told the silent member left, once")
                    .hasSize(1);
        } finally {
            member.abort();
            classmate.abort();
        }
    }

    private WebSocket connect(String uuid, CopyOnWriteArrayList<String> frames) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/atmosphere/classroom/math"
                                + "?X-Atmosphere-Transport=websocket&X-Atmosphere-tracking-id=" + uuid
                                + "&X-Atmosphere-Framework=5.0.0&X-atmo-protocol=true"),
                        new WebSocket.Listener() {
                            private final StringBuilder buffer = new StringBuilder();

                            @Override
                            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                buffer.append(data);
                                if (last) {
                                    frames.add(buffer.toString());
                                    buffer.setLength(0);
                                }
                                ws.request(1);
                                return CompletableFuture.completedFuture(null);
                            }
                        })
                .get(10, TimeUnit.SECONDS);
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

    private static void awaitFrame(CopyOnWriteArrayList<String> frames, String marker, int count)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (frames.stream().filter(f -> f.contains(marker)).count() >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no " + marker + " frame (" + count + ") in " + frames);
    }
}
