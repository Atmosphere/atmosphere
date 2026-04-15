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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textPartWithTextAndMetadata() {
        var meta = Map.<String, Object>of("key", "value");
        var part = new Part.TextPart("hello", meta);
        assertEquals("hello", part.text());
        assertEquals("value", part.metadata().get("key"));
    }

    @Test
    void textPartConvenienceConstructor() {
        var part = new Part.TextPart("simple");
        assertEquals("simple", part.text());
        assertNotNull(part.metadata());
        assertTrue(part.metadata().isEmpty());
    }

    @Test
    void filePartWithAllFields() {
        byte[] data = {1, 2, 3};
        var meta = Map.<String, Object>of("size", 3);
        var part = new Part.FilePart("test.txt", "text/plain", "file:///test.txt", data, meta);
        assertEquals("test.txt", part.name());
        assertEquals("text/plain", part.mimeType());
        assertEquals("file:///test.txt", part.uri());
        assertArrayEquals(new byte[]{1, 2, 3}, part.bytes());
        assertEquals(3, part.metadata().get("size"));
    }

    @Test
    void filePartConvenienceConstructor() {
        var part = new Part.FilePart("doc.pdf", "application/pdf", "https://example.com/doc.pdf");
        assertEquals("doc.pdf", part.name());
        assertEquals("application/pdf", part.mimeType());
        assertEquals("https://example.com/doc.pdf", part.uri());
        assertNull(part.bytes());
        assertTrue(part.metadata().isEmpty());
    }

    @Test
    void dataPartWithDataAndMetadata() {
        var data = Map.<String, Object>of("key", "val");
        var meta = Map.<String, Object>of("source", "test");
        var part = new Part.DataPart(data, meta);
        assertEquals("val", part.data().get("key"));
        assertEquals("test", part.metadata().get("source"));
    }

    @Test
    void dataPartNullDataDefaultsToEmptyMap() {
        var part = new Part.DataPart(null, null);
        assertNotNull(part.data());
        assertTrue(part.data().isEmpty());
    }

    @Test
    void dataPartNullMetadataDefaultsToEmptyMap() {
        var part = new Part.DataPart(Map.of("k", "v"), null);
        assertNotNull(part.metadata());
        assertTrue(part.metadata().isEmpty());
    }

    @Test
    void dataPartDataMapIsUnmodifiable() {
        var part = new Part.DataPart(Map.of("k", "v"), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> part.data().put("new", "val"));
    }

    @Test
    void dataPartMetadataMapIsUnmodifiable() {
        var part = new Part.DataPart(Map.of(), Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> part.metadata().put("new", "val"));
    }

    @Test
    void jsonTextPartHasTypeDiscriminator() throws Exception {
        var part = new Part.TextPart("hello");
        String json = mapper.writeValueAsString(part);
        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"text\":\"hello\""));
    }

    @Test
    void jsonFilePartHasTypeDiscriminator() throws Exception {
        var part = new Part.FilePart("f.txt", "text/plain", "http://example.com/f.txt");
        String json = mapper.writeValueAsString(part);
        assertTrue(json.contains("\"type\":\"file\""));
        assertTrue(json.contains("\"name\":\"f.txt\""));
    }

    @Test
    void jsonDataPartHasTypeDiscriminator() throws Exception {
        var part = new Part.DataPart(Map.of("answer", 42), Map.of());
        String json = mapper.writeValueAsString(part);
        assertTrue(json.contains("\"type\":\"data\""));
    }

    @Test
    void jsonDeserializationTextPart() throws Exception {
        String json = "{\"type\":\"text\",\"text\":\"deserialized\",\"metadata\":{}}";
        Part part = mapper.readValue(json, Part.class);
        assertInstanceOf(Part.TextPart.class, part);
        assertEquals("deserialized", ((Part.TextPart) part).text());
    }

    @Test
    void jsonDeserializationFilePart() throws Exception {
        String json = "{\"type\":\"file\",\"name\":\"a.txt\",\"mimeType\":\"text/plain\","
                + "\"uri\":\"http://example.com\",\"metadata\":{}}";
        Part part = mapper.readValue(json, Part.class);
        assertInstanceOf(Part.FilePart.class, part);
        assertEquals("a.txt", ((Part.FilePart) part).name());
    }

    @Test
    void jsonDeserializationDataPart() throws Exception {
        String json = "{\"type\":\"data\",\"data\":{\"k\":\"v\"},\"metadata\":{}}";
        Part part = mapper.readValue(json, Part.class);
        assertInstanceOf(Part.DataPart.class, part);
        assertEquals("v", ((Part.DataPart) part).data().get("k"));
    }
}
