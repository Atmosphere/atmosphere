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
package org.atmosphere.wasync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.atmosphere.wasync.impl.AtmosphereRequestBuilder;
import org.atmosphere.wasync.impl.DefaultOptionsBuilder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for wAsync client against an embedded Jetty
 * Atmosphere server. Similar to the Playwright E2E tests but using the
 * Java wAsync client directly over WebSocket.
 */
@Timeout(30)
class ChatIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ChatIntegrationTest.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static Server server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(0); // random available port
        server.addConnector(connector);

        var context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Configure WebSocket before Atmosphere
        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, serverContainer) ->
                logger.info("WebSocket ServerContainer configured: {}", serverContainer.getClass().getName()));

        var atmosphereServlet = new ServletHolder(AtmosphereServlet.class);
        atmosphereServlet.setInitParameter(ApplicationConfig.ANNOTATION_PACKAGE, "org.atmosphere.wasync");
        atmosphereServlet.setInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE, "application/json");
        atmosphereServlet.setInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT, "true");
        atmosphereServlet.setInitOrder(1);
        atmosphereServlet.setAsyncSupported(true);
        context.addServlet(atmosphereServlet, "/chat/*");

        server.setHandler(context);
        server.start();

        port = connector.getLocalPort();
        logger.info("Test server started on port {}", port);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void clientConnectsViaWebSocket() throws Exception {
        var client = AtmosphereClient.newClient();
        var openLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, o -> openLatch.countDown())
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "Should connect within 10s");
        socket.close();
    }

    @Test
    void clientSendsAndReceivesMessage() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, o -> openLatch.countDown())
              .on(Event.MESSAGE, m -> {
                  var msg = m.toString().strip();
                  if (!msg.isEmpty()) {
                      messages.add(msg);
                      messageLatch.countDown();
                  }
              })
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "Should connect");

        // Send a chat message
        var json = mapper.writeValueAsString(
                new ChatHandler.ChatMessage("Alice", "Hello from wAsync!"));
        socket.fire(json);

        assertTrue(messageLatch.await(10, TimeUnit.SECONDS), "Should receive broadcast");
        assertFalse(messages.isEmpty(), "Should have received at least one message");

        // Verify the message content
        var received = mapper.readValue(messages.getLast(), ChatHandler.ChatMessage.class);
        assertEquals("Alice", received.author());
        assertEquals("Hello from wAsync!", received.message());

        socket.close();
    }

    @Test
    void multipleClientsBroadcast() throws Exception {
        var messagesAlice = new CopyOnWriteArrayList<String>();
        var messagesBob = new CopyOnWriteArrayList<String>();
        var aliceOpen = new CountDownLatch(1);
        var bobOpen = new CountDownLatch(1);
        var aliceReceivedBob = new CountDownLatch(1);
        var bobReceivedAlice = new CountDownLatch(1);

        var client1 = AtmosphereClient.newClient();
        var client2 = AtmosphereClient.newClient();

        var options = client1.newOptionsBuilder().reconnect(false).build();

        var request1 = ((AtmosphereRequestBuilder) client1.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var request2 = ((AtmosphereRequestBuilder) client2.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        // Alice connects
        var alice = client1.create(options);
        alice.on(Event.OPEN, o -> aliceOpen.countDown())
             .on(Event.MESSAGE, m -> {
                 var msg = m.toString().strip();
                 if (!msg.isEmpty() && msg.startsWith("{")) {
                     try {
                         var chat = mapper.readValue(msg, ChatHandler.ChatMessage.class);
                         if ("Bob".equals(chat.author())) {
                             messagesAlice.add(msg);
                             aliceReceivedBob.countDown();
                         }
                     } catch (Exception ignored) { }
                 }
             })
             .open(request1);

        // Bob connects
        var bob = client2.create(options);
        bob.on(Event.OPEN, o -> bobOpen.countDown())
           .on(Event.MESSAGE, m -> {
               var msg = m.toString().strip();
               if (!msg.isEmpty() && msg.startsWith("{")) {
                   try {
                       var chat = mapper.readValue(msg, ChatHandler.ChatMessage.class);
                       if ("Alice".equals(chat.author())) {
                           messagesBob.add(msg);
                           bobReceivedAlice.countDown();
                       }
                   } catch (Exception ignored) { }
               }
           })
           .open(request2);

        assertTrue(aliceOpen.await(10, TimeUnit.SECONDS), "Alice should connect");
        assertTrue(bobOpen.await(10, TimeUnit.SECONDS), "Bob should connect");

        // Allow time for both resources to be fully registered in the Broadcaster
        Thread.sleep(500);

        // Alice sends a message — Bob should receive it (broadcast to all)
        alice.fire(mapper.writeValueAsString(
                new ChatHandler.ChatMessage("Alice", "Can you see this, Bob?")));

        assertTrue(bobReceivedAlice.await(10, TimeUnit.SECONDS),
                "Bob should receive Alice's message");
        var bobMsg = mapper.readValue(messagesBob.getFirst(), ChatHandler.ChatMessage.class);
        assertEquals("Alice", bobMsg.author());
        assertEquals("Can you see this, Bob?", bobMsg.message());

        // Bob replies — Alice should receive it (broadcast to all)
        bob.fire(mapper.writeValueAsString(
                new ChatHandler.ChatMessage("Bob", "Yes I can, Alice!")));

        assertTrue(aliceReceivedBob.await(10, TimeUnit.SECONDS),
                "Alice should receive Bob's message");
        var aliceMsg = mapper.readValue(messagesAlice.getFirst(), ChatHandler.ChatMessage.class);
        assertEquals("Bob", aliceMsg.author());
        assertEquals("Yes I can, Alice!", aliceMsg.message());

        alice.close();
        bob.close();
    }

    @Test
    void clientReceivesMultipleMessages() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageCount = 3;
        var allReceived = new CountDownLatch(messageCount);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, o -> openLatch.countDown())
              .on(Event.MESSAGE, m -> {
                  var msg = m.toString().strip();
                  if (!msg.isEmpty() && msg.contains("message")) {
                      messages.add(msg);
                      allReceived.countDown();
                  }
              })
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "Should connect");

        // Send multiple messages rapidly
        for (int i = 1; i <= messageCount; i++) {
            socket.fire(mapper.writeValueAsString(
                    new ChatHandler.ChatMessage("User", "message " + i)));
        }

        assertTrue(allReceived.await(10, TimeUnit.SECONDS),
                "Should receive all " + messageCount + " messages");
        assertEquals(messageCount, messages.size());

        socket.close();
    }

    @Test
    void socketStatusTransitions() throws Exception {
        var client = AtmosphereClient.newClient();
        var openLatch = new CountDownLatch(1);
        var closeLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        assertEquals(Socket.STATUS.INIT, socket.status());

        socket.on(Event.OPEN, o -> openLatch.countDown())
              .on(Event.CLOSE, c -> closeLatch.countDown())
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "Should connect");
        assertEquals(Socket.STATUS.OPEN, socket.status());

        socket.close();
        assertEquals(Socket.STATUS.CLOSE, socket.status());
    }

    @Test
    void clientWithEncoder() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        Encoder<ChatHandler.ChatMessage, String> jsonEncoder = msg -> {
            try {
                return mapper.writeValueAsString(msg);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .encoder(jsonEncoder)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, o -> openLatch.countDown())
              .on(Event.MESSAGE, m -> {
                  var msg = m.toString().strip();
                  if (msg.contains("encoder")) {
                      messages.add(msg);
                      messageLatch.countDown();
                  }
              })
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "Should connect");

        // Fire a POJO — the encoder should convert it to JSON
        socket.fire(new ChatHandler.ChatMessage("Test", "via encoder"));

        assertTrue(messageLatch.await(10, TimeUnit.SECONDS),
                "Should receive encoded message");
        var received = mapper.readValue(messages.getFirst(), ChatHandler.ChatMessage.class);
        assertEquals("Test", received.author());
        assertEquals("via encoder", received.message());

        socket.close();
    }

    @Test
    void sseTransportConnectsAndReceivesMessage() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("http://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.SSE)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, o -> openLatch.countDown())
              .on(Event.MESSAGE, m -> {
                  var msg = m.toString().strip();
                  if (!msg.isEmpty() && msg.startsWith("{")) {
                      messages.add(msg);
                      messageLatch.countDown();
                  }
              })
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "SSE should connect");

        // Send a message via a separate WebSocket client (SSE is read-only for receive)
        var sender = AtmosphereClient.newClient();
        var senderOpen = new CountDownLatch(1);

        var senderRequest = ((AtmosphereRequestBuilder) sender.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var senderSocket = sender.create(options);
        senderSocket.on(Event.OPEN, o -> senderOpen.countDown())
                    .open(senderRequest);

        assertTrue(senderOpen.await(10, TimeUnit.SECONDS), "Sender should connect");

        senderSocket.fire(mapper.writeValueAsString(
                new ChatHandler.ChatMessage("SSE-Test", "Hello via SSE")));

        assertTrue(messageLatch.await(10, TimeUnit.SECONDS),
                "SSE client should receive broadcast");
        var received = mapper.readValue(messages.getFirst(), ChatHandler.ChatMessage.class);
        assertEquals("SSE-Test", received.author());
        assertEquals("Hello via SSE", received.message());

        senderSocket.close();
        socket.close();
    }

    @Test
    void longPollingTransportConnectsAndReceivesMessage() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("http://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.LONG_POLLING)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, o -> openLatch.countDown())
              .on(Event.MESSAGE, m -> {
                  var msg = m.toString().strip();
                  if (!msg.isEmpty() && msg.startsWith("{")) {
                      messages.add(msg);
                      messageLatch.countDown();
                  }
              })
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "Long-polling should connect");

        // Send a message via a separate WebSocket client
        var sender = AtmosphereClient.newClient();
        var senderOpen = new CountDownLatch(1);

        var senderRequest = ((AtmosphereRequestBuilder) sender.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var senderSocket = sender.create(options);
        senderSocket.on(Event.OPEN, o -> senderOpen.countDown())
                    .open(senderRequest);

        assertTrue(senderOpen.await(10, TimeUnit.SECONDS), "Sender should connect");

        senderSocket.fire(mapper.writeValueAsString(
                new ChatHandler.ChatMessage("LP-Test", "Hello via long-polling")));

        assertTrue(messageLatch.await(10, TimeUnit.SECONDS),
                "Long-polling client should receive broadcast");
        var received = mapper.readValue(messages.getFirst(), ChatHandler.ChatMessage.class);
        assertEquals("LP-Test", received.author());
        assertEquals("Hello via long-polling", received.message());

        senderSocket.close();
        socket.close();
    }

    @Test
    void streamingTransportConnectsAndReceivesMessage() throws Exception {
        var client = AtmosphereClient.newClient();
        var messages = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);

        var options = client.newOptionsBuilder()
                .reconnect(false)
                .build();

        var request = ((AtmosphereRequestBuilder) client.newRequestBuilder())
                .uri("http://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.STREAMING)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, o -> openLatch.countDown())
              .on(Event.MESSAGE, m -> {
                  var msg = m.toString().strip();
                  if (!msg.isEmpty() && msg.startsWith("{")) {
                      messages.add(msg);
                      messageLatch.countDown();
                  }
              })
              .open(request);

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "Streaming should connect");

        // Send a message via a separate WebSocket client
        var sender = AtmosphereClient.newClient();
        var senderOpen = new CountDownLatch(1);

        var senderRequest = ((AtmosphereRequestBuilder) sender.newRequestBuilder())
                .uri("ws://localhost:" + port + "/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var senderSocket = sender.create(options);
        senderSocket.on(Event.OPEN, o -> senderOpen.countDown())
                    .open(senderRequest);

        assertTrue(senderOpen.await(10, TimeUnit.SECONDS), "Sender should connect");

        senderSocket.fire(mapper.writeValueAsString(
                new ChatHandler.ChatMessage("Stream-Test", "Hello via streaming")));

        assertTrue(messageLatch.await(10, TimeUnit.SECONDS),
                "Streaming client should receive broadcast");
        var received = mapper.readValue(messages.getFirst(), ChatHandler.ChatMessage.class);
        assertEquals("Stream-Test", received.author());
        assertEquals("Hello via streaming", received.message());

        senderSocket.close();
        socket.close();
    }
}
