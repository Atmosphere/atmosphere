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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JacksonStructuredOutputParserTest {

    public record Person(String name, int age) {}

    public record SimpleRecord(String title, boolean active) {}

    private final JacksonStructuredOutputParser parser = new JacksonStructuredOutputParser();

    @Test
    void parseSimpleRecord() {
        var json = "{\"name\":\"Alice\",\"age\":30}";
        var person = parser.parse(json, Person.class);
        assertEquals("Alice", person.name());
        assertEquals(30, person.age());
    }

    @Test
    void parseWithMarkdownFences() {
        var input = "```json\n{\"name\":\"Bob\",\"age\":25}\n```";
        var person = parser.parse(input, Person.class);
        assertEquals("Bob", person.name());
        assertEquals(25, person.age());
    }

    @Test
    void parseWithSurroundingText() {
        var input = "Here is the result: {\"name\":\"Carol\",\"age\":40} hope that helps!";
        var person = parser.parse(input, Person.class);
        assertEquals("Carol", person.name());
        assertEquals(40, person.age());
    }

    @Test
    void parseInvalidJsonThrowsException() {
        assertThrows(StructuredOutputParser.StructuredOutputException.class,
                () -> parser.parse("not json at all", Person.class));
    }

    @Test
    void schemaInstructionsContainsJsonSchema() {
        var instructions = parser.schemaInstructions(Person.class);
        assertNotNull(instructions);
        assertFalse(instructions.isEmpty());
    }

    @Test
    void schemaInstructionsContainsFieldNames() {
        var instructions = parser.schemaInstructions(Person.class);
        assertFalse(instructions.indexOf("name") < 0);
        assertFalse(instructions.indexOf("age") < 0);
    }

    @Test
    void isAvailableReturnsTrue() {
        assertFalse(parser.isAvailable() == false);
    }

    @Test
    void extractJsonHandlesPlainJson() {
        var result = JacksonStructuredOutputParser.extractJson("{\"key\":\"val\"}");
        assertEquals("{\"key\":\"val\"}", result);
    }

    @Test
    void extractJsonHandlesMarkdownJsonFence() {
        var result = JacksonStructuredOutputParser.extractJson("```json\n{\"k\":\"v\"}\n```");
        assertEquals("{\"k\":\"v\"}", result);
    }

    @Test
    void extractJsonHandlesPlainMarkdownFence() {
        var result = JacksonStructuredOutputParser.extractJson("```\n{\"k\":\"v\"}\n```");
        assertEquals("{\"k\":\"v\"}", result);
    }

    @Test
    void extractJsonHandlesLeadingText() {
        var result = JacksonStructuredOutputParser.extractJson("output: {\"k\":\"v\"} done");
        assertEquals("{\"k\":\"v\"}", result);
    }

    @Test
    void parseFieldReturnsEmptyForBlankChunk() {
        var result = parser.parseField("   ", Person.class);
        assertFalse(result.isPresent());
    }

    @Test
    void parseFieldExtractsStringField() {
        var result = parser.parseField("\"name\":\"Alice\"", Person.class);
        assertFalse(result.isEmpty());
        assertEquals("name", result.get().getKey());
        assertEquals("Alice", result.get().getValue());
    }

    @Test
    void parseFieldExtractsNumericField() {
        var result = parser.parseField("\"age\":30", Person.class);
        assertFalse(result.isEmpty());
        assertEquals("age", result.get().getKey());
        assertEquals(30, result.get().getValue());
    }

    @Test
    void parseFieldReturnsEmptyForUnparseable() {
        var result = parser.parseField("partial...", Person.class);
        assertFalse(result.isPresent());
    }

    @Test
    void parseBooleanField() {
        var json = "{\"title\":\"Test\",\"active\":true}";
        var rec = parser.parse(json, SimpleRecord.class);
        assertEquals("Test", rec.title());
        assertEquals(true, rec.active());
    }
}
