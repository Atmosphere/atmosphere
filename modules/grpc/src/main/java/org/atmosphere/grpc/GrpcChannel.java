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

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a gRPC {@link StreamObserver} for outbound messages — analogous to WebSocket.
 * Holds the outbound stream observer and the associated {@link AtmosphereResource}.
 */
public class GrpcChannel {

    private static final Logger logger = LoggerFactory.getLogger(GrpcChannel.class);

    private final StreamObserver<AtmosphereMessage> responseObserver;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String uuid;
    private AtmosphereResource resource;
    private GrpcHandler handler;
    private volatile long lastWrite;

    public GrpcChannel(StreamObserver<AtmosphereMessage> responseObserver, String uuid) {
        this.responseObserver = responseObserver;
        this.uuid = uuid;
    }

    public AtmosphereResource resource() {
        return resource;
    }

    public GrpcChannel resource(AtmosphereResource resource) {
        this.resource = resource;
        return this;
    }

    public GrpcHandler handler() {
        return handler;
    }

    public GrpcChannel handler(GrpcHandler handler) {
        this.handler = handler;
        return this;
    }

    public String uuid() {
        return uuid;
    }

    public long lastWriteTimestamp() {
        return lastWrite;
    }

    public void write(String data) throws IOException {
        checkOpen();
        var msg = AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setPayload(data)
                .build();
        send(msg);
    }

    public void write(byte[] data) throws IOException {
        checkOpen();
        var msg = AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setBinaryPayload(ByteString.copyFrom(data))
                .build();
        send(msg);
    }

    public void write(String topic, String data) throws IOException {
        checkOpen();
        var msg = AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setTopic(topic)
                .setPayload(data)
                .build();
        send(msg);
    }

    public void write(String topic, byte[] data) throws IOException {
        checkOpen();
        var msg = AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setTopic(topic)
                .setBinaryPayload(ByteString.copyFrom(data))
                .build();
        send(msg);
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                synchronized (responseObserver) {
                    responseObserver.onCompleted();
                }
            } catch (Exception e) {
                logger.trace("Error closing gRPC channel {}", uuid, e);
            }
        }
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /**
     * Send a raw pre-built {@link AtmosphereMessage} directly to the stream observer.
     */
    void sendRaw(AtmosphereMessage message) throws IOException {
        checkOpen();
        send(message);
    }

    private void checkOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("GrpcChannel " + uuid + " is closed");
        }
    }

    private void send(AtmosphereMessage message) {
        synchronized (responseObserver) {
            responseObserver.onNext(message);
            lastWrite = System.currentTimeMillis();
        }
    }
}
