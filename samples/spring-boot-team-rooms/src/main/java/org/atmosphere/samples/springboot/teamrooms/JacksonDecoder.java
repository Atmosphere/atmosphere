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
package org.atmosphere.samples.springboot.teamrooms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.atmosphere.config.managed.Decoder;

import tools.jackson.databind.ObjectMapper;

/**
 * Turns what a client sent into a {@link Message}. Three shapes reach a room:
 * <ul>
 *   <li>{@code {"author","text"}} — this sample's own format;</li>
 *   <li>{@code {"author","message","time"}} — the Atmosphere Console's broadcast envelope,
 *       the sample's UI;</li>
 *   <li>a plain string — a bare WebSocket client, or the Console before it learns the
 *       endpoint is a broadcast room.</li>
 * </ul>
 * The room and the timestamp are never taken from the client: the path and the server
 * assign them in {@link Message#stamped(String)}.
 */
public class JacksonDecoder implements Decoder<String, Message> {

    static final String ANONYMOUS = "anonymous";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Message decode(String s) {
        if (!s.stripLeading().startsWith("{")) {
            return new Message(null, ANONYMOUS, s, null);
        }
        Inbound in = mapper.readValue(s, Inbound.class);
        String author = in.author() == null || in.author().isBlank() ? ANONYMOUS : in.author();
        return new Message(null, author, in.text() != null ? in.text() : in.message(), null);
    }

    /** The fields a client may set; anything else it sends (room, at, time) is ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Inbound(String author, String text, String message) {
    }
}
