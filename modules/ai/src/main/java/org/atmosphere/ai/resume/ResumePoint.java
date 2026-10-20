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
package org.atmosphere.ai.resume;

import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a reconnecting client last received of each AI session it followed:
 * the {@code seq} of the last frame of the session it holds. A client sends it
 * on its reconnect as {@value #HEADER} (a request header, or a query parameter
 * of the same name), {@code sessionId:seq} pairs separated by commas, for
 * instance
 * {@code X-Atmosphere-Resume=4f1c...:12,9a0e...:5}.
 *
 * <p>The server resends from there what the client missed of a run: the
 * frames of the session written after that {@code seq} — written into a
 * connection whose loss the server had not noticed yet, kept by no cache —
 * and, in a {@code broadcastReply} room, the frames of the room's later turns.
 * A frame it already holds is not written again.</p>
 *
 * <p>The value is checked before anything is read from it (Correctness
 * Invariant #4): at most {@value #MAX_SESSIONS} pairs and
 * {@value #MAX_LENGTH} characters, a session id of letters, digits, {@code -}
 * and {@code _} (at most 64 of them), a {@code seq} of at most 18 digits. A
 * value that breaks any of these is ignored whole, as a reconnect without
 * one: a client that sends none (an older one) is served as before. A session
 * id names nothing but a session the client received frames of; one the
 * server does not know, or a run the caller may not reattach to, yields
 * nothing.</p>
 *
 * @param sessions session id to the {@code seq} of the last frame of it the
 *                 client received, in the order the client listed them
 */
public record ResumePoint(Map<String, Long> sessions) {

    /** The request header (or query parameter) carrying a client's resume point. */
    public static final String HEADER = "X-Atmosphere-Resume";

    /** The most sessions a resume point names. */
    public static final int MAX_SESSIONS = 16;

    /** The longest value accepted, in characters. */
    public static final int MAX_LENGTH = MAX_SESSIONS * (64 + 1 + 18) + MAX_SESSIONS - 1;

    private static final Pattern PAIR = Pattern.compile("([A-Za-z0-9_-]{1,64}):([0-9]{1,18})");

    private static final Logger logger = LoggerFactory.getLogger(ResumePoint.class);

    public ResumePoint {
        sessions = Collections.unmodifiableMap(new LinkedHashMap<>(sessions));
    }

    /**
     * The {@code seq} of the last frame of {@code sessionId} the client
     * received, or {@code -1} when it named none.
     */
    public long lastSeq(String sessionId) {
        var seq = sessionId != null ? sessions.get(sessionId) : null;
        return seq != null ? seq : -1;
    }

    /**
     * Parse {@code value}, the {@value #HEADER} a client sent.
     *
     * @return the resume point, or empty when there is none or it is malformed
     */
    public static Optional<ResumePoint> parse(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        if (value.length() > MAX_LENGTH) {
            logger.debug("Ignoring a {} of {} characters (at most {})", HEADER, value.length(), MAX_LENGTH);
            return Optional.empty();
        }
        var pairs = value.split(",", -1);
        if (pairs.length > MAX_SESSIONS) {
            logger.debug("Ignoring a {} naming {} sessions (at most {})", HEADER, pairs.length, MAX_SESSIONS);
            return Optional.empty();
        }
        var sessions = new LinkedHashMap<String, Long>();
        for (var pair : pairs) {
            var matcher = PAIR.matcher(pair);
            if (!matcher.matches()) {
                logger.debug("Ignoring a malformed {}", HEADER);
                return Optional.empty();
            }
            sessions.put(matcher.group(1), Long.parseLong(matcher.group(2)));
        }
        return Optional.of(new ResumePoint(sessions));
    }

    /**
     * The resume point the request of {@code resource} carries ({@value #HEADER}),
     * or empty when it carries none, a malformed one, or the request is gone.
     */
    public static Optional<ResumePoint> of(AtmosphereResource resource) {
        if (resource == null) {
            return Optional.empty();
        }
        try {
            var request = resource.getRequest();
            return request != null ? parse(request.getHeader(HEADER)) : Optional.empty();
        } catch (RuntimeException e) {
            logger.trace("Unable to read the {} of {}", HEADER, resource.uuid(), e);
            return Optional.empty();
        }
    }
}
