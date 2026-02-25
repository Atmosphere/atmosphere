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
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.grpc.AtmosphereGrpcServer;
import org.atmosphere.grpc.GrpcChannel;
import org.atmosphere.grpc.GrpcHandler;
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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for raw gRPC transport against an AtmosphereGrpcServer.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GrpcTransportTest {

    private AtmosphereFramework framework;
    private AtmosphereGrpcServer grpcServer;
    private int port;

    // Tracking handler for lifecycle callback tests
    private final CopyOnWriteArrayList<String> handlerEvents = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch onOpenLatch;
    private volatile CountDownLatch onMessageLatch;
    private volatile CountDownLatch onCloseLatch;

    @BeforeAll
    public void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.addInitParameter("org.atmosphere.cpr.Broadcaster.scanClassPath", "false");
        framework.init();

        onOpenLatch = new CountDownLatch(1);
        onMessageLatch = new CountDownLatch(1);
        onCloseLatch = new CountDownLatch(1);

        GrpcHandler trackingHandler = new GrpcHandlerAdapter() {
            @Override
            public void onOpen(GrpcChannel channel) {
                handlerEvents.add("onOpen");
                onOpenLatch.countDown();
            }

            @Override
            public void onMessage(GrpcChannel channel, String message) {
                handlerEvents.add("onMessage:" + message);
                onMessageLatch.countDown();
            }

            @Override
            public void onClose(GrpcChannel channel) {
                handlerEvents.add("onClose");
                onCloseLatch.countDown();
            }
        };

        grpcServer = AtmosphereGrpcServer.builder()
                .framework(framework)
                .port(0)
                .handler(trackingHandler)
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
    public void testGrpcConnectAndSubscribe() throws Exception {
        var channel = createChannel();
        try {
            var ackLatch = new CountDownLatch(1);
            var received = new CopyOnWriteArrayList<AtmosphereMessage>();

            var requestObserver = newStream(channel, received, ackLatch, null);

            requestObserver.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic("/test-subscribe")
                    .build());

            assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "Should receive ACK for subscribe");
            var ack = received.stream()
                    .filter(m -> m.getType() == MessageType.ACK)
                    .findFirst();
            assertTrue(ack.isPresent(), "ACK message should be present");
            assertEquals("/test-subscribe", ack.get().getTopic());

            requestObserver.onCompleted();
        } finally {
            shutdownChannel(channel);
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testGrpcSendAndReceiveBroadcast() throws Exception {
        var channel1 = createChannel();
        var channel2 = createChannel();
        try {
            var ackLatch1 = new CountDownLatch(1);
            var ackLatch2 = new CountDownLatch(1);
            var msgLatch1 = new CountDownLatch(1);
            var msgLatch2 = new CountDownLatch(1);
            var received1 = new CopyOnWriteArrayList<AtmosphereMessage>();
            var received2 = new CopyOnWriteArrayList<AtmosphereMessage>();

            var obs1 = newStream(channel1, received1, ackLatch1, msgLatch1);
            var obs2 = newStream(channel2, received2, ackLatch2, msgLatch2);

            var topic = "/broadcast-" + System.nanoTime();

            obs1.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic(topic)
                    .build());
            obs2.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic(topic)
                    .build());

            assertTrue(ackLatch1.await(5, TimeUnit.SECONDS), "Client 1 should receive ACK");
            assertTrue(ackLatch2.await(5, TimeUnit.SECONDS), "Client 2 should receive ACK");

            // Client 1 sends a message to the topic
            obs1.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.MESSAGE)
                    .setTopic(topic)
                    .setPayload("hello-broadcast")
                    .build());

            assertTrue(msgLatch1.await(5, TimeUnit.SECONDS),
                    "Client 1 should receive broadcast but got: " + received1);
            assertTrue(msgLatch2.await(5, TimeUnit.SECONDS),
                    "Client 2 should receive broadcast but got: " + received2);

            assertTrue(received1.stream().anyMatch(m ->
                            m.getType() == MessageType.MESSAGE && m.getPayload().contains("hello-broadcast")),
                    "Client 1 should get 'hello-broadcast'");
            assertTrue(received2.stream().anyMatch(m ->
                            m.getType() == MessageType.MESSAGE && m.getPayload().contains("hello-broadcast")),
                    "Client 2 should get 'hello-broadcast'");

            obs1.onCompleted();
            obs2.onCompleted();
        } finally {
            shutdownChannel(channel1);
            shutdownChannel(channel2);
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testGrpcHandlerCallbacks() throws Exception {
        // Reset tracking state
        handlerEvents.clear();
        onOpenLatch = new CountDownLatch(1);
        onMessageLatch = new CountDownLatch(1);
        onCloseLatch = new CountDownLatch(1);

        var channel = createChannel();
        try {
            var ackLatch = new CountDownLatch(1);
            var received = new CopyOnWriteArrayList<AtmosphereMessage>();

            var obs = newStream(channel, received, ackLatch, null);

            // onOpen is called when stream is opened
            assertTrue(onOpenLatch.await(5, TimeUnit.SECONDS), "onOpen should be called");

            // Subscribe and send a message to trigger onMessage
            var topic = "/handler-test-" + System.nanoTime();
            obs.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic(topic)
                    .build());
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS));

            obs.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.MESSAGE)
                    .setTopic(topic)
                    .setPayload("callback-test")
                    .build());

            assertTrue(onMessageLatch.await(5, TimeUnit.SECONDS), "onMessage should be called");

            // Close stream to trigger onClose
            obs.onCompleted();
            assertTrue(onCloseLatch.await(5, TimeUnit.SECONDS), "onClose should be called");

            assertTrue(handlerEvents.contains("onOpen"), "Events should contain onOpen");
            assertTrue(handlerEvents.stream().anyMatch(e -> e.startsWith("onMessage:")),
                    "Events should contain onMessage");
            assertTrue(handlerEvents.contains("onClose"), "Events should contain onClose");
        } finally {
            shutdownChannel(channel);
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testGrpcHeartbeat() throws Exception {
        var channel = createChannel();
        try {
            var heartbeatLatch = new CountDownLatch(1);
            var received = new CopyOnWriteArrayList<AtmosphereMessage>();

            var obs = newStream(channel, received, null, null, heartbeatLatch);

            obs.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.HEARTBEAT)
                    .build());

            assertTrue(heartbeatLatch.await(5, TimeUnit.SECONDS), "Should receive HEARTBEAT response");
            assertTrue(received.stream().anyMatch(m -> m.getType() == MessageType.HEARTBEAT),
                    "Response should contain HEARTBEAT message");

            obs.onCompleted();
        } finally {
            shutdownChannel(channel);
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testMultipleTopics() throws Exception {
        var channel1 = createChannel();
        var channel2 = createChannel();
        try {
            var ackLatch1 = new CountDownLatch(1);
            var ackLatch2 = new CountDownLatch(1);
            var msgLatch1 = new CountDownLatch(1);
            var received1 = new CopyOnWriteArrayList<AtmosphereMessage>();
            var received2 = new CopyOnWriteArrayList<AtmosphereMessage>();

            var obs1 = newStream(channel1, received1, ackLatch1, msgLatch1);
            var obs2 = newStream(channel2, received2, ackLatch2, null);

            var topicA = "/topicA-" + System.nanoTime();
            var topicB = "/topicB-" + System.nanoTime();

            obs1.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic(topicA)
                    .build());
            obs2.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic(topicB)
                    .build());

            assertTrue(ackLatch1.await(5, TimeUnit.SECONDS));
            assertTrue(ackLatch2.await(5, TimeUnit.SECONDS));

            // Send message to topicA only
            obs1.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.MESSAGE)
                    .setTopic(topicA)
                    .setPayload("topicA-only")
                    .build());

            assertTrue(msgLatch1.await(5, TimeUnit.SECONDS),
                    "Client 1 on topicA should receive message");

            // Wait briefly and verify client 2 on topicB did NOT receive it
            Thread.sleep(500);
            assertTrue(received2.stream().noneMatch(m ->
                            m.getType() == MessageType.MESSAGE && m.getPayload().contains("topicA-only")),
                    "Client 2 on topicB should NOT receive topicA message, but got: " + received2);

            obs1.onCompleted();
            obs2.onCompleted();
        } finally {
            shutdownChannel(channel1);
            shutdownChannel(channel2);
        }
    }

    private ManagedChannel createChannel() {
        return ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
    }

    private void shutdownChannel(ManagedChannel channel) {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    private StreamObserver<AtmosphereMessage> newStream(
            ManagedChannel channel,
            CopyOnWriteArrayList<AtmosphereMessage> received,
            CountDownLatch ackLatch,
            CountDownLatch msgLatch) {
        return newStream(channel, received, ackLatch, msgLatch, null);
    }

    private StreamObserver<AtmosphereMessage> newStream(
            ManagedChannel channel,
            CopyOnWriteArrayList<AtmosphereMessage> received,
            CountDownLatch ackLatch,
            CountDownLatch msgLatch,
            CountDownLatch heartbeatLatch) {

        var stub = AtmosphereServiceGrpc.newStub(channel);

        return stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                received.add(message);
                switch (message.getType()) {
                    case ACK -> {
                        if (ackLatch != null) ackLatch.countDown();
                    }
                    case MESSAGE -> {
                        if (msgLatch != null) msgLatch.countDown();
                    }
                    case HEARTBEAT -> {
                        if (heartbeatLatch != null) heartbeatLatch.countDown();
                    }
                    default -> { }
                }
            }

            @Override
            public void onError(Throwable t) {
                // test will fail on latch timeout
            }

            @Override
            public void onCompleted() {
                // server closed
            }
        });
    }
}
