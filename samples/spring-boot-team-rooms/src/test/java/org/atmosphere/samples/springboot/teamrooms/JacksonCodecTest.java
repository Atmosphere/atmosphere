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

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Atmosphere Console is this sample's UI, so a room must accept what the Console sends
 * and answer in a frame the Console renders. Before, the Console's {@code {author, message}}
 * envelope decoded to a line with no text, its plain-text dialect failed the decoder, and the
 * {@code {room, author, text, at}} reply had no {@code message} field, so nothing rendered.
 */
class JacksonCodecTest {

    private final JacksonDecoder decoder = new JacksonDecoder();
    private final JacksonEncoder encoder = new JacksonEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void decodesTheConsoleBroadcastEnvelope() {
        Message m = decoder.decode("{\"author\":\"console\",\"message\":\"hello\",\"time\":1760400000000}");
        assertEquals("console", m.author());
        assertEquals("hello", m.text());
    }

    @Test
    void decodesPlainTextAsAnAnonymousLine() {
        Message m = decoder.decode("hello");
        assertEquals(JacksonDecoder.ANONYMOUS, m.author());
        assertEquals("hello", m.text());
    }

    @Test
    void stillDecodesTheSampleOwnFormat() {
        Message m = decoder.decode("{\"author\":\"alice\",\"text\":\"standup in 5\"}");
        assertEquals("alice", m.author());
        assertEquals("standup in 5", m.text());
    }

    @Test
    void neverTakesTheRoomOrTimestampFromTheClient() {
        Message m = decoder.decode(
                "{\"author\":\"alice\",\"text\":\"hi\",\"room\":\"admins\",\"at\":\"2000-01-01T00:00:00Z\"}");
        assertNull(m.room());
        assertNull(m.at());
    }

    @Test
    void encodedFrameCarriesTheFieldTheConsoleRenders() {
        String json = encoder.encode(new Message("general", "alice", "hello", Instant.EPOCH));
        JsonNode node = mapper.readTree(json);
        assertEquals("hello", node.get("message").stringValue());
        assertEquals("alice", node.get("author").stringValue());
        assertEquals("hello", node.get("text").stringValue());
        assertEquals("general", node.get("room").stringValue());
    }
}
