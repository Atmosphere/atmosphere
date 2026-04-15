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

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link StructuredOutputParser} interface contract:
 * default methods, static {@code resolve()}, and inner exception class.
 */
class StructuredOutputParserTest {

    /**
     * Minimal implementation for testing default methods.
     */
    private static class StubParser implements StructuredOutputParser {
        @Override
        public String schemaInstructions(Class<?> targetType) {
            return "schema for " + targetType.getSimpleName();
        }

        @Override
        public <T> T parse(String llmOutput, Class<T> targetType) {
            return null;
        }

        @Override
        public <T> Optional<Map.Entry<String, Object>> parseField(String chunk, Class<T> targetType) {
            return Optional.empty();
        }
    }

    @Test
    void defaultPriorityReturnsZero() {
        var parser = new StubParser();
        assertEquals(0, parser.priority());
    }

    @Test
    void defaultIsAvailableReturnsTrue() {
        var parser = new StubParser();
        assertTrue(parser.isAvailable());
    }

    @Test
    void resolveReturnsFallbackParser() {
        // ServiceLoader won't find providers in test classpath,
        // so resolve() should return JacksonStructuredOutputParser
        var parser = StructuredOutputParser.resolve();
        assertNotNull(parser);
        assertInstanceOf(JacksonStructuredOutputParser.class, parser);
    }

    @Test
    void structuredOutputExceptionWithMessage() {
        var ex = new StructuredOutputParser.StructuredOutputException("parse failed");
        assertEquals("parse failed", ex.getMessage());
        assertNull(ex.getCause());
        assertInstanceOf(AiException.class, ex);
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void structuredOutputExceptionWithMessageAndCause() {
        var cause = new IllegalArgumentException("bad input");
        var ex = new StructuredOutputParser.StructuredOutputException("parse failed", cause);
        assertEquals("parse failed", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    void structuredOutputExceptionCanBeCaughtAsAiException() {
        assertThrows(AiException.class, () -> {
            throw new StructuredOutputParser.StructuredOutputException("test");
        });
    }
}
