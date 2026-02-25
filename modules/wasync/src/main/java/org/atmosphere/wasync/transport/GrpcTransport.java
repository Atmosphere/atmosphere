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
package org.atmosphere.wasync.transport;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.AtmosphereServiceGrpc;
import org.atmosphere.grpc.proto.MessageType;
import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.FunctionResolver;
import org.atmosphere.wasync.Options;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link org.atmosphere.wasync.Transport} implementation using gRPC bidirectional streaming.
 *
 * <p>Connects to an {@code AtmosphereService} gRPC server and uses a single
 * bidirectional stream for subscribe/unsubscribe and message exchange.</p>
 */
public class GrpcTransport extends AbstractTransport {

    private final Options options;
    private volatile ManagedChannel channel;
    private volatile StreamObserver<AtmosphereMessage> requestObserver;
    private volatile String topic;
    private List<Decoder<?, ?>> decoders = List.of();
    private FunctionResolver resolver = FunctionResolver.DEFAULT;

    public GrpcTransport(Options options) {
        this.options = options;
    }

    @Override
    public Request.TRANSPORT name() {
        return Request.TRANSPORT.GRPC;
    }

    /**
     * Connect to the gRPC server at the given URI and subscribe to the request path as topic.
     */
    public CompletableFuture<Void> connect(URI uri, Request request) {
        this.decoders = request.decoders();
        this.resolver = request.functionResolver();

        var host = uri.getHost();
        var port = uri.getPort() > 0 ? uri.getPort() : 443;
        this.topic = uri.getPath() != null ? uri.getPath() : "/";

        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();

        var stub = AtmosphereServiceGrpc.newStub(channel);

        var future = new CompletableFuture<Void>();

        requestObserver = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                switch (message.getType()) {
                    case ACK -> {
                        if (status == Socket.STATUS.INIT) {
                            status = Socket.STATUS.OPEN;
                            dispatchEvent(Event.OPEN, message.getTrackingId());
                            future.complete(null);
                            if (connectFuture != null) {
                                connectFuture.complete(null);
                            }
                        }
                    }
                    case MESSAGE -> {
                        var payload = message.getPayload();
                        if (!payload.isEmpty()) {
                            dispatchMessage(Event.MESSAGE, payload, decoders, resolver);
                        } else if (!message.getBinaryPayload().isEmpty()) {
                            dispatchMessage(Event.MESSAGE_BYTES,
                                    message.getBinaryPayload().toByteArray(), decoders, resolver);
                        }
                    }
                    case ERROR -> {
                        var err = new RuntimeException(message.getPayload());
                        onThrowable(err);
                    }
                    case HEARTBEAT -> logger.trace("Heartbeat received");
                    default -> logger.debug("Unhandled message type: {}", message.getType());
                }
            }

            @Override
            public void onError(Throwable t) {
                onThrowable(t);
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                logger.debug("gRPC stream completed by server");
                status = Socket.STATUS.CLOSE;
                dispatchEvent(Event.CLOSE, "server completed stream");
            }
        });

        // Send SUBSCRIBE message with the request URI path as topic
        var subscribe = AtmosphereMessage.newBuilder()
                .setType(MessageType.SUBSCRIBE)
                .setTopic(topic)
                .build();
        requestObserver.onNext(subscribe);

        return future;
    }

    /**
     * Send a text message via the gRPC stream.
     */
    public CompletableFuture<Void> sendText(String text) {
        if (requestObserver == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("gRPC stream not connected"));
        }
        try {
            var builder = AtmosphereMessage.newBuilder()
                    .setType(MessageType.MESSAGE)
                    .setPayload(text);
            if (topic != null) {
                builder.setTopic(topic);
            }
            var msg = builder.build();
            requestObserver.onNext(msg);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void close() {
        status = Socket.STATUS.CLOSE;
        if (requestObserver != null) {
            try {
                requestObserver.onCompleted();
            } catch (Exception e) {
                logger.debug("Error completing gRPC stream", e);
            }
        }
        if (channel != null) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
}
