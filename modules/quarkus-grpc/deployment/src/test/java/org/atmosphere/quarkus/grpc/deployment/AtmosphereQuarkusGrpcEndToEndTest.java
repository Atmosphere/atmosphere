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
package org.atmosphere.quarkus.grpc.deployment;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.QuarkusExtensionTest;

import jakarta.enterprise.inject.spi.CDI;

import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.AtmosphereServiceGrpc;
import org.atmosphere.grpc.proto.MessageType;
import org.atmosphere.quarkus.grpc.runtime.AtmosphereQuarkusGrpcLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test that boots the full {@code atmosphere-quarkus-grpc} extension
 * inside a {@link QuarkusExtensionTest}, then drives the standalone Netty gRPC
 * server via a real client {@link AtmosphereServiceGrpc} stub.
 *
 * <p>Asserts:
 * <ul>
 *   <li>The lifecycle bean is registered (would fail if
 *       {@code AtmosphereQuarkusGrpcProcessor.registerLifecycleBean} were removed),</li>
 *   <li>The standalone gRPC server is bound on the ephemeral port the test
 *       requested (would fail if {@code @Observes StartupEvent} were not firing),</li>
 *   <li>A real bi-di stream connects, SUBSCRIBE elicits an ACK, MESSAGE is echoed
 *       back through the Broadcaster (proving the wire-shape parity with the
 *       Spring Boot starter — the on-wire proto is shared so identical clients
 *       work against either backend).</li>
 * </ul>
 */
class AtmosphereQuarkusGrpcEndToEndTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereQuarkusGrpcEndToEndTest.class))
            // Pin the core extension's package scan empty (no @ManagedService /
            // @AiEndpoint to discover); we only need the framework alive so the
            // gRPC processor has a Broadcaster factory to attach to.
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.grpc.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0")
            // Enable gRPC; ephemeral port to dodge conflicts with parallel tests.
            .overrideConfigKey("quarkus.atmosphere.grpc.enabled", "true")
            .overrideConfigKey("quarkus.atmosphere.grpc.port", "0");

    @Test
    void grpcServerStreamsAcrossBroadcaster() throws Exception {
        AtmosphereQuarkusGrpcLifecycle lifecycle =
                CDI.current().select(AtmosphereQuarkusGrpcLifecycle.class).get();

        int port = lifecycle.port();
        assertNotEquals(-1, port,
                "Atmosphere gRPC server must be bound — StartupEvent observer did not fire");

        // grpc-netty-shaded is on the test classpath; we use it directly to
        // prove that a vanilla Netty gRPC client (the same shape a customer
        // would use) interoperates with the Quarkus-hosted server.
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext()
                .build();
        try {
            CopyOnWriteArrayList<AtmosphereMessage> received = new CopyOnWriteArrayList<>();
            CountDownLatch ackLatch = new CountDownLatch(1);
            CountDownLatch echoLatch = new CountDownLatch(1);

            AtmosphereServiceGrpc.AtmosphereServiceStub stub =
                    AtmosphereServiceGrpc.newStub(channel);

            StreamObserver<AtmosphereMessage> requestObserver = stub.stream(new StreamObserver<>() {
                @Override
                public void onNext(AtmosphereMessage message) {
                    received.add(message);
                    if (message.getType() == MessageType.ACK) {
                        ackLatch.countDown();
                    } else if (message.getType() == MessageType.MESSAGE
                            && "hello quarkus grpc".equals(message.getPayload())) {
                        echoLatch.countDown();
                    }
                }

                @Override
                public void onError(Throwable t) {
                    // Failure path — surfaced via the latch timeouts below.
                }

                @Override
                public void onCompleted() {
                    // Server-side completion — also surfaced via latch timeouts.
                }
            });

            // SUBSCRIBE on a unique topic — ACK confirms processor.onMessage()
            // ran through the SUBSCRIBE branch and the channel is parked on
            // the Broadcaster.
            requestObserver.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.SUBSCRIBE)
                    .setTopic("/quarkus-grpc-e2e")
                    .build());

            assertTrue(ackLatch.await(5, TimeUnit.SECONDS),
                    "Did not receive SUBSCRIBE ACK from Atmosphere gRPC server "
                            + "within 5s; received messages so far: " + received);

            // Send a MESSAGE on the same topic — the GrpcProcessor broadcasts
            // via the framework Broadcaster which fans out to all subscribers.
            // Since this stream is the only subscriber, we expect the payload
            // back as a MESSAGE.
            requestObserver.onNext(AtmosphereMessage.newBuilder()
                    .setType(MessageType.MESSAGE)
                    .setTopic("/quarkus-grpc-e2e")
                    .setPayload("hello quarkus grpc")
                    .build());

            assertTrue(echoLatch.await(5, TimeUnit.SECONDS),
                    "Did not receive broadcast echo of test payload within 5s; "
                            + "received messages so far: " + received);

            // Close cleanly. The processor's onCompleted handler removes the
            // channel from the registry and the resource from the Broadcaster.
            requestObserver.onCompleted();

            // Sanity-check that the ACK carried the topic we subscribed to and
            // that the broadcast echo had the right payload — guards against a
            // future regression where the message gets routed to the wrong
            // topic but the latch happens to count down off a spurious ACK.
            AtmosphereMessage ack = received.stream()
                    .filter(m -> m.getType() == MessageType.ACK)
                    .findFirst()
                    .orElseThrow();
            assertEquals("/quarkus-grpc-e2e", ack.getTopic(),
                    "ACK topic must match the SUBSCRIBE topic");
            assertFalse(ack.getTrackingId().isEmpty(),
                    "ACK must carry the server-assigned tracking_id");
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
