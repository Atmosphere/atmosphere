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

import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartDeserializerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void acceptsLegacyTypeDiscriminator() throws Exception {
        // Pre-1.0 A2A drafts (and our own emitter) use "type".
        Part part = MAPPER.readValue("{\"type\":\"text\",\"text\":\"hi\"}", Part.class);
        assertInstanceOf(Part.TextPart.class, part);
        assertEquals("hi", ((Part.TextPart) part).text());
    }

    @Test
    void acceptsCurrentKindDiscriminator() throws Exception {
        // Regression: current A2A spec uses "kind" — server used to drop the
        // payload silently because Jackson couldn't find the discriminator.
        Part part = MAPPER.readValue("{\"kind\":\"text\",\"text\":\"hi\"}", Part.class);
        assertInstanceOf(Part.TextPart.class, part);
        assertEquals("hi", ((Part.TextPart) part).text());
    }

    @Test
    void filePartAcceptsKind() throws Exception {
        String json = "{\"kind\":\"file\",\"name\":\"report.pdf\","
                + "\"mimeType\":\"application/pdf\",\"uri\":\"https://example.com/r.pdf\"}";
        Part part = MAPPER.readValue(json, Part.class);
        assertInstanceOf(Part.FilePart.class, part);
        assertEquals("report.pdf", ((Part.FilePart) part).name());
        assertEquals("application/pdf", ((Part.FilePart) part).mimeType());
    }

    @Test
    void dataPartAcceptsKind() throws Exception {
        String json = "{\"kind\":\"data\",\"data\":{\"score\":42}}";
        Part part = MAPPER.readValue(json, Part.class);
        assertInstanceOf(Part.DataPart.class, part);
        assertEquals(42, ((Part.DataPart) part).data().get("score"));
    }

    @Test
    void typeWinsOverKindWhenBothPresent() throws Exception {
        // Defensive: if both are present, trust "type" (the field we emit and
        // that the server authoritatively sets). A mismatched client sending
        // {"type":"text","kind":"data",...} is rare but should not produce a
        // surprise DataPart.
        Part part = MAPPER.readValue(
                "{\"type\":\"text\",\"kind\":\"data\",\"text\":\"hi\"}", Part.class);
        assertInstanceOf(Part.TextPart.class, part);
    }

    @Test
    void emptyObjectFailsWithHelpfulMessage() {
        var ex = assertThrows(DatabindException.class,
                () -> MAPPER.readValue("{}", Part.class));
        assertEquals(true,
                ex.getOriginalMessage().contains("discriminator")
                        || ex.getOriginalMessage().contains("type")
                        || ex.getOriginalMessage().contains("kind"));
    }

    @Test
    void unknownKindFailsWithHelpfulMessage() {
        var ex = assertThrows(DatabindException.class,
                () -> MAPPER.readValue("{\"kind\":\"video\",\"src\":\"x\"}", Part.class));
        assertEquals(true, ex.getOriginalMessage().contains("video"));
    }
}
