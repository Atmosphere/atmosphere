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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the v1.0.0 single-record {@link Part} (text/raw/url/data oneof). */
class PartTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textFactoryProducesTextPart() {
        var p = Part.text("hello");
        assertEquals("hello", p.text());
        assertNull(p.raw());
        assertNull(p.url());
        assertNull(p.data());
    }

    @Test
    void rawFactoryProducesRawPart() {
        var bytes = new byte[]{1, 2, 3};
        var p = Part.raw(bytes, "doc.bin", "application/octet-stream");
        assertEquals(bytes, p.raw());
        assertEquals("doc.bin", p.filename());
        assertEquals("application/octet-stream", p.mediaType());
        assertNull(p.text());
    }

    @Test
    void urlFactoryProducesUrlPart() {
        var p = Part.url("https://example.com/file.pdf", "file.pdf", "application/pdf");
        assertEquals("https://example.com/file.pdf", p.url());
        assertEquals("file.pdf", p.filename());
        assertEquals("application/pdf", p.mediaType());
    }

    @Test
    void dataFactoryProducesDataPart() {
        var p = Part.data(Map.of("score", 95));
        assertEquals(95, p.data().get("score"));
        assertNull(p.text());
    }

    @Test
    void serializationOmitsNullFields() throws Exception {
        var p = Part.text("hi");
        var json = mapper.writeValueAsString(p);
        assertTrue(json.contains("\"text\":\"hi\""));
        assertFalse(json.contains("\"raw\""));
        assertFalse(json.contains("\"url\""));
        assertFalse(json.contains("\"data\""));
    }

    @Test
    void deserializationRoundTrip() throws Exception {
        var p = Part.text("round trip");
        var roundTrip = mapper.readValue(mapper.writeValueAsString(p), Part.class);
        assertEquals("round trip", roundTrip.text());
    }

    @Test
    void mediaTypeOnTextPartIsAllowed() {
        var p = Part.text("# heading", "text/markdown");
        assertEquals("text/markdown", p.mediaType());
        assertNotNull(p.text());
    }
}
