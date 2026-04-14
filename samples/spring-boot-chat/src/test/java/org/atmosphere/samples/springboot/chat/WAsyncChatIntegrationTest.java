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
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test demonstrating the wAsync Java client connecting to a
 * Spring Boot Atmosphere chat server over WebSocket, SSE, and long-polling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WAsyncChatIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AtmosphereFramework framework;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Wait until the broadcaster at {@code path} reports at least {@code minSubscribers}
     * suspended {@code AtmosphereResource}s. Long-polling clients appear in this set only
     * while their current HTTP request is suspended — between polls they vanish. Firing
     * a broadcast before the LP client re-enters suspend mode drops the message on
     * runners where poll-reconnect latency is non-trivial (seen on GitHub Actions).
     */
    private void awaitBroadcasterSubscribers(String path, int minSubscribers, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Optional<Broadcaster> b = framework.getBroadcasterFactory().findBroadcaster(path);
            if (b.isPresent() && b.get().getAtmosphereResources().size() >= minSubscribers) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Broadcaster " + path + " never reached " + minSubscribers
                + " suspended subscribers within " + timeoutMillis + "ms");
    }

    @Test
    void webSocketConnectAndChat() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = client.newRequestBuilder()
                .uri("ws://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, (Function<Object>) o -> openLatch.countDown())
              .on(Event.MESSAGE, (Function<Object>) m -> {
                  var msg = m.toString().strip();
                  if (msg.startsWith("{")) {
                      messages.add(msg);
                      messageLatch.countDown();
                  }
              })
              .open(request);

        assertThat(openLatch.await(10, TimeUnit.SECONDS))
                .as("WebSocket should connect").isTrue();

        socket.fire(mapper.writeValueAsString(new Message("Alice", "Hello from wAsync!")));

        assertThat(messageLatch.await(20, TimeUnit.SECONDS))
                .as("Should receive broadcast").isTrue();

        var received = mapper.readValue(messages.getFirst(), Message.class);
        assertThat(received.getAuthor()).isEqualTo("Alice");
        assertThat(received.getMessage()).isEqualTo("Hello from wAsync!");

        socket.close();
    }

    @Test
    void sseConnectAndReceive() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        // SSE receiver
        var sseRequest = client.newRequestBuilder()
                .uri("http://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.SSE)
                .enableProtocol(false)
                .build();

        var sseSocket = client.create(options);
        sseSocket.on(Event.OPEN, (Function<Object>) o -> openLatch.countDown())
                 .on(Event.MESSAGE, (Function<Object>) m -> {
                     var msg = m.toString().strip();
                     if (msg.startsWith("{")) {
                         messages.add(msg);
                         messageLatch.countDown();
                     }
                 })
                 .open(sseRequest);

        assertThat(openLatch.await(10, TimeUnit.SECONDS))
                .as("SSE should connect").isTrue();

        // Send via WebSocket sender
        var sender = AtmosphereClient.newClient();
        var senderOpen = new CountDownLatch(1);
        var senderRequest = sender.newRequestBuilder()
                .uri("ws://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var senderSocket = sender.create(options);
        senderSocket.on(Event.OPEN, (Function<Object>) o -> senderOpen.countDown())
                    .open(senderRequest);

        assertThat(senderOpen.await(10, TimeUnit.SECONDS))
                .as("Sender should connect").isTrue();

        senderSocket.fire(mapper.writeValueAsString(new Message("Bob", "Hello via SSE!")));

        assertThat(messageLatch.await(20, TimeUnit.SECONDS))
                .as("SSE client should receive broadcast").isTrue();

        var received = mapper.readValue(messages.getFirst(), Message.class);
        assertThat(received.getAuthor()).isEqualTo("Bob");
        assertThat(received.getMessage()).isEqualTo("Hello via SSE!");

        senderSocket.close();
        sseSocket.close();
    }

    @Test
    void longPollingConnectAndReceive() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        // Long-polling MUST re-poll: each response ends the HTTP cycle,
        // so reconnect(true) is required to keep polling for new messages.
        var lpOptions = client.newOptionsBuilder()
                .reconnect(true)
                .build();

        var senderOptions = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        // Long-polling receiver
        var lpRequest = client.newRequestBuilder()
                .uri("http://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.LONG_POLLING)
                .enableProtocol(false)
                .build();

        var lpSocket = client.create(lpOptions);
        lpSocket.on(Event.OPEN, (Function<Object>) o -> openLatch.countDown())
                .on(Event.MESSAGE, (Function<Object>) m -> {
                    var msg = m.toString().strip();
                    if (msg.startsWith("{")) {
                        messages.add(msg);
                        messageLatch.countDown();
                    }
                })
                .open(lpRequest);

        assertThat(openLatch.await(10, TimeUnit.SECONDS))
                .as("Long-polling should connect").isTrue();

        // Send via WebSocket sender
        var sender = AtmosphereClient.newClient();
        var senderOpen = new CountDownLatch(1);
        var senderRequest = sender.newRequestBuilder()
                .uri("ws://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var senderSocket = sender.create(senderOptions);
        senderSocket.on(Event.OPEN, (Function<Object>) o -> senderOpen.countDown())
                    .open(senderRequest);

        assertThat(senderOpen.await(10, TimeUnit.SECONDS))
                .as("Sender should connect").isTrue();

        // Long-polling has a race between Event.OPEN (first poll request
        // dispatched locally) and server-side AtmosphereResource registration
        // in the broadcaster. Query the broadcaster directly until BOTH the LP
        // receiver and the WS sender appear in the suspended-resources set.
        // This is more reliable than firing on a retry loop because slow CI
        // runners can leave the LP client between polls for 100s of ms, and
        // broadcasts during that window are dropped with no cache to replay.
        awaitBroadcasterSubscribers("/atmosphere/chat", 2, 15_000);

        senderSocket.fire(mapper.writeValueAsString(new Message("Charlie", "Hello via LP!")));
        assertThat(messageLatch.await(15, TimeUnit.SECONDS))
                .as("Long-polling client should receive broadcast").isTrue();

        var received = mapper.readValue(messages.getFirst(), Message.class);
        assertThat(received.getAuthor()).isEqualTo("Charlie");
        assertThat(received.getMessage()).isEqualTo("Hello via LP!");

        senderSocket.close();
        lpSocket.close();
    }

    @Test
    void multipleClientsReceiveBroadcast() throws Exception {
        var client1Messages = new CopyOnWriteArrayList<String>();
        var client2Messages = new CopyOnWriteArrayList<String>();
        var open1 = new CountDownLatch(1);
        var open2 = new CountDownLatch(1);
        var msg1 = new CountDownLatch(1);
        var msg2 = new CountDownLatch(1);

        var options = AtmosphereClient.newClient().newOptionsBuilder()
                .reconnect(false)
                .build();

        // Client 1: WebSocket
        var c1 = AtmosphereClient.newClient();
        var r1 = c1.newRequestBuilder()
                .uri("ws://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var s1 = c1.create(options);
        s1.on(Event.OPEN, (Function<Object>) o -> open1.countDown())
          .on(Event.MESSAGE, (Function<Object>) m -> {
              var text = m.toString().strip();
              if (text.contains("broadcast-test")) {
                  client1Messages.add(text);
                  msg1.countDown();
              }
          })
          .open(r1);

        // Client 2: SSE
        var c2 = AtmosphereClient.newClient();
        var r2 = c2.newRequestBuilder()
                .uri("http://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.SSE)
                .enableProtocol(false)
                .build();

        var s2 = c2.create(options);
        s2.on(Event.OPEN, (Function<Object>) o -> open2.countDown())
          .on(Event.MESSAGE, (Function<Object>) m -> {
              var text = m.toString().strip();
              if (text.contains("broadcast-test")) {
                  client2Messages.add(text);
                  msg2.countDown();
              }
          })
          .open(r2);

        assertThat(open1.await(10, TimeUnit.SECONDS)).as("Client 1 should connect").isTrue();
        assertThat(open2.await(10, TimeUnit.SECONDS)).as("Client 2 should connect").isTrue();

        // Client 1 sends, both should receive
        s1.fire(mapper.writeValueAsString(new Message("Sender", "broadcast-test")));

        assertThat(msg1.await(10, TimeUnit.SECONDS)).as("Client 1 should receive").isTrue();
        assertThat(msg2.await(10, TimeUnit.SECONDS)).as("Client 2 should receive").isTrue();

        s1.close();
        s2.close();
    }
}
