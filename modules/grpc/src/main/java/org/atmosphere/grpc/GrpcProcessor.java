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
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.MessageType;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle manager for gRPC connections — analogous to DefaultWebSocketProcessor.
 * Manages mapping gRPC streams to {@link AtmosphereResource} instances.
 */
public class GrpcProcessor {

    private static final Logger logger = LoggerFactory.getLogger(GrpcProcessor.class);

    private final AtmosphereFramework framework;
    private final GrpcHandler handler;
    private final Map<String, GrpcChannel> channels = new ConcurrentHashMap<>();

    public GrpcProcessor(AtmosphereFramework framework, GrpcHandler handler) {
        this.framework = framework;
        this.handler = handler;
    }

    public GrpcChannel open(StreamObserver<AtmosphereMessage> responseObserver) {
        var config = framework.getAtmosphereConfig();
        var uuid = config.uuidProvider().generateUuid();

        var channel = new GrpcChannel(responseObserver, uuid);
        channel.handler(handler);

        var request = new AtmosphereRequestImpl.Builder()
                .headers(Map.of(
                        HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.GRPC_TRANSPORT,
                        HeaderConfig.X_ATMOSPHERE_TRACKING_ID, uuid))
                .pathInfo("/grpc")
                .method("GET")
                .build();

        var writer = new GrpcAsyncIOWriter(channel);
        var response = new AtmosphereResponseImpl.Builder()
                .asyncIOWriter(writer)
                .request(request)
                .writeHeader(false)
                .build();

        try {
            var resource = framework.atmosphereFactory().create(
                    config, request, response, framework.getAsyncSupport());
            if (resource instanceof AtmosphereResourceImpl impl) {
                impl.transport(TRANSPORT.GRPC);
                impl.atmosphereHandler(new AbstractReflectorAtmosphereHandler.Default());
                impl.action().type(Action.TYPE.SUSPEND);
                impl.action().timeout(-1);
            }
            channel.resource(resource);
        } catch (Exception e) {
            logger.error("Failed to create AtmosphereResource for gRPC channel {}", uuid, e);
        }

        channels.put(uuid, channel);
        handler.onOpen(channel);
        logger.debug("gRPC channel opened: {}", uuid);
        return channel;
    }

    public void onMessage(GrpcChannel channel, AtmosphereMessage message) {
        switch (message.getType()) {
            case SUBSCRIBE -> handleSubscribe(channel, message);
            case UNSUBSCRIBE -> handleUnsubscribe(channel, message);
            case MESSAGE -> handleMessage(channel, message);
            case HEARTBEAT -> handleHeartbeat(channel);
            default -> logger.warn("Unhandled message type: {}", message.getType());
        }
    }

    public void close(GrpcChannel channel) {
        var uuid = channel.uuid();
        channels.remove(uuid);

        var resource = channel.resource();
        if (resource != null) {
            for (Broadcaster b : resource.broadcasters()) {
                b.removeAtmosphereResource(resource);
            }
            framework.atmosphereFactory().remove(uuid);
        }

        handler.onClose(channel);
        channel.close();
        logger.debug("gRPC channel closed: {}", uuid);
    }

    public GrpcChannel getChannel(String uuid) {
        return channels.get(uuid);
    }

    public Collection<GrpcChannel> getChannels() {
        return channels.values();
    }

    private void handleSubscribe(GrpcChannel channel, AtmosphereMessage message) {
        var topic = message.getTopic();
        var broadcaster = framework.getBroadcasterFactory().lookup(topic, true);
        broadcaster.addAtmosphereResource(channel.resource());
        sendAck(channel, topic);
        logger.debug("Channel {} subscribed to topic {}", channel.uuid(), topic);
    }

    private void handleUnsubscribe(GrpcChannel channel, AtmosphereMessage message) {
        var topic = message.getTopic();
        var broadcaster = framework.getBroadcasterFactory().lookup(topic, false);
        if (broadcaster != null) {
            broadcaster.removeAtmosphereResource(channel.resource());
        }
        sendAck(channel, topic);
        logger.debug("Channel {} unsubscribed from topic {}", channel.uuid(), topic);
    }

    private void handleMessage(GrpcChannel channel, AtmosphereMessage message) {
        if (!message.getPayload().isEmpty()) {
            handler.onMessage(channel, message.getPayload());
        } else if (!message.getBinaryPayload().isEmpty()) {
            handler.onBinaryMessage(channel, message.getBinaryPayload().toByteArray());
        }

        // Broadcast through Atmosphere's transport-agnostic Broadcaster so that
        // ALL subscribed resources (WebSocket, SSE, gRPC, etc.) receive the message.
        if (!message.getTopic().isEmpty()) {
            var broadcaster = framework.getBroadcasterFactory().lookup(message.getTopic(), false);
            if (broadcaster != null) {
                if (!message.getPayload().isEmpty()) {
                    broadcaster.broadcast(message.getPayload());
                } else if (!message.getBinaryPayload().isEmpty()) {
                    broadcaster.broadcast(message.getBinaryPayload().toByteArray());
                }
            }
        }
    }

    private void handleHeartbeat(GrpcChannel channel) {
        try {
            channel.sendRaw(AtmosphereMessage.newBuilder()
                    .setType(MessageType.HEARTBEAT)
                    .build());
        } catch (Exception e) {
            logger.warn("Failed to send heartbeat pong to channel {}", channel.uuid(), e);
        }
    }

    private void sendAck(GrpcChannel channel, String topic) {
        try {
            channel.sendRaw(AtmosphereMessage.newBuilder()
                    .setType(MessageType.ACK)
                    .setTopic(topic)
                    .build());
        } catch (Exception e) {
            logger.warn("Failed to send ACK to channel {}", channel.uuid(), e);
        }
    }
}
