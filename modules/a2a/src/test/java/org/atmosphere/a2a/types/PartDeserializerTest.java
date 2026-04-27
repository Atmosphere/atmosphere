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
package org.atmosphere.a2a.types;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link PartDeserializer} parses both the v1.0.0 spec shape
 * (top-level text/raw/url/data fields) and the pre-1.0 polymorphic shape
 * ({@code "type":"text"} / {@code "kind":"text"}, {@code mimeType},
 * {@code uri}, {@code bytes}).
 */
class PartDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void v1TextShape() throws Exception {
        var p = mapper.readValue("{\"text\":\"hi\"}", Part.class);
        assertEquals("hi", p.text());
    }

    @Test
    void v1DataShape() throws Exception {
        var p = mapper.readValue("{\"data\":{\"score\":42}}", Part.class);
        assertEquals(42, p.data().get("score"));
    }

    @Test
    void v1UrlShape() throws Exception {
        var p = mapper.readValue("{\"url\":\"https://example.com/a\"}", Part.class);
        assertEquals("https://example.com/a", p.url());
    }

    @Test
    void legacyTypeDiscriminatorText() throws Exception {
        var p = mapper.readValue("{\"type\":\"text\",\"text\":\"hi\"}", Part.class);
        assertEquals("hi", p.text());
    }

    @Test
    void legacyKindDiscriminatorText() throws Exception {
        var p = mapper.readValue("{\"kind\":\"text\",\"text\":\"hi\"}", Part.class);
        assertEquals("hi", p.text());
    }

    @Test
    void legacyFileShapeNormalizesMimeAndUri() throws Exception {
        var p = mapper.readValue(
                "{\"type\":\"file\",\"name\":\"a.pdf\","
                        + "\"mimeType\":\"application/pdf\",\"uri\":\"file:///a.pdf\"}",
                Part.class);
        assertEquals("a.pdf", p.filename());
        assertEquals("application/pdf", p.mediaType());
        assertEquals("file:///a.pdf", p.url());
    }

    @Test
    void legacyFileShapeWithBytes() throws Exception {
        // base64("hi") = "aGk="
        var p = mapper.readValue(
                "{\"type\":\"file\",\"name\":\"x\",\"bytes\":\"aGk=\"}",
                Part.class);
        assertArrayEquals(new byte[]{'h', 'i'}, p.raw());
    }

    @Test
    void legacyDataShape() throws Exception {
        var p = mapper.readValue("{\"type\":\"data\",\"data\":{\"k\":\"v\"}}", Part.class);
        assertEquals("v", p.data().get("k"));
    }

    @Test
    void unknownLegacyKindRejected() {
        assertThrows(Exception.class, () ->
                mapper.readValue("{\"type\":\"frobnicate\"}", Part.class));
    }

    @Test
    void emptyObjectRejected() {
        assertThrows(Exception.class, () ->
                mapper.readValue("{}", Part.class));
    }
}
