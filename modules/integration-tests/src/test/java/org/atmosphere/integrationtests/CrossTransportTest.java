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

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.atmosphere.grpc.AtmosphereGrpcServer;
import org.atmosphere.grpc.GrpcHandlerAdapter;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.AtmosphereServiceGrpc;
import org.atmosphere.grpc.proto.MessageType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-transport integration test: verifies that a gRPC client can push
 * a message and a WebSocket client receives it (and vice versa) through
 * the same Atmosphere Broadcaster.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CrossTransportTest {

    private EmbeddedAtmosphereServer jettyServer;
    private AtmosphereGrpcServer grpcServer;
    private HttpClient httpClient;
    private int grpcPort;

    @BeforeAll
    public void setUp() throws Exception {
        // Start Jetty with WebSocket support (hosts the /echo @ManagedService)
        jettyServer = new EmbeddedAtmosphereServer();
        jettyServer.start();

        // Start gRPC server sharing the SAME AtmosphereFramework
        grpcServer = AtmosphereGrpcServer.builder()
                .framework(jettyServer.getFramework())
                .port(0)
                .handler(new GrpcHandlerAdapter())
                .build();
        grpcServer.start();
        grpcPort = grpcServer.port();

        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    public void tearDown() throws Exception {
        httpClient.close();
        if (grpcServer != null) grpcServer.close();
        if (jettyServer != null) jettyServer.close();
    }

    /**
     * gRPC client pushes a message → WebSocket client on the same broadcaster receives it.
     */
    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testGrpcPushToWebSocketClient() throws Exception {
        // 1. Connect a WebSocket client to /echo
        var wsReceived = new CopyOnWriteArrayList<String>();
        var wsOpenLatch = new CountDownLatch(1);
        var wsMessageLatch = new WebSocketTransportTest.MessageLatch(
                m -> m.contains("hello-from-grpc"));

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/echo"),
                        new WebSocketTransportTest.CollectingListener(wsReceived, wsOpenLatch, wsMessageLatch))
                .join();
        assertTrue(wsOpenLatch.await(5, TimeUnit.SECONDS), "WebSocket should connect");

        // Let the WebSocket handshake settle
        Thread.sleep(1000);

        // 2. Connect a gRPC client and subscribe to the same topic /echo
        var grpcChannel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        try {
            var ackLatch = new CountDownLatch(1);
            var grpcReceived = new CopyOnWriteArrayList<AtmosphereMessage>();

            var grpcStream = AtmosphereServiceGrpc.newStub(grpcChannel)
                    .stream(new StreamObserver<>() {
                        @Override
                        public void onNext(AtmosphereMessage msg) {
                            grpcReceived.add(msg);
                            if (msg.getType() == MessageType.ACK) ackLatch.countDown();
                        }

                        @Override
                        public void onError(Throwable t) { }

                        @Override
                        public void onCompleted() { }
                    });

            // Subscribe to the same broadcaster path
            grpcStream.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic("/echo")
                    .build());
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "gRPC should receive SUBSCRIBE ACK");

            // 3. gRPC client sends a message
            grpcStream.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.MESSAGE)
                    .setTopic("/echo")
                    .setPayload("hello-from-grpc")
                    .build());

            // 4. WebSocket client should receive the broadcast
            assertTrue(wsMessageLatch.await(5, TimeUnit.SECONDS),
                    "WebSocket client should receive 'hello-from-grpc' but got: " + wsReceived);

            grpcStream.onCompleted();
        } finally {
            grpcChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    /**
     * WebSocket client sends a message → gRPC client on the same broadcaster receives it.
     */
    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testWebSocketPushToGrpcClient() throws Exception {
        // 1. Connect gRPC client and subscribe to /echo
        var grpcChannel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        try {
            var ackLatch = new CountDownLatch(1);
            var grpcMsgLatch = new CountDownLatch(1);
            var grpcReceived = new CopyOnWriteArrayList<AtmosphereMessage>();

            var grpcStream = AtmosphereServiceGrpc.newStub(grpcChannel)
                    .stream(new StreamObserver<>() {
                        @Override
                        public void onNext(AtmosphereMessage msg) {
                            grpcReceived.add(msg);
                            if (msg.getType() == MessageType.ACK) ackLatch.countDown();
                            if (msg.getType() == MessageType.MESSAGE
                                    && msg.getPayload().contains("hello-from-websocket")) {
                                grpcMsgLatch.countDown();
                            }
                        }

                        @Override
                        public void onError(Throwable t) { }

                        @Override
                        public void onCompleted() { }
                    });

            grpcStream.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic("/echo")
                    .build());
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "gRPC should receive SUBSCRIBE ACK");

            // 2. Connect a WebSocket client to /echo
            var wsReceived = new CopyOnWriteArrayList<String>();
            var wsOpenLatch = new CountDownLatch(1);

            var ws = httpClient.newWebSocketBuilder()
                    .buildAsync(buildWsUri("/echo"),
                            new WebSocketTransportTest.CollectingListener(
                                    wsReceived, wsOpenLatch,
                                    new WebSocketTransportTest.MessageLatch(m -> false)))
                    .join();
            assertTrue(wsOpenLatch.await(5, TimeUnit.SECONDS), "WebSocket should connect");
            Thread.sleep(1000);

            // 3. WebSocket client sends a message
            ws.sendText("hello-from-websocket", true).join();

            // 4. gRPC client should receive it via the broadcaster
            assertTrue(grpcMsgLatch.await(5, TimeUnit.SECONDS),
                    "gRPC client should receive 'hello-from-websocket' but got: " + grpcReceived);

            grpcStream.onCompleted();
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } finally {
            grpcChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Both transports on the same broadcaster: gRPC and WebSocket clients
     * each send a message, and both receive both messages.
     */
    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testBidirectionalCrossTransport() throws Exception {
        // 1. Connect WebSocket client to /echo
        var wsReceived = new CopyOnWriteArrayList<String>();
        var wsOpenLatch = new CountDownLatch(1);
        var wsGrpcMsgLatch = new WebSocketTransportTest.MessageLatch(
                m -> m.contains("from-grpc-bidi"));

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/echo"),
                        new WebSocketTransportTest.CollectingListener(wsReceived, wsOpenLatch, wsGrpcMsgLatch))
                .join();
        assertTrue(wsOpenLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(1000);

        // 2. Connect gRPC client to same /echo topic
        var grpcChannel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        try {
            var ackLatch = new CountDownLatch(1);
            var grpcWsMsgLatch = new CountDownLatch(1);
            var grpcReceived = new CopyOnWriteArrayList<AtmosphereMessage>();

            var grpcStream = AtmosphereServiceGrpc.newStub(grpcChannel)
                    .stream(new StreamObserver<>() {
                        @Override
                        public void onNext(AtmosphereMessage msg) {
                            grpcReceived.add(msg);
                            if (msg.getType() == MessageType.ACK) ackLatch.countDown();
                            if (msg.getType() == MessageType.MESSAGE
                                    && msg.getPayload().contains("from-ws-bidi")) {
                                grpcWsMsgLatch.countDown();
                            }
                        }

                        @Override
                        public void onError(Throwable t) { }

                        @Override
                        public void onCompleted() { }
                    });

            grpcStream.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic("/echo")
                    .build());
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS));

            // 3. gRPC sends → WebSocket receives
            grpcStream.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.MESSAGE)
                    .setTopic("/echo")
                    .setPayload("from-grpc-bidi")
                    .build());
            assertTrue(wsGrpcMsgLatch.await(5, TimeUnit.SECONDS),
                    "WebSocket should receive gRPC message, got: " + wsReceived);

            // 4. WebSocket sends → gRPC receives
            ws.sendText("from-ws-bidi", true).join();
            assertTrue(grpcWsMsgLatch.await(5, TimeUnit.SECONDS),
                    "gRPC should receive WebSocket message, got: " + grpcReceived);

            grpcStream.onCompleted();
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } finally {
            grpcChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private URI buildWsUri(String path) {
        return URI.create(jettyServer.getWebSocketUrl() + path
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                + "&X-Atmosphere-Transport=websocket"
                + "&X-Atmosphere-Framework=5.0.0");
    }
}
