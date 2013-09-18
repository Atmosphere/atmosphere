/*
 * Copyright 2013 Jeanfrancois Arcand
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
 *//* ClassFileBuffer.java
 * 
 ******************************************************************************
 *
 * Created: Oct 10, 2011
 * Character encoding: UTF-8
 * 
 * Copyright (c) 2011 - XIAM Solutions B.V. The Netherlands, http://www.xiam.nl
 * 
 ********************************* LICENSE ************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atmosphere.util.annotation;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * {@code ClassFileBuffer} is used by {@link eu.infomas.annotation.AnnotationDetector} to efficiently
 * read Java ClassFile files from an {@link java.io.InputStream} and parse the content
 * via the {@link java.io.DataInput} interface.
 * <br/>
 * Note that Java ClassFile files can grow really big,
 * {@code com.sun.corba.se.impl.logging.ORBUtilSystemException} is 128.2 kb!
 *
 * @author <a href="mailto:rmuller@xiam.nl">Ronald K. Muller</a>
 * @since annotation-detector 3.0.0
 */
final class ClassFileBuffer implements DataInput {

    private byte[] buffer;
    private int size; // the number of significant bytes read
    private int pointer; // the "read pointer"

    /**
     * Create a new, empty {@code ClassFileBuffer} with the default initial capacity (8 kb).
     */
    ClassFileBuffer() {
        this(8 * 1024);
    }

    /**
     * Create a new, empty {@code ClassFileBuffer} with the specified initial capacity.
     * The initial capacity must be greater than zero. The internal buffer will grow
     * automatically when a higher capacity is required. However, buffer resizing occurs
     * extra overhead. So in good initial capacity is important in performance critical
     * situations.
     */
    ClassFileBuffer(final int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("initialCapacity < 1: " + initialCapacity);
        }
        this.buffer = new byte[initialCapacity];
    }

    /**
     * Clear and fill the buffer of this {@code ClassFileBuffer} with the
     * supplied byte stream.
     * The read pointer is reset to the start of the byte array.
     */
    public void readFrom(final InputStream in) throws IOException {
        pointer = 0;
        size = 0;
        int n;
        do {
            n = in.read(buffer, size, buffer.length - size);
            if (n > 0) {
                size += n;
            }
            resizeIfNeeded();
        } while (n >= 0);
    }

    /**
     * Sets the file-pointer offset, measured from the beginning of this file,
     * at which the next read or write occurs.
     */
    public void seek(final int position) throws IOException {
        if (position < 0) {
            throw new IllegalArgumentException("position < 0: " + position);
        }
        if (position > size) {
            throw new EOFException();
        }
        this.pointer = position;
    }

    /**
     * Return the size (in bytes) of this Java ClassFile file.
     */
    public int size() {
        return size;
    }

    // DataInput

    @Override
    public void readFully(final byte bytes[]) throws IOException {
        readFully(bytes, 0, bytes.length);
    }

    @Override
    public void readFully(final byte bytes[], final int offset, final int length) throws IOException {
        if (length < 0 || offset < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException();
        }
        if (pointer + length > size) {
            throw new EOFException();
        }
        System.arraycopy(buffer, pointer, bytes, offset, length);
        pointer += length;
    }

    @Override
    public int skipBytes(final int n) throws IOException {
        seek(pointer + n);
        return n;
    }

    @Override
    public byte readByte() throws IOException {
        if (pointer >= size) {
            throw new EOFException();
        }
        return buffer[pointer++];
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readByte() != 0;
    }

    @Override
    public int readUnsignedByte() throws IOException {
        if (pointer >= size) {
            throw new EOFException();
        }
        return read();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        if (pointer + 2 > size) {
            throw new EOFException();
        }
        return (read() << 8) + read();
    }

    @Override
    public short readShort() throws IOException {
        return (short) readUnsignedShort();
    }

    @Override
    public char readChar() throws IOException {
        return (char) readUnsignedShort();
    }

    @Override
    public int readInt() throws IOException {
        if (pointer + 4 > size) {
            throw new EOFException();
        }
        return (read() << 24) +
                (read() << 16) +
                (read() << 8) +
                read();
    }

    @Override
    public long readLong() throws IOException {
        if (pointer + 8 > size) {
            throw new EOFException();
        }
        return (read() << 56) +
                (read() << 48) +
                (read() << 40) +
                (read() << 32) +
                (read() << 24) +
                (read() << 16) +
                (read() << 8) +
                read();
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    /**
     * This methods throws an {@link UnsupportedOperationException} because the method
     * is deprecated and not used in the context of this implementation.
     */
    @Override
    @Deprecated
    public String readLine() throws IOException {
        throw new UnsupportedOperationException("readLine() is deprecated and not supported");
    }

    @Override
    public String readUTF() throws IOException {
        return DataInputStream.readUTF(this);
    }

    // private

    private int read() {
        return buffer[pointer++] & 0xff;
    }

    private void resizeIfNeeded() {
        if (size >= buffer.length) {
            final byte[] newBuffer = new byte[buffer.length * 2];
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
            buffer = newBuffer;
        }
    }

    public void destroy() {
        buffer = null;
    }
}
