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
package org.atmosphere.idlecachee2e;

import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On a real container (embedded Tomcat), a WebSocket client that sends nothing
 * is closed by the idle timeout, a message is broadcast while it is away, and it
 * reconnects under its tracking id: the {@code UUIDBroadcasterCache} must replay
 * that message, as it does for a client that closed the connection itself.
 *
 * <p>The client stays connected longer than the cache's client idle time
 * (shortened here from 60 s to 2 s, like the idle timeout from 120 s to 3 s):
 * a client is only refreshed in the cache when it connects, so while it stayed
 * connected the cache had already forgotten it when the server closed it.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = IdleClosedClientGetsMissedBroadcastHttpE2eTest.TestApp.class,
        properties = {
                "atmosphere.packages=org.atmosphere.idlecachee2e",
                "atmosphere.init-params[org.atmosphere.cache.UUIDBroadcasterCache.clientIdleTime]=2",
                "atmosphere.init-params[org.atmosphere.cache.UUIDBroadcasterCache.invalidateCacheInterval]=1"
        })
class IdleClosedClientGetsMissedBroadcastHttpE2eTest {

    static final String PATH = "/atmosphere/idle-chat";

    @ManagedService(path = PATH, atmosphereConfig = MAX_INACTIVE + "=3000")
    public static class IdleChat {

        @Message
        public String onMessage(String message) {
            return message;
        }
    }

    @SpringBootApplication
    static class TestApp {
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AtmosphereFramework framework;

    private record Connection(WebSocket webSocket, List<String> frames, CompletableFuture<Integer> closed) {
    }

    private Connection connect(HttpClient client, String trackingId) throws Exception {
        var frames = new CopyOnWriteArrayList<String>();
        var closed = new CompletableFuture<Integer>();
        var webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + PATH
                        + "?X-Atmosphere-Transport=websocket&X-Atmosphere-tracking-id=" + trackingId),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                frames.add(data.toString());
                                ws.request(1);
                                return null;
                            }

                            @Override
                            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                                closed.complete(statusCode);
                                return null;
                            }
                        })
                .get(10, TimeUnit.SECONDS);
        return new Connection(webSocket, frames, closed);
    }

    private Broadcaster chat() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var found = framework.getBroadcasterFactory().findBroadcaster(PATH);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no broadcaster at " + PATH);
    }

    @Test
    void anIdleClosedClientGetsWhatWasBroadcastWhileItWasAway() throws Exception {
        String trackingId = UUID.randomUUID().toString();
        try (var client = HttpClient.newHttpClient()) {
            var first = connect(client, trackingId);
            // The client sends nothing: the idle timeout closes it.
            int code = first.closed().get(30, TimeUnit.SECONDS);
            var chat = chat();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!chat.getAtmosphereResources().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(chat.getAtmosphereResources().isEmpty(),
                    "the idle-closed client (close code " + code + ") left the chat");

            chat.broadcast("missed-while-idle-closed").get(5, TimeUnit.SECONDS);

            var second = connect(client, trackingId);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (second.frames().stream().noneMatch(f -> f.contains("missed-while-idle-closed"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            second.webSocket().abort();
            assertTrue(second.frames().stream().anyMatch(f -> f.contains("missed-while-idle-closed")),
                    "the reconnected client must get the message broadcast while it was idle-closed; got "
                            + second.frames());
        }
    }
}
