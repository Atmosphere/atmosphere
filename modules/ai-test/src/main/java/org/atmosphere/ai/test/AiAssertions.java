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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AiEvent;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Fluent assertion API for AI endpoint responses.
 *
 * <pre>{@code
 * var response = client.prompt("What's the weather in Tokyo?");
 * assertThat(response)
 *     .hasToolCall("get_weather")
 *     .withArgument("city", "Tokyo")
 *     .completedWithin(Duration.ofSeconds(10))
 *     .hasNoErrors();
 * }</pre>
 */
public class AiAssertions {

    private final AiResponse response;

    private AiAssertions(AiResponse response) {
        this.response = response;
    }

    /**
     * Entry point for fluent assertions on an AI response.
     */
    public static AiAssertions assertThat(AiResponse response) {
        return new AiAssertions(response);
    }

    /**
     * Assert that the response text contains the given substring.
     */
    public AiAssertions containsText(String expected) {
        assertTrue(response.text().contains(expected),
                "Expected response to contain '" + expected + "' but was: "
                        + truncate(response.text(), 200));
        return this;
    }

    /**
     * Assert that a tool was called with the given name.
     */
    public ToolCallAssertions hasToolCall(String toolName) {
        assertTrue(response.hasToolCall(toolName),
                "Expected tool call '" + toolName + "' but events were: "
                        + response.events().stream()
                        .map(e -> e.eventType())
                        .toList());
        return new ToolCallAssertions(this, toolName);
    }

    /**
     * Assert that the response contains at least one event of the given type.
     */
    public AiAssertions containsEventType(Class<? extends AiEvent> eventType) {
        assertFalse(response.eventsOfType(eventType).isEmpty(),
                "Expected at least one " + eventType.getSimpleName() + " event");
        return this;
    }

    /**
     * Assert that the response completed within the given duration.
     */
    public AiAssertions completedWithin(Duration maxDuration) {
        assertTrue(response.completed(),
                "Response did not complete (errors: " + response.errors() + ")");
        assertTrue(response.elapsed().compareTo(maxDuration) <= 0,
                "Expected completion within " + maxDuration + " but took " + response.elapsed());
        return this;
    }

    /**
     * Assert that no errors occurred.
     */
    public AiAssertions hasNoErrors() {
        assertFalse(response.hasErrors(),
                "Expected no errors but got: " + response.errors());
        return this;
    }

    /**
     * Assert that the response completed successfully.
     */
    public AiAssertions isComplete() {
        assertTrue(response.completed(), "Expected response to complete");
        return this;
    }

    /**
     * Assert that the metadata contains the given key.
     */
    public AiAssertions hasMetadata(String key) {
        assertTrue(response.metadata().containsKey(key),
                "Expected metadata key '" + key + "' but keys were: "
                        + response.metadata().keySet());
        return this;
    }

    /**
     * Fluent assertions for a specific tool call.
     */
    public static class ToolCallAssertions {
        private final AiAssertions parent;
        private final String toolName;

        ToolCallAssertions(AiAssertions parent, String toolName) {
            this.parent = parent;
            this.toolName = toolName;
        }

        /**
         * Assert that the tool was called with a specific argument.
         */
        public ToolCallAssertions withArgument(String argName, Object expected) {
            var toolStart = parent.response.eventsOfType(AiEvent.ToolStart.class).stream()
                    .filter(ts -> ts.toolName().equals(toolName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Tool call '" + toolName + "' not found"));
            var actual = toolStart.arguments().get(argName);
            if (!expected.equals(actual)) {
                fail("Expected tool '" + toolName + "' argument '" + argName
                        + "' = " + expected + " but was: " + actual);
            }
            return this;
        }

        /**
         * Assert that the tool returned a result.
         */
        public ToolCallAssertions hasResult() {
            assertTrue(parent.response.hasToolResult(toolName),
                    "Expected result for tool '" + toolName + "'");
            return this;
        }

        /**
         * Return to the parent assertions for chaining.
         */
        public AiAssertions and() {
            return parent;
        }
    }

    private static String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
