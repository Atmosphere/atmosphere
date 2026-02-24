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
package org.atmosphere.grpc;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.AtmosphereServiceGrpc;
import org.atmosphere.grpc.proto.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcIntegrationTest {

    private io.grpc.Server server;
    private ManagedChannel clientChannel;
    private GrpcProcessor processor;
    private final CopyOnWriteArrayList<String> handlerMessages = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<GrpcChannel> openedChannels = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<GrpcChannel> closedChannels = new CopyOnWriteArrayList<>();

    @SuppressWarnings("deprecation")
    @BeforeEach
    void setUp() throws Exception {
        var framework = new AtmosphereFramework();
        framework.init();

        var handler = new GrpcHandlerAdapter() {
            @Override
            public void onOpen(GrpcChannel channel) {
                openedChannels.add(channel);
            }

            @Override
            public void onMessage(GrpcChannel channel, String message) {
                handlerMessages.add(message);
            }

            @Override
            public void onClose(GrpcChannel channel) {
                closedChannels.add(channel);
            }
        };

        processor = new GrpcProcessor(framework, handler);
        var service = new AtmosphereGrpcService(processor);

        var serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();

        clientChannel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
    }

    @AfterEach
    void tearDown() {
        clientChannel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void clientConnectsAndReceivesAckOnSubscribe() throws Exception {
        var responses = new CopyOnWriteArrayList<AtmosphereMessage>();
        var latch = new CountDownLatch(1);

        var stub = AtmosphereServiceGrpc.newStub(clientChannel);
        var requestObserver = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                responses.add(message);
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        // Subscribe to a topic
        requestObserver.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.SUBSCRIBE)
                .setTopic("/chat")
                .build());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive ACK");
        assertEquals(1, responses.size());
        assertEquals(MessageType.ACK, responses.get(0).getType());
        assertEquals("/chat", responses.get(0).getTopic());

        requestObserver.onCompleted();
    }

    @Test
    void clientSendsMessageAndHandlerReceivesIt() throws Exception {
        var latch = new CountDownLatch(1);

        var stub = AtmosphereServiceGrpc.newStub(clientChannel);
        var requestObserver = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        // Send a text message
        requestObserver.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setPayload("hello from client")
                .build());

        // Allow processing
        Thread.sleep(200);

        assertFalse(handlerMessages.isEmpty(), "Handler should receive message");
        assertEquals("hello from client", handlerMessages.get(0));

        requestObserver.onCompleted();
    }

    @Test
    void heartbeatReturnsPong() throws Exception {
        var responses = new CopyOnWriteArrayList<AtmosphereMessage>();
        var latch = new CountDownLatch(1);

        var stub = AtmosphereServiceGrpc.newStub(clientChannel);
        var requestObserver = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                if (message.getType() == MessageType.HEARTBEAT) {
                    responses.add(message);
                    latch.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        requestObserver.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.HEARTBEAT)
                .build());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive heartbeat pong");
        assertEquals(MessageType.HEARTBEAT, responses.get(0).getType());

        requestObserver.onCompleted();
    }

    @Test
    void onOpenCalledWhenClientConnects() throws Exception {
        var latch = new CountDownLatch(1);

        var stub = AtmosphereServiceGrpc.newStub(clientChannel);
        var requestObserver = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        // Send heartbeat to trigger some interaction
        requestObserver.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.HEARTBEAT)
                .build());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, openedChannels.size());

        requestObserver.onCompleted();
    }

    @Test
    void onCloseCalledWhenClientCompletes() throws Exception {
        var completedLatch = new CountDownLatch(1);

        var stub = AtmosphereServiceGrpc.newStub(clientChannel);
        var requestObserver = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
                completedLatch.countDown();
            }
        });

        // Complete the client stream
        requestObserver.onCompleted();

        assertTrue(completedLatch.await(5, TimeUnit.SECONDS));
        // Give processor time to handle close
        Thread.sleep(200);

        assertEquals(1, closedChannels.size());
    }

    @Test
    void multipleClientsGetIndependentChannels() throws Exception {
        var latch = new CountDownLatch(2);

        var stub1 = AtmosphereServiceGrpc.newStub(clientChannel);
        var stub2 = AtmosphereServiceGrpc.newStub(clientChannel);

        var observer1 = stub1.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        var observer2 = stub2.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        // Both send heartbeats
        observer1.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.HEARTBEAT)
                .build());
        observer2.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.HEARTBEAT)
                .build());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(2, openedChannels.size());

        // Verify different UUIDs
        var uuid1 = openedChannels.get(0).uuid();
        var uuid2 = openedChannels.get(1).uuid();
        assertFalse(uuid1.equals(uuid2), "Each client should get a unique channel UUID");

        observer1.onCompleted();
        observer2.onCompleted();
    }

    @Test
    void subscribeAndBroadcastMessageDeliveredToSubscriber() throws Exception {
        var responses = new CopyOnWriteArrayList<AtmosphereMessage>();
        var ackLatch = new CountDownLatch(1);
        var broadcastLatch = new CountDownLatch(1);

        var stub = AtmosphereServiceGrpc.newStub(clientChannel);
        var requestObserver = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                responses.add(message);
                if (message.getType() == MessageType.ACK) {
                    ackLatch.countDown();
                } else if (message.getType() == MessageType.MESSAGE
                        && (message.getPayload().contains("broadcast message")
                            || message.getBinaryPayload().toStringUtf8().contains("broadcast message"))) {
                    broadcastLatch.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });

        // Subscribe to /chat
        requestObserver.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.SUBSCRIBE)
                .setTopic("/chat")
                .build());

        assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "Should receive ACK for subscribe");

        // Send a message to /chat topic to broadcast
        requestObserver.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setTopic("/chat")
                .setPayload("broadcast message")
                .build());

        // The broadcaster writes through AtmosphereResponseImpl which sends
        // HTTP headers first, then the actual payload as separate writes.
        assertTrue(broadcastLatch.await(5, TimeUnit.SECONDS),
                "Should receive broadcast message; got: " + responses);

        requestObserver.onCompleted();
    }
}
