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
package org.atmosphere.mcp.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SseEventReaderTest {

    private static SseEventReader reader(String body) {
        return new SseEventReader(new StringReader(body));
    }

    @Test
    public void readsEventsInOrderAndEndsAtEndOfStream() throws IOException {
        try (var r = reader("event: message\ndata: one\n\ndata:two\n\n")) {
            assertEquals(Optional.of("one"), r.next());
            assertEquals(Optional.of("two"), r.next());
            assertEquals(Optional.empty(), r.next());
        }
    }

    @Test
    public void joinsDataLinesAndAcceptsCrAndCrlfLineEnds() throws IOException {
        try (var r = reader("data: a\r\ndata: b\r\rdata: c\n\n")) {
            assertEquals(Optional.of("a\nb"), r.next());
            assertEquals(Optional.of("c"), r.next());
        }
    }

    @Test
    public void skipsCommentsOtherFieldsEmptyEventsAndNonMessageTypes() throws IOException {
        try (var r = reader(": keep-alive\n\nid: 7\nretry: 100\n\nevent: ping\ndata: skipped\n\n"
                + "event: message\nid: 8\ndata: kept\n\n")) {
            assertEquals(Optional.of("kept"), r.next());
            assertEquals(Optional.empty(), r.next());
        }
    }

    @Test
    public void discardsAnEventCutOffByTheEndOfTheStream() throws IOException {
        try (var r = reader("data: whole\n\ndata: cut")) {
            assertEquals(Optional.of("whole"), r.next());
            assertEquals(Optional.empty(), r.next());
        }
    }

    @Test
    public void rejectsAnOversizedEvent() {
        var huge = "data: " + "x".repeat(SseEventReader.MAX_EVENT_CHARS + 1) + "\n\n";
        assertThrows(IOException.class, () -> reader(huge).next());
    }

    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @Test
    public void returnsAnEventBeforeTheStreamEnds() throws Exception {
        var chunks = new LinkedBlockingQueue<byte[]>();
        chunks.add("data: first\n\n".getBytes(StandardCharsets.UTF_8));
        try (var r = new SseEventReader(new BlockingStream(chunks))) {
            // The stream stays open: the first event must come back without
            // waiting for the end, as on a held GET stream.
            assertEquals(Optional.of("first"), r.next());
            chunks.add("data: second\n\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(Optional.of("second"), r.next());
            chunks.add(new byte[0]);
            assertEquals(Optional.empty(), r.next());
        }
    }

    /** Hands out queued chunks one read at a time and blocks between them; an empty chunk is EOF. */
    private static final class BlockingStream extends InputStream {
        private final BlockingQueue<byte[]> chunks;

        BlockingStream(BlockingQueue<byte[]> chunks) {
            this.chunks = chunks;
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException("read(byte[], int, int) only");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            byte[] chunk;
            try {
                chunk = chunks.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (chunk.length == 0) {
                return -1;
            }
            if (chunk.length > length) {
                throw new IllegalStateException("test chunk larger than the read buffer");
            }
            System.arraycopy(chunk, 0, buffer, offset, chunk.length);
            return chunk.length;
        }
    }
}
