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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Incremental reader of a {@code text/event-stream} body: returns each event's
 * data as soon as the blank line that ends the event arrives, so a stream that
 * stays open delivers events one by one instead of when it closes.
 *
 * <p>Follows the SSE format: lines end in LF, CR or CRLF; a {@code data:} field
 * loses one leading space; several {@code data:} lines join with LF; comment
 * lines ({@code :}) and the {@code id}, {@code retry} fields are ignored; an
 * event with no data is not dispatched; an event whose {@code event:} type is
 * not {@code message} is skipped, as MCP sends every JSON-RPC message as a
 * {@code message} event. An event cut off by the end of the stream is
 * discarded, as the format requires.</p>
 */
public final class SseEventReader implements AutoCloseable {

    /** Longest event the reader accepts; a longer one ends the stream with an error. */
    static final int MAX_EVENT_CHARS = 16 * 1024 * 1024;

    private final BufferedReader reader;

    public SseEventReader(InputStream in) {
        this(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public SseEventReader(Reader in) {
        this.reader = in instanceof BufferedReader buffered ? buffered : new BufferedReader(in);
    }

    /**
     * The data of the next {@code message} event, or empty at the end of the stream.
     *
     * @throws IOException on a read failure or an event over {@link #MAX_EVENT_CHARS}
     */
    public Optional<String> next() throws IOException {
        var data = new StringBuilder();
        var hasData = false;
        String type = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (hasData && (type == null || type.equals("message"))) {
                    return Optional.of(data.toString());
                }
                data.setLength(0);
                hasData = false;
                type = null;
                continue;
            }
            if (line.charAt(0) == ':') {
                continue;
            }
            var colon = line.indexOf(':');
            var field = colon < 0 ? line : line.substring(0, colon);
            var value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "data" -> {
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                    if (data.length() > MAX_EVENT_CHARS) {
                        throw new IOException("SSE event exceeds " + MAX_EVENT_CHARS + " characters");
                    }
                }
                case "event" -> type = value;
                default -> {
                    // id, retry and unknown fields carry nothing the bridge relays.
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
