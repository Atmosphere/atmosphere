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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReaderInputStreamTest {

    @Test
    void readSingleByte() throws IOException {
        try (var ris = new ReaderInputStream(new StringReader("A"), StandardCharsets.UTF_8)) {
            assertEquals('A', ris.read());
            assertEquals(-1, ris.read());
        }
    }

    @Test
    void readIntoByteArray() throws IOException {
        try (var ris = new ReaderInputStream(new StringReader("hello"), StandardCharsets.UTF_8)) {
            var buf = new byte[10];
            int read = ris.read(buf, 0, buf.length);
            assertEquals(5, read);
            assertEquals("hello", new String(buf, 0, read, StandardCharsets.UTF_8));
        }
    }

    @Test
    void readEmptyReader() throws IOException {
        try (var ris = new ReaderInputStream(new StringReader(""), StandardCharsets.UTF_8)) {
            assertEquals(-1, ris.read());
        }
    }

    @Test
    void readZeroLengthReturnsZero() throws IOException {
        try (var ris = new ReaderInputStream(new StringReader("data"), StandardCharsets.UTF_8)) {
            assertEquals(0, ris.read(new byte[5], 0, 0));
        }
    }

    @Test
    void readNullArrayThrowsNPE() throws IOException {
        try (var ris = new ReaderInputStream(new StringReader("x"), StandardCharsets.UTF_8)) {
            assertThrows(NullPointerException.class, () -> ris.read(null, 0, 1));
        }
    }

    @Test
    void readInvalidOffsetThrows() throws IOException {
        try (var ris = new ReaderInputStream(new StringReader("x"), StandardCharsets.UTF_8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> ris.read(new byte[5], -1, 1));
        }
    }

    @Test
    void readMultiByteCharacters() throws IOException {
        String text = "日本語";
        try (var ris = new ReaderInputStream(new StringReader(text), StandardCharsets.UTF_8)) {
            var buf = new byte[50];
            int total = 0;
            int n;
            while ((n = ris.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            assertEquals(text, new String(buf, 0, total, StandardCharsets.UTF_8));
        }
    }

    @Test
    void closeClosesUnderlyingReader() throws IOException {
        var reader = new StringReader("test");
        var ris = new ReaderInputStream(reader, StandardCharsets.UTF_8);
        ris.close();
        assertThrows(IOException.class, reader::read);
    }

    @Test
    void charsetNameConstructor() throws IOException {
        try (var ris = new ReaderInputStream(new StringReader("ok"), "UTF-8")) {
            assertEquals('o', ris.read());
            assertEquals('k', ris.read());
        }
    }

    @Test
    void readByteArrayShortcut() throws IOException {
        try (var ris = new ReaderInputStream(new StringReader("abc"), StandardCharsets.UTF_8)) {
            var buf = new byte[10];
            int n = ris.read(buf);
            assertEquals(3, n);
            assertEquals('a', buf[0]);
            assertEquals('b', buf[1]);
            assertEquals('c', buf[2]);
        }
    }
}
