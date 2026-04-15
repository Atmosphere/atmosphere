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
package org.atmosphere.util.annotation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileBufferTest {

    @Test
    void readFromAndSize() throws IOException {
        var data = new byte[]{1, 2, 3, 4, 5};
        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(data));
        assertEquals(5, buffer.size());
    }

    @Test
    void seekAndReadByte() throws IOException {
        var data = new byte[]{10, 20, 30};
        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(data));
        buffer.seek(0);
        assertEquals(10, buffer.readByte());
        assertEquals(20, buffer.readByte());
        assertEquals(30, buffer.readByte());
    }

    @Test
    void seekBeyondSizeThrows() throws IOException {
        var data = new byte[]{1, 2};
        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(data));
        assertThrows(EOFException.class, () -> buffer.seek(10));
    }

    @Test
    void readInt() throws IOException {
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        dos.writeInt(42);
        dos.flush();

        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(baos.toByteArray()));
        buffer.seek(0);
        assertEquals(42, buffer.readInt());
    }

    @Test
    void readShort() throws IOException {
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        dos.writeShort(1234);
        dos.flush();

        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(baos.toByteArray()));
        buffer.seek(0);
        assertEquals(1234, buffer.readShort());
    }

    @Test
    void readUnsignedShort() throws IOException {
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        dos.writeShort(60000);
        dos.flush();

        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(baos.toByteArray()));
        buffer.seek(0);
        assertEquals(60000, buffer.readUnsignedShort());
    }

    @Test
    void readUTF() throws IOException {
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        dos.writeUTF("hello");
        dos.flush();

        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(baos.toByteArray()));
        buffer.seek(0);
        assertEquals("hello", buffer.readUTF());
    }

    @Test
    void readBoolean() throws IOException {
        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(new byte[]{1, 0}));
        buffer.seek(0);
        assertTrue(buffer.readBoolean());
    }

    @Test
    void readFully() throws IOException {
        var data = new byte[]{10, 20, 30, 40};
        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(data));
        buffer.seek(0);

        var out = new byte[4];
        buffer.readFully(out, 0, 4);
        assertEquals(10, out[0]);
        assertEquals(40, out[3]);
    }

    @Test
    void readLong() throws IOException {
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        dos.writeLong(123456789L);
        dos.flush();

        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(baos.toByteArray()));
        buffer.seek(0);
        assertEquals(123456789L, buffer.readLong());
    }

    @Test
    void readChar() throws IOException {
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        dos.writeChar('Z');
        dos.flush();

        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(baos.toByteArray()));
        buffer.seek(0);
        assertEquals('Z', buffer.readChar());
    }

    @Test
    void customInitialCapacity() throws IOException {
        var buffer = new ClassFileBuffer(16);
        var data = new byte[32];
        for (int i = 0; i < 32; i++) data[i] = (byte) i;
        buffer.readFrom(new ByteArrayInputStream(data));
        assertEquals(32, buffer.size());
    }

    @Test
    void destroy() throws IOException {
        var buffer = new ClassFileBuffer();
        buffer.readFrom(new ByteArrayInputStream(new byte[]{1, 2}));
        buffer.destroy();
        // After destroy, the internal byte array is null so reading throws
        buffer.seek(0);
        assertThrows(NullPointerException.class, buffer::readByte);
    }
}
