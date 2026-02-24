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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GrpcChannelTest {

    @SuppressWarnings("unchecked")
    private final StreamObserver<AtmosphereMessage> observer = mock(StreamObserver.class);
    private GrpcChannel channel;

    @BeforeEach
    void setUp() {
        channel = new GrpcChannel(observer, "test-uuid");
    }

    @Test
    void uuidReturnsConstructorValue() {
        assertEquals("test-uuid", channel.uuid());
    }

    @Test
    void newChannelIsOpen() {
        assertTrue(channel.isOpen());
    }

    @Test
    void resourceIsNullByDefault() {
        assertNull(channel.resource());
    }

    @Test
    void resourceBindingReturnsThis() {
        var resource = mock(AtmosphereResource.class);
        var result = channel.resource(resource);
        assertEquals(channel, result);
        assertEquals(resource, channel.resource());
    }

    @Test
    void handlerBindingReturnsThis() {
        var handler = mock(GrpcHandler.class);
        var result = channel.handler(handler);
        assertEquals(channel, result);
        assertEquals(handler, channel.handler());
    }

    @Test
    void handlerIsNullByDefault() {
        assertNull(channel.handler());
    }

    @Test
    void writeStringSendsMessageType() throws IOException {
        channel.write("hello");

        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());

        var msg = captor.getValue();
        assertEquals(MessageType.MESSAGE, msg.getType());
        assertEquals("hello", msg.getPayload());
        assertTrue(msg.getTopic().isEmpty());
    }

    @Test
    void writeBytesSendsBinaryPayload() throws IOException {
        byte[] data = {1, 2, 3, 4};
        channel.write(data);

        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());

        var msg = captor.getValue();
        assertEquals(MessageType.MESSAGE, msg.getType());
        assertArrayEquals(data, msg.getBinaryPayload().toByteArray());
    }

    @Test
    void writeStringWithTopicIncludesTopic() throws IOException {
        channel.write("/chat", "hello");

        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());

        var msg = captor.getValue();
        assertEquals(MessageType.MESSAGE, msg.getType());
        assertEquals("/chat", msg.getTopic());
        assertEquals("hello", msg.getPayload());
    }

    @Test
    void writeBytesWithTopicIncludesTopic() throws IOException {
        byte[] data = {5, 6};
        channel.write("/data", data);

        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());

        var msg = captor.getValue();
        assertEquals(MessageType.MESSAGE, msg.getType());
        assertEquals("/data", msg.getTopic());
        assertArrayEquals(data, msg.getBinaryPayload().toByteArray());
    }

    @Test
    void closeCompletesObserver() {
        channel.close();
        verify(observer).onCompleted();
        assertFalse(channel.isOpen());
    }

    @Test
    void doubleCloseOnlyCompletesOnce() {
        channel.close();
        channel.close();
        verify(observer, times(1)).onCompleted();
    }

    @Test
    void writeAfterCloseThrowsIOException() {
        channel.close();
        assertThrows(IOException.class, () -> channel.write("data"));
    }

    @Test
    void writeBytesAfterCloseThrowsIOException() {
        channel.close();
        assertThrows(IOException.class, () -> channel.write(new byte[]{1}));
    }

    @Test
    void writeWithTopicAfterCloseThrowsIOException() {
        channel.close();
        assertThrows(IOException.class, () -> channel.write("/t", "data"));
    }

    @Test
    void writeBytesWithTopicAfterCloseThrowsIOException() {
        channel.close();
        assertThrows(IOException.class, () -> channel.write("/t", new byte[]{1}));
    }

    @Test
    void lastWriteTimestampUpdatedOnWrite() throws IOException {
        long before = System.currentTimeMillis();
        channel.write("data");
        long after = System.currentTimeMillis();

        assertTrue(channel.lastWriteTimestamp() >= before);
        assertTrue(channel.lastWriteTimestamp() <= after);
    }

    @Test
    void sendRawSendsPrebuiltMessage() throws IOException {
        var raw = AtmosphereMessage.newBuilder()
                .setType(MessageType.HEARTBEAT)
                .build();
        channel.sendRaw(raw);

        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());
        assertEquals(MessageType.HEARTBEAT, captor.getValue().getType());
    }

    @Test
    void sendRawAfterCloseThrowsIOException() {
        channel.close();
        var raw = AtmosphereMessage.newBuilder()
                .setType(MessageType.ACK)
                .build();
        assertThrows(IOException.class, () -> channel.sendRaw(raw));
    }

    @Test
    void closeDoesNotCallOnNextOnObserver() {
        channel.close();
        verify(observer, never()).onNext(org.mockito.ArgumentMatchers.any());
    }
}
