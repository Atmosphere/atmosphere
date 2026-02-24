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

import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.atmosphere.wasync.impl.AtmosphereRequestBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

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

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void webSocketConnectAndChat() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
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

        assertThat(messageLatch.await(10, TimeUnit.SECONDS))
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
        var sseRequest = ((AtmosphereRequestBuilder) client.newRequestBuilder())
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
        var senderRequest = ((AtmosphereRequestBuilder) sender.newRequestBuilder())
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

        assertThat(messageLatch.await(10, TimeUnit.SECONDS))
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

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        // Long-polling receiver
        var lpRequest = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("http://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.LONG_POLLING)
                .enableProtocol(false)
                .build();

        var lpSocket = client.create(options);
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
        var senderRequest = ((AtmosphereRequestBuilder) sender.newRequestBuilder())
                .uri("ws://localhost:" + port + "/atmosphere/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var senderSocket = sender.create(options);
        senderSocket.on(Event.OPEN, (Function<Object>) o -> senderOpen.countDown())
                    .open(senderRequest);

        assertThat(senderOpen.await(10, TimeUnit.SECONDS))
                .as("Sender should connect").isTrue();

        senderSocket.fire(mapper.writeValueAsString(new Message("Charlie", "Hello via LP!")));

        assertThat(messageLatch.await(10, TimeUnit.SECONDS))
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
        var r1 = ((AtmosphereRequestBuilder) c1.newRequestBuilder())
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
        var r2 = ((AtmosphereRequestBuilder) c2.newRequestBuilder())
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
