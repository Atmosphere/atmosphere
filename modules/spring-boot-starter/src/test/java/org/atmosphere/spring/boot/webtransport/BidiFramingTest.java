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
package org.atmosphere.spring.boot.webtransport;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#15): the bidi stream used to UTF-8 decode and
 * newline-split EVERYTHING, silently corrupting binary payloads (a 0x0A
 * byte split the payload; non-UTF-8 bytes decoded to replacement
 * characters). Binary frames now travel length-prefixed behind a 0x00
 * marker and must survive byte-for-byte, however the transport slices
 * the delivery.
 */
class BidiFramingTest {

    private static final int MAX = 1_048_576;

    private final CompositeByteBuf accumulator = Unpooled.compositeBuffer();

    @AfterEach
    void tearDown() {
        if (accumulator.refCnt() > 0) {
            accumulator.release();
        }
    }

    private List<BidiFraming.Frame> feed(byte[] data, int sliceSize) {
        var frames = new ArrayList<BidiFraming.Frame>();
        for (int i = 0; i < data.length; i += sliceSize) {
            var end = Math.min(data.length, i + sliceSize);
            accumulator.addComponent(true,
                    Unpooled.wrappedBuffer(data, i, end - i).retain());
            var result = BidiFraming.drain(accumulator, MAX);
            assertFalse(result.corrupt(), "well-formed input must never read as corrupt");
            frames.addAll(result.frames());
        }
        return frames;
    }

    private static byte[] binaryFrame(byte[] payload) {
        return ByteBuffer.allocate(5 + payload.length)
                .put((byte) 0)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    @Test
    void binaryPayloadWithNewlinesAndInvalidUtf8SurvivesByteForByte() {
        // 0x0A would have split it; 0xFF/0x80 would have decoded to U+FFFD.
        var payload = new byte[] {0x0A, (byte) 0xFF, (byte) 0x80, 0x00, 0x0A, 0x42};

        var frames = feed(binaryFrame(payload), 1); // worst-case: 1 byte per read

        assertEquals(1, frames.size(), "one complete binary frame: " + frames);
        assertArrayEquals(payload,
                ((BidiFraming.Frame.Binary) frames.get(0)).payload(),
                "binary payloads must survive the transport untouched");
    }

    @Test
    void textAndBinaryFramesInterleaveOnOneStream() {
        var text1 = "hello\n".getBytes(StandardCharsets.UTF_8);
        var payload = new byte[] {1, 2, 0x0A, 3};
        var text2 = "wörld\n".getBytes(StandardCharsets.UTF_8);
        var wire = ByteBuffer.allocate(text1.length + 5 + payload.length + text2.length)
                .put(text1).put(binaryFrame(payload)).put(text2).array();

        var frames = feed(wire, 3);

        assertEquals(3, frames.size(), String.valueOf(frames));
        assertEquals("hello", ((BidiFraming.Frame.Text) frames.get(0)).message());
        assertArrayEquals(payload, ((BidiFraming.Frame.Binary) frames.get(1)).payload());
        assertEquals("wörld", ((BidiFraming.Frame.Text) frames.get(2)).message());
    }

    @Test
    void partialBinaryHeaderWaitsInsteadOfDecodingAsText() {
        accumulator.addComponent(true, Unpooled.wrappedBuffer(new byte[] {0, 0, 0}).retain());
        var result = BidiFraming.drain(accumulator, MAX);

        assertFalse(result.corrupt());
        assertTrue(result.frames().isEmpty(),
                "an incomplete binary header must buffer, not decode as text");
        assertEquals(3, accumulator.readableBytes());
    }

    @Test
    void oversizedDeclaredLengthMarksTheStreamCorrupt() {
        var header = ByteBuffer.allocate(5).put((byte) 0).putInt(MAX + 1).array();
        accumulator.addComponent(true, Unpooled.wrappedBuffer(header).retain());

        assertTrue(BidiFraming.drain(accumulator, MAX).corrupt(),
                "a length beyond the frame cap is a DoS vector and must fail closed");
    }

    @Test
    void multiByteUtf8TextSplitAcrossReadsDecodesIntact() {
        var frames = feed("caractères accentués\n".getBytes(StandardCharsets.UTF_8), 1);

        assertEquals(1, frames.size());
        assertEquals("caractères accentués",
                ((BidiFraming.Frame.Text) frames.get(0)).message(),
                "text frames decode only when complete, so split UTF-8 stays intact");
    }
}
