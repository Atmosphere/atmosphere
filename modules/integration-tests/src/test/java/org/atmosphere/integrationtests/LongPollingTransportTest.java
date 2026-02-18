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

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Integration tests for long-polling transport.
 */
@Test(groups = "core")
public class LongPollingTransportTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeClass
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer();
        server.start();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterClass
    public void tearDown() throws Exception {
        httpClient.close();
        server.close();
    }

    @Test(timeOut = 15_000)
    public void testLongPollingSubscribeAndReceive() throws Exception {
        var trackingId = UUID.randomUUID().toString();
        var received = new CopyOnWriteArrayList<String>();
        var messageLatch = new CountDownLatch(1);

        var pollUri = URI.create(server.getBaseUrl() + "/echo"
                + "?X-Atmosphere-tracking-id=" + trackingId
                + "&X-Atmosphere-Transport=long-polling"
                + "&X-Atmosphere-Framework=5.0.0");
        var request = HttpRequest.newBuilder(pollUri)
                .header("Content-Type", "text/plain")
                .GET()
                .build();

        // Start long-poll asynchronously
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    var body = response.body();
                    if (body != null && !body.isBlank()) {
                        received.add(body);
                        if (body.contains("long-poll-test")) {
                            messageLatch.countDown();
                        }
                    }
                });

        // Let the long-poll connection establish
        Thread.sleep(1500);

        // Send a message via WebSocket to trigger broadcast
        var wsUri = URI.create(server.getWebSocketUrl() + "/echo"
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
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

        ws.sendText("long-poll-test", true).join();

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS),
                "Long-polling client should receive broadcast, got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test(timeOut = 15_000)
    public void testLongPollingSendMessage() throws Exception {
        // Connect a WebSocket client to receive broadcasts
        var wsReceived = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var wsLatch = new WebSocketTransportTest.MessageLatch(m -> m.contains("post-message"));

        var wsUri = URI.create(server.getWebSocketUrl() + "/echo"
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                + "&X-Atmosphere-Transport=websocket"
                + "&X-Atmosphere-Framework=5.0.0");

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(wsUri, new WebSocketTransportTest.CollectingListener(wsReceived, openLatch, wsLatch))
                .join();

        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(1000);

        // Send a message via long-polling POST
        var postUri = URI.create(server.getBaseUrl() + "/echo"
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                + "&X-Atmosphere-Transport=long-polling"
                + "&X-Atmosphere-Framework=5.0.0");

        var postRequest = HttpRequest.newBuilder(postUri)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("post-message"))
                .build();

        var response = httpClient.send(postRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(response.statusCode(), 200);

        assertTrue(wsLatch.await(5, TimeUnit.SECONDS),
                "WebSocket client should receive the POST broadcast, got: " + wsReceived);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}
