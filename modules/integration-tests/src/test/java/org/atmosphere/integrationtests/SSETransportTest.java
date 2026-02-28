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
package org.atmosphere.integrationtests;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SSE (Server-Sent Events) transport.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SSETransportTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer();
        server.start();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    public void tearDown() throws Exception {
        httpClient.close();
        server.close();
    }

    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testSSESubscribeAndReceive() throws Exception {
        var trackingId = UUID.randomUUID().toString();
        var received = new CopyOnWriteArrayList<String>();
        var messageLatch = new CountDownLatch(1);

        var sseUri = URI.create(server.getBaseUrl() + "/echo"
                + "?X-Atmosphere-tracking-id=" + trackingId
                + "&X-Atmosphere-Transport=sse"
                + "&X-Atmosphere-Framework=5.0.0"
                + "&Content-Type=text/event-stream");

        var request = HttpRequest.newBuilder(sseUri)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        // Subscribe asynchronously — sendAsync returns immediately
        var sseFuture = httpClient.sendAsync(request,
                HttpResponse.BodyHandlers.ofInputStream());

        // Process SSE stream in a daemon virtual thread
        var readerThread = Thread.startVirtualThread(() -> {
            try {
                var response = sseFuture.join();
                try (var is = response.body()) {
                    var buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        var chunk = new String(buf, 0, n).trim();
                        if (!chunk.isBlank()) {
                            received.add(chunk);
                            if (chunk.contains("sse-test-message")) {
                                messageLatch.countDown();
                                return;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Connection closed or timeout — expected
            }
        });

        // Let SSE connection establish
        Thread.sleep(1500);

        // Send a message via WebSocket to trigger broadcast
        var wsTrackingId = UUID.randomUUID().toString();
        var wsUri = URI.create(server.getWebSocketUrl() + "/echo"
                + "?X-Atmosphere-tracking-id=" + wsTrackingId
                + "&X-Atmosphere-Transport=websocket"
                + "&X-Atmosphere-Framework=5.0.0");

        var openLatch = new CountDownLatch(1);
        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(wsUri, new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        openLatch.countDown();
                        WebSocket.Listener.super.onOpen(webSocket);
                    }
                })
                .join();
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        ws.sendText("sse-test-message", true).join();

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS),
                "SSE client should receive broadcast, got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        readerThread.interrupt();
    }
}
