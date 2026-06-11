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

/**
 * Stateful, spec-compliant parser for a Server-Sent Events stream
 * (WHATWG HTML "event stream interpretation"). Fed one line at a time; emits a
 * {@link Frame} when a blank line terminates an event.
 *
 * <p>Implements the fields the {@code SSETransport} previously dropped:</p>
 * <ul>
 *   <li>{@code data:} — multiple {@code data:} lines in one event are joined
 *       with {@code \n} (a single line never was, but multi-line events were
 *       silently concatenated without the separator — a real corruption bug
 *       this parser fixes);</li>
 *   <li>{@code event:} — the event name, surfaced on the frame;</li>
 *   <li>{@code id:} — becomes the {@linkplain #lastEventId() last event id},
 *       which persists across events and is sent as {@code Last-Event-ID} on
 *       reconnect (ignoring values containing a NUL, per spec);</li>
 *   <li>{@code retry:} — an integer reconnection time in milliseconds, which
 *       also persists;</li>
 *   <li>lines beginning with {@code :} are comments and ignored;</li>
 *   <li>a field with no colon is treated as that field name with an empty
 *       value, per spec.</li>
 * </ul>
 */
final class SseFrameParser {

    /**
     * One dispatched SSE event.
     *
     * @param event       the {@code event:} name, or {@code null} (default "message")
     * @param data        the joined {@code data:} payload with the trailing newline stripped
     * @param lastEventId the most recent {@code id:} seen up to and including this event
     * @param retryMillis the most recent {@code retry:} value, or {@code null}
     */
    record Frame(String event, String data, String lastEventId, Long retryMillis) {
    }

    private final StringBuilder data = new StringBuilder();
    private String event;
    private boolean hasContent;

    // lastEventId and retry persist across events for the lifetime of the stream.
    private String lastEventId;
    private Long retryMillis;

    /**
     * Feed one line. Returns a {@link Frame} when {@code line} is the blank
     * line that terminates an event with content; otherwise {@code null}.
     */
    Frame accept(String line) {
        if (line.isEmpty()) {
            return dispatch();
        }
        if (line.charAt(0) == ':') {
            return null; // comment
        }

        String field;
        String value;
        var colon = line.indexOf(':');
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            value = line.substring(colon + 1);
            if (!value.isEmpty() && value.charAt(0) == ' ') {
                value = value.substring(1); // strip a single leading space
            }
        }

        switch (field) {
            case "data" -> {
                data.append(value).append('\n');
                hasContent = true;
            }
            case "event" -> {
                event = value;
                hasContent = true;
            }
            case "id" -> {
                if (value.indexOf('\0') < 0) {
                    lastEventId = value;
                }
            }
            case "retry" -> {
                try {
                    retryMillis = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // per spec, a non-integer retry is ignored
                }
            }
            default -> {
                // unknown field — ignored per spec
            }
        }
        return null;
    }

    private Frame dispatch() {
        if (!hasContent) {
            return null; // blank line with nothing buffered — nothing to emit
        }
        var payload = data.length() > 0 && data.charAt(data.length() - 1) == '\n'
                ? data.substring(0, data.length() - 1)
                : data.toString();
        var frame = new Frame(event, payload, lastEventId, retryMillis);
        data.setLength(0);
        event = null;
        hasContent = false;
        return frame;
    }

    /** The most recent {@code id:} value, for the {@code Last-Event-ID} reconnect header. */
    String lastEventId() {
        return lastEventId;
    }

    /** The most recent {@code retry:} reconnection time in millis, or {@code null}. */
    Long retryMillis() {
        return retryMillis;
    }
}
