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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The WebTransport bidi stream message framing, shared by the server's
 * inbound scanner and mirrored by the atmosphere.js client:
 *
 * <ul>
 *   <li><b>Text</b> — UTF-8 bytes terminated by {@code 0x0A}.</li>
 *   <li><b>Binary</b> — {@code 0x00} marker + 4-byte big-endian length +
 *       exact payload bytes. UTF-8 text never begins with NUL, so the
 *       marker is unambiguous at frame boundaries.</li>
 * </ul>
 *
 * <p>Binary payloads must never pass through the text decode/split path —
 * splitting on a payload's {@code 0x0A} bytes and UTF-8 decoding silently
 * corrupted them (Boundary Safety, Invariant #4).</p>
 */
final class BidiFraming {

    /** First byte of a length-prefixed binary frame. */
    static final byte BINARY_FRAME_MARKER = 0x00;

    /** A complete frame drained from the accumulator. */
    sealed interface Frame {
        record Text(String message) implements Frame { }

        record Binary(byte[] payload) implements Frame { }
    }

    /** Drain outcome: the complete frames, or {@code corrupt} on a bound violation. */
    record DrainResult(List<Frame> frames, boolean corrupt) {
    }

    private BidiFraming() {
    }

    /**
     * Drain every complete frame from {@code accumulator}, leaving partial
     * trailing bytes buffered for the next read. A binary frame whose
     * declared length is negative or exceeds {@code maxFrameBytes} marks the
     * stream corrupt — the caller must drop the buffer.
     */
    static DrainResult drain(CompositeByteBuf accumulator, int maxFrameBytes) {
        var frames = new ArrayList<Frame>();
        while (accumulator.isReadable()) {
            if (accumulator.getByte(accumulator.readerIndex()) == BINARY_FRAME_MARKER) {
                if (accumulator.readableBytes() < 5) {
                    break; // wait for the full binary header
                }
                int length = accumulator.getInt(accumulator.readerIndex() + 1);
                if (length < 0 || length > maxFrameBytes) {
                    return new DrainResult(List.copyOf(frames), true);
                }
                if (accumulator.readableBytes() < 5 + length) {
                    break; // wait for the full payload
                }
                accumulator.skipBytes(5);
                var payload = new byte[length];
                accumulator.readBytes(payload);
                accumulator.discardReadBytes();
                frames.add(new Frame.Binary(payload));
                continue;
            }
            int nlIdx = findNewline(accumulator);
            if (nlIdx < 0) {
                break; // wait for the rest of the text frame
            }
            var frameBytes = new byte[nlIdx];
            accumulator.readBytes(frameBytes);
            accumulator.readByte(); // consume the newline
            accumulator.discardReadBytes();
            var message = new String(frameBytes, StandardCharsets.UTF_8);
            if (!message.isEmpty()) {
                frames.add(new Frame.Text(message));
            }
        }
        return new DrainResult(List.copyOf(frames), false);
    }

    private static int findNewline(ByteBuf buf) {
        for (int i = buf.readerIndex(); i < buf.writerIndex(); i++) {
            if (buf.getByte(i) == '\n') {
                return i - buf.readerIndex();
            }
        }
        return -1;
    }
}
