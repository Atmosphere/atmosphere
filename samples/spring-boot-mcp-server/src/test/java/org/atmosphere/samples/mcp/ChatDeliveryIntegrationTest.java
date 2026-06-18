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
package org.atmosphere.samples.mcp;

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
 * Regression test for the broken browser chat in {@code spring-boot-mcp-server}.
 *
 * <p>The Connect-Web frontend connects to {@code /atmosphere/chat}, but the
 * {@code @ManagedService} backing the chat was mapped to
 * {@code /atmosphere/ai-chat}. A websocket message therefore arrived at a path
 * with no registered handler, the framework returned 404
 * ("Unable to deliver the websocket messages to installed component"), and the
 * message never broadcast back to subscribers.</p>
 *
 * <p>This test drives a wAsync client against {@code /atmosphere/chat} and
 * asserts the message round-trips through the broadcaster. Against the
 * pre-fix mapping it times out (no handler on that path); against the fixed
 * mapping the broadcast is delivered.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatDeliveryIntegrationTest {

    private static final String CHAT_PATH = "/atmosphere/chat";

    @LocalServerPort
    private int port;

    @Autowired
    private AtmosphereFramework framework;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void chatPathDeliversBroadcastToSubscriber() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder().reconnect(false).build();
        var request = client.newRequestBuilder()
                .uri("ws://localhost:" + port + CHAT_PATH)
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
                .as("WebSocket should connect to " + CHAT_PATH).isTrue();

        // The message must reach the @ManagedService and broadcast back. With
        // the chat mapped to /atmosphere/ai-chat (the bug), this never arrives.
        socket.fire(mapper.writeValueAsString(new Message("Alice", "Hello over /atmosphere/chat")));

        assertThat(messageLatch.await(20, TimeUnit.SECONDS))
                .as("subscriber on " + CHAT_PATH + " should receive the broadcast "
                        + "(regression: chat was mis-mapped to /atmosphere/ai-chat)")
                .isTrue();

        var received = mapper.readValue(messages.getFirst(), Message.class);
        assertThat(received.getAuthor()).isEqualTo("Alice");
        assertThat(received.getMessage()).isEqualTo("Hello over /atmosphere/chat");

        socket.close();
    }

    @Test
    void broadcasterIsRegisteredOnChatPathNotAiChat() {
        // Drive a connection so the broadcaster is created, then assert it
        // lives at /atmosphere/chat — pinning the path the frontend expects.
        var client = AtmosphereClient.newClient();
        try {
            var options = client.newOptionsBuilder().reconnect(false).build();
            var openLatch = new CountDownLatch(1);
            var socket = client.create(options);
            socket.on(Event.OPEN, (Function<Object>) o -> openLatch.countDown())
                  .open(client.newRequestBuilder()
                          .uri("ws://localhost:" + port + CHAT_PATH)
                          .transport(Request.TRANSPORT.WEBSOCKET)
                          .enableProtocol(false)
                          .build());
            assertThat(openLatch.await(10, TimeUnit.SECONDS)).isTrue();

            Optional<Broadcaster> chat =
                    framework.getBroadcasterFactory().findBroadcaster(CHAT_PATH);
            assertThat(chat)
                    .as("@ManagedService chat broadcaster must be registered at " + CHAT_PATH)
                    .isPresent();

            socket.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while connecting", e);
        }
    }
}
