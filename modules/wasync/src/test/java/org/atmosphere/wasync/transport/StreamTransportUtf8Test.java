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
package org.atmosphere.wasync.transport;

import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.FunctionResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * Regression (registre#38): the stream read loop used to decode each raw
 * transport chunk with {@code new String(buf, 0, n, UTF_8)}, so any
 * multi-byte UTF-8 character split across a buffer boundary decoded to
 * replacement characters. Decoding must be incremental across chunks.
 */
class StreamTransportUtf8Test {

    /** Delivers the payload one byte per read() — the worst-case split. */
    private static final class OneByteAtATimeStream extends InputStream {
        private final byte[] data;
        private int pos;

        OneByteAtATimeStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            b[off] = data[pos++];
            return 1;
        }
    }

    private static final class CapturingStreamTransport extends StreamTransport {
        final List<Object> messages = new CopyOnWriteArrayList<>();

        CapturingStreamTransport() {
            super(HttpClient.newHttpClient(),
                    new org.atmosphere.wasync.impl.DefaultOptionsBuilder().build());
        }

        @Override
        protected void dispatchMessage(Event event, Object message,
                                       List<Decoder<?, ?>> decoders, FunctionResolver resolver) {
            messages.add(message);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiByteCharactersSurviveChunkBoundarySplits() throws IOException {
        var payload = "héllo wörld — caractères accentués: éàçüñ🎉";
        var transport = new CapturingStreamTransport();
        var response = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(
                new OneByteAtATimeStream(payload.getBytes(StandardCharsets.UTF_8)));

        transport.readLoop(response);

        var reassembled = String.join("", transport.messages.stream()
                .map(Object::toString).toList());
        assertFalse(reassembled.contains("�"),
                "split multi-byte sequences must never decode to replacement "
                + "characters: " + reassembled);
        assertEquals(payload.replace(" ", ""), reassembled.replace(" ", ""),
                "every character must survive chunk-boundary splits intact");
    }
}
