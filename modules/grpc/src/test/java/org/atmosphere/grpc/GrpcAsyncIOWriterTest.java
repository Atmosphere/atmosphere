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
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GrpcAsyncIOWriterTest {

    @SuppressWarnings("unchecked")
    private final StreamObserver<AtmosphereMessage> observer = mock(StreamObserver.class);
    private GrpcChannel channel;
    private GrpcAsyncIOWriter writer;
    private final AtmosphereResponse response = mock(AtmosphereResponse.class);

    @BeforeEach
    void setUp() {
        channel = new GrpcChannel(observer, "writer-test-uuid");
        writer = new GrpcAsyncIOWriter(channel);
    }

    @Test
    void writeStringDelegatesToChannel() throws IOException {
        var result = writer.write(response, "hello");

        assertSame(writer, result);
        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());
        assertEquals("hello", captor.getValue().getPayload());
        assertEquals(MessageType.MESSAGE, captor.getValue().getType());
    }

    @Test
    void writeBytesDelegatesToChannel() throws IOException {
        byte[] data = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var result = writer.write(response, data);

        assertSame(writer, result);
        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());
        assertEquals("abc", captor.getValue().getPayload());
    }

    @Test
    void writeBytesWithFullRangeDelegatesToChannel() throws IOException {
        byte[] data = "xyz".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var result = writer.write(response, data, 0, data.length);

        assertSame(writer, result);
        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());
        assertEquals("xyz", captor.getValue().getPayload());
    }

    @Test
    void writeBytesWithOffsetSlicesCorrectly() throws IOException {
        byte[] data = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.write(response, data, 1, 3);

        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());
        assertEquals("ell", captor.getValue().getPayload());
    }

    @Test
    void writeErrorSendsFormattedString() throws IOException {
        var result = writer.writeError(response, 500, "Internal Error");

        assertSame(writer, result);
        var captor = ArgumentCaptor.forClass(AtmosphereMessage.class);
        verify(observer).onNext(captor.capture());
        assertEquals("ERROR 500: Internal Error", captor.getValue().getPayload());
    }

    @Test
    void closeClosesChannel() throws IOException {
        writer.close(response);

        assertFalse(channel.isOpen());
        verify(observer).onCompleted();
    }

    @Test
    void redirectReturnsThis() throws IOException {
        var result = writer.redirect(response, "http://example.com");
        assertSame(writer, result);
    }

    @Test
    void flushReturnsThis() throws IOException {
        var result = writer.flush(response);
        assertSame(writer, result);
    }
}
