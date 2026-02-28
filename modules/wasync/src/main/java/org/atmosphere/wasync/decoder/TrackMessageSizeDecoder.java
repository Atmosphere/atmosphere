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
package org.atmosphere.wasync.decoder;

import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Event;

/**
 * Decoder that handles Atmosphere's message-length protocol.
 *
 * <p>When {@code trackMessageLength} is enabled on the server, messages are sent in the
 * format: {@code <length><delimiter><message>}. This decoder parses the length prefix,
 * buffers partial messages, and emits complete messages.</p>
 */
public class TrackMessageSizeDecoder implements Decoder<String, String> {

    private final String delimiter;
    private final StringBuilder buffer = new StringBuilder();
    private int expectedLength = -1;
    private boolean skipFirst;

    /**
     * Create a decoder with the default delimiter ("|").
     */
    public TrackMessageSizeDecoder() {
        this("|", false);
    }

    /**
     * Create a decoder with a custom delimiter.
     *
     * @param delimiter the delimiter between length and message
     * @param skipFirst whether to skip the first message (protocol handshake)
     */
    public TrackMessageSizeDecoder(String delimiter, boolean skipFirst) {
        this.delimiter = delimiter;
        this.skipFirst = skipFirst;
    }

    @Override
    public String decode(Event event, String message) {
        if (event != Event.MESSAGE || message == null) {
            return message;
        }

        buffer.append(message);

        return extractMessage();
    }

    private String extractMessage() {
        var content = buffer.toString();

        if (expectedLength == -1) {
            int delimIdx = content.indexOf(delimiter);
            if (delimIdx == -1) {
                return null;
            }

            try {
                expectedLength = Integer.parseInt(content.substring(0, delimIdx).strip());
            } catch (NumberFormatException e) {
                // Not a tracked message, return as-is
                buffer.setLength(0);
                return content;
            }

            content = content.substring(delimIdx + delimiter.length());
            buffer.setLength(0);
            buffer.append(content);
        }

        if (buffer.length() >= expectedLength) {
            var msg = buffer.substring(0, expectedLength);
            var remaining = buffer.substring(expectedLength);
            buffer.setLength(0);
            buffer.append(remaining);
            expectedLength = -1;

            if (skipFirst) {
                skipFirst = false;
                // Try to extract the next message if there is remaining data
                if (!buffer.isEmpty()) {
                    return extractMessage();
                }
                return null;
            }

            return msg;
        }

        // Still waiting for more data
        return null;
    }
}
