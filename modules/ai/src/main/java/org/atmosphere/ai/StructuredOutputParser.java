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

import java.util.Map;
import java.util.Optional;

/**
 * SPI for parsing structured output from LLM responses. Implementations
 * generate schema instructions (appended to the prompt) and parse the
 * LLM's text output into typed Java objects.
 *
 * <p>The built-in {@link JacksonStructuredOutputParser} works with any
 * instruction-following model by generating JSON Schema and parsing
 * JSON output via Jackson. Framework-specific bridges can delegate to
 * native structured output support (e.g., Spring AI's BeanOutputConverter).</p>
 *
 * <p>Auto-detected via {@link java.util.ServiceLoader}. When multiple
 * implementations are available, the one with the highest
 * {@link #priority()} wins.</p>
 *
 * @see AiEvent.StructuredField
 * @see AiEvent.EntityStart
 * @see AiEvent.EntityComplete
 */
public interface StructuredOutputParser {

    /**
     * Generate the instruction text to append to the system prompt so the
     * LLM returns output conforming to the given type's schema.
     *
     * @param targetType the Java class to generate schema instructions for
     * @return instruction text (e.g., "Respond with valid JSON matching this schema: {...}")
     */
    String schemaInstructions(Class<?> targetType);

    /**
     * Parse a complete LLM text response into an instance of the target type.
     *
     * @param llmOutput  the raw LLM output text
     * @param targetType the Java class to parse into
     * @param <T>        the target type
     * @return the parsed object
     * @throws StructuredOutputException if parsing fails
     */
    <T> T parse(String llmOutput, Class<T> targetType);

    /**
     * Attempt to parse a single field from a streaming chunk. Returns empty
     * if the chunk does not contain a complete field value.
     *
     * @param chunk      the streaming text chunk
     * @param targetType the target entity type (for field name resolution)
     * @param <T>        the target type
     * @return a field name/value pair, or empty if no field was parsed
     */
    <T> Optional<Map.Entry<String, Object>> parseField(String chunk, Class<T> targetType);

    /**
     * Priority for ServiceLoader selection. Higher values win.
     */
    default int priority() {
        return 0;
    }

    /**
     * Whether this parser's required dependencies are on the classpath.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Exception thrown when structured output parsing fails.
     */
    class StructuredOutputException extends RuntimeException {
        public StructuredOutputException(String message) {
            super(message);
        }

        public StructuredOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
