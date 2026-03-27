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
package org.atmosphere.samples.grpc;

import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.AtmosphereServiceGrpc;
import org.atmosphere.grpc.proto.MessageType;

import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

/**
 * Interactive CLI client that connects to the gRPC chat server via native bidirectional streaming.
 *
 * <p>Run with:
 * <pre>
 * mvn exec:java -pl samples/grpc-chat \
 *   -Dexec.mainClass=org.atmosphere.samples.grpc.GrpcChatClient
 * </pre>
 */
public class GrpcChatClient {

    public static void main(String[] args) throws InterruptedException {
        var host = System.getProperty("grpc.host", "localhost");
        var port = Integer.getInteger("grpc.port", 9090);

        var channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();

        var stub = AtmosphereServiceGrpc.newStub(channel);
        var disconnected = new CountDownLatch(1);

        var requestObserver = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                switch (message.getType()) {
                    case ACK -> System.out.println("[ack] Subscribed to " + message.getTopic()
                            + " (tracking_id: " + message.getTrackingId() + ")");
                    case MESSAGE -> System.out.println("[msg] " + message.getPayload());
                    case HEARTBEAT -> { /* silent */ }
                    default -> System.out.println("[" + message.getType() + "] "
                            + message.getPayload());
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Stream error: " + t.getMessage());
                disconnected.countDown();
            }

            @Override
            public void onCompleted() {
                System.out.println("Stream completed.");
                disconnected.countDown();
            }
        });

        // Subscribe to /chat
        requestObserver.onNext(AtmosphereMessage.newBuilder()
                .setType(MessageType.SUBSCRIBE)
                .setTopic("/chat")
                .build());

        System.out.println("Connected to " + host + ":" + port + " — type a message and press Enter. Ctrl-C to quit.");

        // Read lines from stdin and send as messages
        var scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            var line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            requestObserver.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.MESSAGE)
                    .setTopic("/chat")
                    .setPayload(line)
                    .build());
        }

        requestObserver.onCompleted();
        disconnected.await();
        channel.shutdownNow();
    }
}
