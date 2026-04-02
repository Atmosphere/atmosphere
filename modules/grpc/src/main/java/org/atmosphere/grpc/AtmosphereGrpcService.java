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

import io.grpc.stub.StreamObserver;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.AtmosphereServiceGrpc;
import org.atmosphere.grpc.proto.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC service implementation that extends the generated {@link AtmosphereServiceGrpc.AtmosphereServiceImplBase}.
 *
 * <p>Provides three RPCs:
 * <ul>
 *   <li>{@code Stream} — bidirectional streaming for native gRPC clients</li>
 *   <li>{@code Subscribe} — server-streaming, browser-compatible via Connect / gRPC-Web</li>
 *   <li>{@code Send} — unary, browser-compatible via Connect / gRPC-Web</li>
 * </ul>
 */
public class AtmosphereGrpcService extends AtmosphereServiceGrpc.AtmosphereServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereGrpcService.class);

    private final GrpcProcessor processor;

    public AtmosphereGrpcService(GrpcProcessor processor) {
        this.processor = processor;
    }

    @Override
    public StreamObserver<AtmosphereMessage> stream(StreamObserver<AtmosphereMessage> responseObserver) {
        var channel = processor.open(responseObserver);

        return new StreamObserver<>() {
            @Override
            public void onNext(AtmosphereMessage message) {
                try {
                    processor.onMessage(channel, message);
                } catch (Exception e) {
                    logger.error("Error processing message on channel {}", channel.uuid(), e);
                    if (channel.handler() != null) {
                        channel.handler().onError(channel, e);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.error("gRPC stream error on channel {}", channel.uuid(), t);
                if (channel.handler() != null) {
                    channel.handler().onError(channel, t);
                }
                processor.close(channel);
            }

            @Override
            public void onCompleted() {
                processor.close(channel);
            }
        };
    }

    @Override
    public void subscribe(AtmosphereMessage request, StreamObserver<AtmosphereMessage> responseObserver) {
        var channel = processor.open(responseObserver);

        // Force the message type to SUBSCRIBE regardless of what the client sent
        var subscribeMsg = request.toBuilder()
                .setType(MessageType.SUBSCRIBE)
                .setTrackingId(channel.uuid())
                .build();

        try {
            processor.onMessage(channel, subscribeMsg);
            logger.debug("Web client {} subscribed to topic {} via Connect/gRPC-Web",
                    channel.uuid(), request.getTopic());
        } catch (Exception e) {
            logger.error("Error processing subscribe on channel {}", channel.uuid(), e);
            processor.close(channel);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
        // Response stream stays open — messages arrive via the Broadcaster → GrpcAsyncIOWriter
    }

    @Override
    public void send(AtmosphereMessage request, StreamObserver<AtmosphereMessage> responseObserver) {
        var trackingId = request.getTrackingId();
        var channel = processor.getChannel(trackingId);

        if (channel == null) {
            responseObserver.onError(io.grpc.Status.NOT_FOUND
                    .withDescription("No channel found for tracking_id: " + trackingId)
                    .asRuntimeException());
            return;
        }

        var message = request.toBuilder()
                .setType(MessageType.MESSAGE)
                .build();

        try {
            processor.onMessage(channel, message);
            var ack = AtmosphereMessage.newBuilder()
                    .setType(MessageType.ACK)
                    .setTopic(request.getTopic())
                    .setTrackingId(trackingId)
                    .build();
            responseObserver.onNext(ack);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing send on channel {}", channel.uuid(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
