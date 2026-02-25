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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.grpc.AtmosphereGrpcServer;
import org.atmosphere.grpc.GrpcChannel;
import org.atmosphere.grpc.GrpcHandlerAdapter;
import org.atmosphere.wasync.Client;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for wAsync client's GrpcTransport against an AtmosphereGrpcServer.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GrpcWasyncTransportTest {

    private AtmosphereFramework framework;
    private AtmosphereGrpcServer grpcServer;
    private int port;

    @BeforeAll
    public void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.addInitParameter("org.atmosphere.cpr.Broadcaster.scanClassPath", "false");
        framework.init();

        // Handler that broadcasts incoming messages back to all subscribers on the topic
        var handler = new GrpcHandlerAdapter() {
            @Override
            public void onMessage(GrpcChannel channel, String message) {
                // The GrpcProcessor already handles broadcasting when topic is set,
                // so no additional action needed here.
            }
        };

        grpcServer = AtmosphereGrpcServer.builder()
                .framework(framework)
                .port(0)
                .handler(handler)
                .build();
        grpcServer.start();
        port = grpcServer.port();
    }

    @AfterAll
    public void tearDown() {
        if (grpcServer != null) {
            grpcServer.close();
        }
        if (framework != null) {
            framework.destroy();
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testWasyncGrpcConnect() throws Exception {
        var client = Client.newClient();
        var options = client.newOptionsBuilder()
                .reconnect(false)
                .waitBeforeUnlocking(5000)
                .build();

        var openLatch = new CountDownLatch(1);

        var request = client.newRequestBuilder()
                .uri("http://localhost:" + port + "/connect-test")
                .transport(Request.TRANSPORT.GRPC)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, (org.atmosphere.wasync.Function<String>) s -> openLatch.countDown());
        socket.open(request, 5, TimeUnit.SECONDS);

        try {
            assertTrue(openLatch.await(5, TimeUnit.SECONDS), "OPEN event should fire");
            assertEquals(Socket.STATUS.OPEN, socket.status());
        } finally {
            socket.close();
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testWasyncGrpcSendAndReceive() throws Exception {
        var client = Client.newClient();
        var options = client.newOptionsBuilder()
                .reconnect(false)
                .waitBeforeUnlocking(5000)
                .build();

        var openLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<String>();

        var request = client.newRequestBuilder()
                .uri("http://localhost:" + port + "/send-receive-test")
                .transport(Request.TRANSPORT.GRPC)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, (org.atmosphere.wasync.Function<String>) s -> openLatch.countDown())
              .on(Event.MESSAGE, (org.atmosphere.wasync.Function<String>) msg -> {
                  received.add(msg);
                  messageLatch.countDown();
              });
        socket.open(request, 5, TimeUnit.SECONDS);

        try {
            assertTrue(openLatch.await(5, TimeUnit.SECONDS), "Should connect");

            socket.fire("hello-wasync");

            assertTrue(messageLatch.await(5, TimeUnit.SECONDS),
                    "Should receive message but got: " + received);
            assertTrue(received.stream().anyMatch(m -> m.contains("hello-wasync")),
                    "Should receive 'hello-wasync' but got: " + received);
        } finally {
            socket.close();
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testWasyncGrpcMultipleClients() throws Exception {
        var client = Client.newClient();
        var options = client.newOptionsBuilder()
                .reconnect(false)
                .waitBeforeUnlocking(5000)
                .build();

        var topic = "/multi-client-" + System.nanoTime();

        var openLatch1 = new CountDownLatch(1);
        var openLatch2 = new CountDownLatch(1);
        var msgLatch1 = new CountDownLatch(1);
        var msgLatch2 = new CountDownLatch(1);
        var received1 = new CopyOnWriteArrayList<String>();
        var received2 = new CopyOnWriteArrayList<String>();

        var request1 = client.newRequestBuilder()
                .uri("http://localhost:" + port + topic)
                .transport(Request.TRANSPORT.GRPC)
                .build();
        var request2 = client.newRequestBuilder()
                .uri("http://localhost:" + port + topic)
                .transport(Request.TRANSPORT.GRPC)
                .build();

        var socket1 = client.create(options);
        socket1.on(Event.OPEN, (org.atmosphere.wasync.Function<String>) s -> openLatch1.countDown())
               .on(Event.MESSAGE, (org.atmosphere.wasync.Function<String>) msg -> {
                   received1.add(msg);
                   msgLatch1.countDown();
               });
        socket1.open(request1, 5, TimeUnit.SECONDS);

        var socket2 = client.create(options);
        socket2.on(Event.OPEN, (org.atmosphere.wasync.Function<String>) s -> openLatch2.countDown())
               .on(Event.MESSAGE, (org.atmosphere.wasync.Function<String>) msg -> {
                   received2.add(msg);
                   msgLatch2.countDown();
               });
        socket2.open(request2, 5, TimeUnit.SECONDS);

        try {
            assertTrue(openLatch1.await(5, TimeUnit.SECONDS), "Client 1 should connect");
            assertTrue(openLatch2.await(5, TimeUnit.SECONDS), "Client 2 should connect");

            socket1.fire("multi-test");

            assertTrue(msgLatch1.await(5, TimeUnit.SECONDS),
                    "Client 1 should receive broadcast but got: " + received1);
            assertTrue(msgLatch2.await(5, TimeUnit.SECONDS),
                    "Client 2 should receive broadcast but got: " + received2);
        } finally {
            socket1.close();
            socket2.close();
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testWasyncGrpcDisconnect() throws Exception {
        var client = Client.newClient();
        var options = client.newOptionsBuilder()
                .reconnect(false)
                .waitBeforeUnlocking(5000)
                .build();

        var openLatch = new CountDownLatch(1);
        var closeLatch = new CountDownLatch(1);

        var request = client.newRequestBuilder()
                .uri("http://localhost:" + port + "/disconnect-test")
                .transport(Request.TRANSPORT.GRPC)
                .build();

        var socket = client.create(options);
        socket.on(Event.OPEN, (org.atmosphere.wasync.Function<String>) s -> openLatch.countDown())
              .on(Event.CLOSE, (org.atmosphere.wasync.Function<String>) s -> closeLatch.countDown());
        socket.open(request, 5, TimeUnit.SECONDS);

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "Should connect");

        socket.close();

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "CLOSE event should fire");
        assertEquals(Socket.STATUS.CLOSE, socket.status());
    }
}
