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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for the HTTP Capsule Protocol (RFC 9297) that a WebTransport client
 * speaks on the session's extended-CONNECT stream. Each capsule is a QUIC
 * varint type, a QUIC varint length and {@code length} value bytes, carried in
 * the stream's HTTP/3 DATA frames. Application messages never travel on that
 * stream (they use the WebTransport bidi stream), so its bytes are capsules
 * and must never reach the application as text (Invariant #4).
 */
final class WebTransportCapsules {

    /** {@code CLOSE_WEBTRANSPORT_SESSION}: 4-byte error code + UTF-8 reason. */
    static final long CLOSE_WEBTRANSPORT_SESSION = 0x2843L;
    /** {@code DRAIN_WEBTRANSPORT_SESSION}: empty value, the peer asks for a graceful wind-down. */
    static final long DRAIN_WEBTRANSPORT_SESSION = 0x78aeL;
    /**
     * Largest capsule value accepted. A CLOSE reason is capped at 1024 bytes
     * and a DATAGRAM capsule at the 65536-byte datagram size this server
     * negotiates, so anything larger is a protocol violation.
     */
    static final int MAX_CAPSULE_VALUE_BYTES = 65_536;

    /** One decoded capsule. */
    record Capsule(long type, byte[] value) {

        /** The application error code of a CLOSE capsule, or 0 when absent. */
        long closeErrorCode() {
            if (value.length < 4) {
                return 0;
            }
            return ((value[0] & 0xFFL) << 24) | ((value[1] & 0xFFL) << 16)
                    | ((value[2] & 0xFFL) << 8) | (value[3] & 0xFFL);
        }

        /** The UTF-8 reason of a CLOSE capsule, or the empty string. */
        String closeReason() {
            if (value.length <= 4) {
                return "";
            }
            return new String(value, 4, value.length - 4, StandardCharsets.UTF_8);
        }
    }

    /**
     * Result of draining an accumulator: the complete capsules, and whether
     * a capsule declared a value larger than {@link #MAX_CAPSULE_VALUE_BYTES}.
     */
    record Drained(List<Capsule> capsules, boolean corrupt) {
    }

    private WebTransportCapsules() {
    }

    /**
     * Consume every complete capsule from {@code in}, leaving the bytes of an
     * incomplete trailing capsule unread for the next call. A capsule whose
     * declared length exceeds {@code maxValueBytes} stops the drain with
     * {@code corrupt = true}; its bytes stay unread.
     */
    static Drained drain(ByteBuf in, int maxValueBytes) {
        var capsules = new ArrayList<Capsule>();
        while (in.isReadable()) {
            int start = in.readerIndex();
            int available = in.readableBytes();
            int typeLength = varintLength(in.getUnsignedByte(start));
            if (available < typeLength + 1) {
                break;
            }
            int lengthLength = varintLength(in.getUnsignedByte(start + typeLength));
            int headerLength = typeLength + lengthLength;
            if (available < headerLength) {
                break;
            }
            long type = readVarint(in, start, typeLength);
            long valueLength = readVarint(in, start + typeLength, lengthLength);
            if (valueLength > maxValueBytes) {
                return new Drained(capsules, true);
            }
            if (available < headerLength + valueLength) {
                break;
            }
            var value = new byte[(int) valueLength];
            in.getBytes(start + headerLength, value);
            in.skipBytes(headerLength + (int) valueLength);
            capsules.add(new Capsule(type, value));
        }
        return new Drained(capsules, false);
    }

    /** Total length (1, 2, 4 or 8 bytes) of the QUIC varint whose first byte is given. */
    static int varintLength(int firstByte) {
        return 1 << (firstByte >> 6);
    }

    private static long readVarint(ByteBuf in, int index, int length) {
        long value = in.getUnsignedByte(index) & 0x3F;
        for (int i = 1; i < length; i++) {
            value = (value << 8) | in.getUnsignedByte(index + i);
        }
        return value;
    }
}
