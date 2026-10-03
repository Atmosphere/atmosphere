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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link IOUtils#readEntirelyAsString(AtmosphereResource, int)} reads at most
 * the limit, refuses a larger body with {@link PayloadTooLargeException}, and
 * decodes strictly.
 */
class IOUtilsBoundedReadTest {

    private static AtmosphereResource post(AtmosphereRequest request) {
        var resource = mock(AtmosphereResource.class);
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(anyString(), anyBoolean())).thenReturn(false);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getRequest()).thenReturn(request);
        return resource;
    }

    private static AtmosphereRequest streamed(byte[] body) {
        return new AtmosphereRequestImpl.Builder().method("POST")
                .inputStream(new ByteArrayInputStream(body)).build();
    }

    @Test
    void aBodyOfExactlyTheLimitIsRead() throws IOException {
        var text = "é".repeat(8); // 16 UTF-8 bytes
        var read = IOUtils.readEntirelyAsString(post(streamed(text.getBytes(StandardCharsets.UTF_8))), 16);
        assertEquals(text, read);
    }

    @Test
    void aBodyOneByteOverTheLimitIsRefused() {
        var body = "x".repeat(17).getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(PayloadTooLargeException.class,
                () -> IOUtils.readEntirelyAsString(post(streamed(body)), 16));
        assertEquals(16, e.limit());
    }

    @Test
    void aBodyIsNeverReadPastTheLimit() {
        // An endless body: the read must stop at the limit, not buffer it all.
        var endless = new InputStream() {
            long served;

            @Override
            public int read() {
                served++;
                return 'a';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                java.util.Arrays.fill(b, off, off + len, (byte) 'a');
                served += len;
                return len;
            }
        };
        var request = new AtmosphereRequestImpl.Builder().method("POST").inputStream(endless).build();
        assertThrows(PayloadTooLargeException.class, () -> IOUtils.readEntirelyAsString(post(request), 1024));
        assertTrue(endless.served <= 1024 + 8192, "read " + endless.served + " bytes for a 1024-byte limit");
    }

    @Test
    void aDeclaredContentLengthOverTheLimitIsRefusedBeforeReading() {
        var request = new AtmosphereRequestImpl.Builder().method("POST").contentLength(100L)
                .inputStream(new InputStream() {
                    @Override
                    public int read() {
                        throw new AssertionError("the body must not be read");
                    }
                }).build();
        assertThrows(PayloadTooLargeException.class, () -> IOUtils.readEntirelyAsString(post(request), 16));
    }

    @Test
    void aCachedBodyIsCheckedAgainstTheLimit() throws IOException {
        var within = new AtmosphereRequestImpl.Builder().method("POST").body("0123456789abcdef").build();
        assertEquals("0123456789abcdef", IOUtils.readEntirelyAsString(post(within), 16));

        var over = new AtmosphereRequestImpl.Builder().method("POST").body("0123456789abcdefg").build();
        assertThrows(PayloadTooLargeException.class, () -> IOUtils.readEntirelyAsString(post(over), 16));

        var bytesOver = new AtmosphereRequestImpl.Builder().method("POST").body(new byte[17]).build();
        assertThrows(PayloadTooLargeException.class, () -> IOUtils.readEntirelyAsString(post(bytesOver), 16));
    }

    @Test
    void malformedTextIsRefused() {
        var invalidUtf8 = new byte[]{'h', 'i', (byte) 0xC3, (byte) 0x28};
        assertThrows(CharacterCodingException.class,
                () -> IOUtils.readEntirelyAsString(post(streamed(invalidUtf8)), 1024));
    }

    @Test
    void anUnknownEncodingIsRefused() {
        var request = new AtmosphereRequestImpl.Builder().method("POST").encoding("no-such-charset")
                .inputStream(new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8))).build();
        assertThrows(UnsupportedEncodingException.class, () -> IOUtils.readEntirelyAsString(post(request), 1024));
    }

    @Test
    void aDecodeOnlyEncodingIsRefused() {
        var request = new AtmosphereRequestImpl.Builder().method("POST").body("cached")
                .encoding("x-JISAutoDetect").build();
        assertThrows(UnsupportedEncodingException.class, () -> IOUtils.readEntirelyAsString(post(request), 1024));
    }

    @Test
    void aBodyAlreadyOpenedAsAReaderIsReadWithinTheLimit() throws IOException {
        var within = mock(AtmosphereRequest.class);
        when(within.getMethod()).thenReturn("POST");
        when(within.body()).thenReturn(new AtmosphereRequestImpl.Builder().build().body());
        when(within.getContentLength()).thenReturn(-1);
        when(within.getInputStream()).thenThrow(new IllegalStateException("getReader() was called"));
        when(within.getReader()).thenReturn(new java.io.BufferedReader(new java.io.StringReader("abc")));
        assertEquals("abc", IOUtils.readEntirelyAsString(post(within), 3));

        var over = mock(AtmosphereRequest.class);
        when(over.getMethod()).thenReturn("POST");
        when(over.body()).thenReturn(new AtmosphereRequestImpl.Builder().build().body());
        when(over.getContentLength()).thenReturn(-1);
        when(over.getInputStream()).thenThrow(new IllegalStateException("getReader() was called"));
        when(over.getReader()).thenReturn(new java.io.BufferedReader(new java.io.StringReader("abcd")));
        assertThrows(PayloadTooLargeException.class, () -> IOUtils.readEntirelyAsString(post(over), 3));
    }

    @Test
    void theRequestEncodingIsHonored() throws IOException {
        var request = new AtmosphereRequestImpl.Builder().method("POST").encoding("ISO-8859-1")
                .inputStream(new ByteArrayInputStream(new byte[]{(byte) 0xE9})).build();
        assertEquals("é", IOUtils.readEntirelyAsString(post(request), 16));
    }

    @Test
    void aGetBodyIsNotRead() throws IOException {
        var request = new AtmosphereRequestImpl.Builder().method("GET")
                .inputStream(new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8))).build();
        assertEquals("", IOUtils.readEntirelyAsString(post(request), 16));
    }
}
