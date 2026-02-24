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
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.util.DefaultUUIDProvider;
import org.atmosphere.grpc.proto.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcProcessorTest {

    private AtmosphereFramework framework;
    private GrpcHandler handler;
    private GrpcProcessor processor;
    private BroadcasterFactory broadcasterFactory;
    private AtmosphereConfig config;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        framework = mock(AtmosphereFramework.class);
        handler = mock(GrpcHandler.class);
        config = mock(AtmosphereConfig.class);
        broadcasterFactory = mock(BroadcasterFactory.class);

        var uuidProvider = new DefaultUUIDProvider();
        when(config.uuidProvider()).thenReturn(uuidProvider);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(framework.getBroadcasterFactory()).thenReturn(broadcasterFactory);
        when(framework.getAsyncSupport()).thenReturn(mock(AsyncSupport.class));

        var resourceFactory = mock(AtmosphereResourceFactory.class);
        var resource = mock(AtmosphereResourceImpl.class);
        when(resource.broadcasters()).thenReturn(List.of());
        when(resourceFactory.create(any(AtmosphereConfig.class), any(), any(), any()))
                .thenReturn(resource);
        when(framework.atmosphereFactory()).thenReturn(resourceFactory);

        processor = new GrpcProcessor(framework, handler);
    }

    @SuppressWarnings("unchecked")
    @Test
    void openCreatesChannelAndCallsHandler() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        assertNotNull(channel);
        assertNotNull(channel.uuid());
        assertTrue(channel.isOpen());
        verify(handler).onOpen(channel);
    }

    @SuppressWarnings("unchecked")
    @Test
    void openSetsHandlerOnChannel() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        assertEquals(handler, channel.handler());
    }

    @SuppressWarnings("unchecked")
    @Test
    void openRegistersChannelForLookup() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        assertEquals(channel, processor.getChannel(channel.uuid()));
        assertTrue(processor.getChannels().contains(channel));
    }

    @SuppressWarnings("unchecked")
    @Test
    void closeRemovesChannelAndCallsHandler() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);
        var uuid = channel.uuid();

        processor.close(channel);

        assertNull(processor.getChannel(uuid));
        verify(handler).onClose(channel);
    }

    @SuppressWarnings("unchecked")
    @Test
    void onMessageSubscribeAddsResourceToBroadcaster() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        var broadcaster = mock(Broadcaster.class);
        when(broadcasterFactory.lookup("/chat", true)).thenReturn(broadcaster);

        var subscribeMsg = AtmosphereMessage.newBuilder()
                .setType(MessageType.SUBSCRIBE)
                .setTopic("/chat")
                .build();

        processor.onMessage(channel, subscribeMsg);

        verify(broadcasterFactory).lookup("/chat", true);
        verify(broadcaster).addAtmosphereResource(channel.resource());

        // Verify ACK sent
        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());
        var ack = captor.getValue();
        assertEquals(MessageType.ACK, ack.getType());
        assertEquals("/chat", ack.getTopic());
    }

    @SuppressWarnings("unchecked")
    @Test
    void onMessageUnsubscribeRemovesResourceFromBroadcaster() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        var broadcaster = mock(Broadcaster.class);
        when(broadcasterFactory.lookup("/chat", false)).thenReturn(broadcaster);

        var unsubMsg = AtmosphereMessage.newBuilder()
                .setType(MessageType.UNSUBSCRIBE)
                .setTopic("/chat")
                .build();

        processor.onMessage(channel, unsubMsg);

        verify(broadcaster).removeAtmosphereResource(channel.resource());
    }

    @SuppressWarnings("unchecked")
    @Test
    void onMessageTextCallsHandlerOnMessage() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        var textMsg = AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setPayload("hello world")
                .build();

        processor.onMessage(channel, textMsg);

        verify(handler).onMessage(channel, "hello world");
    }

    @SuppressWarnings("unchecked")
    @Test
    void onMessageBinaryCallsHandlerOnBinaryMessage() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        byte[] data = {1, 2, 3};
        var binMsg = AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setBinaryPayload(ByteString.copyFrom(data))
                .build();

        processor.onMessage(channel, binMsg);

        verify(handler).onBinaryMessage(eq(channel), eq(data));
    }

    @SuppressWarnings("unchecked")
    @Test
    void onMessageWithTopicBroadcastsToTopic() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        var broadcaster = mock(Broadcaster.class);
        when(broadcasterFactory.lookup("/chat", false)).thenReturn(broadcaster);

        var msg = AtmosphereMessage.newBuilder()
                .setType(MessageType.MESSAGE)
                .setTopic("/chat")
                .setPayload("broadcast me")
                .build();

        processor.onMessage(channel, msg);

        verify(broadcaster).broadcast("broadcast me");
    }

    @SuppressWarnings("unchecked")
    @Test
    void onMessageHeartbeatSendsPong() {
        var observer = mock(StreamObserver.class);
        var channel = processor.open(observer);

        var heartbeat = AtmosphereMessage.newBuilder()
                .setType(MessageType.HEARTBEAT)
                .build();

        processor.onMessage(channel, heartbeat);

        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());
        assertEquals(MessageType.HEARTBEAT, captor.getValue().getType());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getChannelsReturnsAllOpenChannels() {
        var observer1 = mock(StreamObserver.class);
        var observer2 = mock(StreamObserver.class);
        processor.open(observer1);
        processor.open(observer2);

        assertEquals(2, processor.getChannels().size());
    }

    @Test
    void getChannelReturnsNullForUnknownUuid() {
        assertNull(processor.getChannel("nonexistent"));
    }
}
