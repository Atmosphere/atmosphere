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
package org.atmosphere.ai.prompt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PromptTemplateTest {

    private static final String TONE_KEY = PromptTemplate.VAR_PROPERTY_PREFIX + "tone";

    @AfterEach
    public void tearDown() {
        System.clearProperty(TONE_KEY);
    }

    @Test
    public void substitutesRepeatedAndWhitespacePaddedPlaceholders() {
        var rendered = PromptTemplate.render(
                "Hello {{name}}, welcome. Goodbye {{ name }}.", Map.of("name", "Alice"));
        assertEquals("Hello Alice, welcome. Goodbye Alice.", rendered);
    }

    @Test
    public void unresolvedVariableFailsClosedNamingTheVariable() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> PromptTemplate.render("Support {{product}} kindly.", Map.of()));
        assertTrue(thrown.getMessage().contains("product"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Refusing"), thrown.getMessage());
    }

    @Test
    public void collectsAllMissingVariablesInOneError() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> PromptTemplate.render("{{a}} and {{b}}", Map.of()));
        assertTrue(thrown.getMessage().contains("a"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("b"), thrown.getMessage());
    }

    @Test
    public void configDefaultSuppliesValueAndPerRequestMapWins() {
        System.setProperty(TONE_KEY, "friendly");
        assertEquals("Be friendly.", PromptTemplate.render("Be {{tone}}.", Map.of()));
        assertEquals("Be formal.", PromptTemplate.render("Be {{tone}}.", Map.of("tone", "formal")));
    }

    @Test
    public void textWithoutPlaceholdersPassesThrough() {
        var text = "No placeholders here, even with { single } braces.";
        assertEquals(text, PromptTemplate.render(text, Map.of()));
        assertSame("", PromptTemplate.render("", Map.of()));
    }
}
