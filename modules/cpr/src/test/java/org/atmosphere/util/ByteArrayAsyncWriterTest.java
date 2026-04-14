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
package org.atmosphere.util;

import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ByteArrayAsyncWriterTest {

    private ByteArrayAsyncWriter writer;
    private AtmosphereResponse response;

    @BeforeEach
    void setUp() {
        writer = new ByteArrayAsyncWriter();
        response = mock(AtmosphereResponse.class);
        when(response.getCharacterEncoding()).thenReturn("UTF-8");
    }

    @Test
    void writeStringAppendesToStream() throws IOException {
        AsyncIOWriter result = writer.write(response, "hello");
        assertSame(writer, result);
        assertEquals("hello", writer.stream().toString("UTF-8"));
    }

    @Test
    void writeBytesAppendesToStream() throws IOException {
        byte[] data = {65, 66, 67};
        writer.write(response, data);
        assertArrayEquals(data, writer.stream().toByteArray());
    }

    @Test
    void writeBytesWithOffsetAppendesToStream() throws IOException {
        byte[] data = {65, 66, 67, 68};
        writer.write(response, data, 1, 2);
        assertArrayEquals(new byte[]{66, 67}, writer.stream().toByteArray());
    }

    @Test
    void multipleWritesAccumulate() throws IOException {
        writer.write(response, "hello ");
        writer.write(response, "world");
        assertEquals("hello world", writer.stream().toString("UTF-8"));
    }

    @Test
    void closeResetsStream() throws IOException {
        writer.write(response, "data");
        writer.close(response);
        assertEquals(0, writer.stream().size());
    }

    @Test
    void streamReturnsBackingOutputStream() {
        assertNotNull(writer.stream());
    }
}
