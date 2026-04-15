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
package org.atmosphere.ai.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnSessionCloseStrategyTest {

    private final OnSessionCloseStrategy strategy = new OnSessionCloseStrategy();

    @Test
    void shouldExtractAlwaysReturnsFalse() {
        assertFalse(strategy.shouldExtract("conv-1", "hello", 0));
        assertFalse(strategy.shouldExtract("conv-1", "hello", 1));
        assertFalse(strategy.shouldExtract("conv-1", "hello", 100));
        assertFalse(strategy.shouldExtract(null, null, 0));
    }

    @Test
    void parseJsonArrayReturnsEmptyForNull() {
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray(null));
    }

    @Test
    void parseJsonArrayReturnsEmptyForBlank() {
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray(""));
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray("   "));
    }

    @Test
    void parseJsonArrayReturnsEmptyForNonArrayInput() {
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray("not json"));
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray("{\"key\": \"value\"}"));
    }

    @Test
    void parseJsonArrayParsesSimpleArray() {
        var result = OnSessionCloseStrategy.parseJsonArray(
                "[\"fact one\", \"fact two\"]");
        assertEquals(List.of("fact one", "fact two"), result);
    }

    @Test
    void parseJsonArrayParsesEmptyArray() {
        assertEquals(List.of(), OnSessionCloseStrategy.parseJsonArray("[]"));
    }

    @Test
    void parseJsonArrayHandlesMarkdownCodeBlock() {
        var input = "```json\n[\"User likes Java\", \"Lives in Montreal\"]\n```";
        var result = OnSessionCloseStrategy.parseJsonArray(input);
        assertEquals(List.of("User likes Java", "Lives in Montreal"), result);
    }

    @Test
    void parseJsonArrayHandlesEscapedCharacters() {
        var input = "[\"She said \\\"hello\\\"\", \"path: C:\\\\Users\"]";
        var result = OnSessionCloseStrategy.parseJsonArray(input);
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("\\\"hello\\\""));
    }

    @Test
    void parseJsonArrayReturnsSingleElement() {
        var result = OnSessionCloseStrategy.parseJsonArray("[\"only one\"]");
        assertEquals(List.of("only one"), result);
    }

    @Test
    void parseJsonArrayHandlesWhitespaceAroundBrackets() {
        var result = OnSessionCloseStrategy.parseJsonArray(
                "  [\"a\", \"b\"]  ");
        assertEquals(List.of("a", "b"), result);
    }

    @Test
    void parseJsonArrayReturnsImmutableList() {
        var result = OnSessionCloseStrategy.parseJsonArray("[\"x\"]");
        assertThrows(UnsupportedOperationException.class, () -> result.add("y"));
    }

    @Test
    void parseJsonArrayHandlesNewlinesInContent() {
        var input = "[\n  \"fact one\",\n  \"fact two\"\n]";
        var result = OnSessionCloseStrategy.parseJsonArray(input);
        assertEquals(List.of("fact one", "fact two"), result);
    }
}
