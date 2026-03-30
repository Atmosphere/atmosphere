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
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
    private LlmJudge judge;
    private String userMessage;

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
     * Configure an LLM judge for eval assertions. Required before calling
     * {@link #meetsIntent}, {@link #isGroundedIn}, or {@link #hasQuality}.
     *
     * @param judge the LLM judge to use for evaluation
     * @return this assertion chain
     */
    public AiAssertions withJudge(LlmJudge judge) {
        this.judge = judge;
        return this;
    }

    /**
     * Set the original user message for context in eval assertions.
     *
     * @param message the user's prompt
     * @return this assertion chain
     */
    public AiAssertions forPrompt(String message) {
        this.userMessage = message;
        return this;
    }

    /**
     * Assert that the response meets the stated intent, as judged by an LLM.
     * Requires {@link #withJudge} and {@link #forPrompt} to be called first.
     *
     * @param intent what the response should accomplish
     * @return this assertion chain
     */
    public AiAssertions meetsIntent(String intent) {
        requireJudge("meetsIntent");
        assertTrue(judge.meetsIntent(
                        userMessage != null ? userMessage : "", response.text(), intent),
                "LLM judge determined response does not meet intent: '" + intent
                        + "'\nResponse: " + truncate(response.text(), 300));
        return this;
    }

    /**
     * Assert that the response is grounded in the outputs of the named tools
     * (i.e., the response cites tool data rather than hallucinating).
     * Requires {@link #withJudge} to be called first.
     *
     * @param toolNames the tools whose outputs the response should be grounded in
     * @return this assertion chain
     */
    public AiAssertions isGroundedIn(String... toolNames) {
        requireJudge("isGroundedIn");
        var toolOutputs = response.eventsOfType(AiEvent.ToolResult.class).stream()
                .filter(tr -> {
                    for (var name : toolNames) {
                        if (name.equals(tr.toolName())) {
                            return true;
                        }
                    }
                    return false;
                })
                .map(tr -> tr.toolName() + ": " + tr.result())
                .collect(Collectors.joining("\n"));

        assertFalse(toolOutputs.isBlank(),
                "No tool results found for: " + String.join(", ", toolNames));

        assertTrue(judge.isGrounded(response.text(), toolOutputs),
                "LLM judge determined response is NOT grounded in tool outputs."
                        + "\nResponse: " + truncate(response.text(), 200)
                        + "\nTool outputs: " + truncate(toolOutputs, 200));
        return this;
    }

    /**
     * Assert that the response meets quality thresholds on relevance,
     * coherence, and safety, as scored by an LLM judge.
     * Requires {@link #withJudge} and {@link #forPrompt} to be called first.
     *
     * @param spec consumer that sets quality thresholds
     * @return this assertion chain
     */
    public AiAssertions hasQuality(Consumer<QualitySpec> spec) {
        requireJudge("hasQuality");
        var qualitySpec = new QualitySpec();
        spec.accept(qualitySpec);

        var scores = judge.scoreQuality(
                userMessage != null ? userMessage : "", response.text());

        assertTrue(scores.relevance() >= qualitySpec.relevanceThreshold(),
                "Relevance score " + scores.relevance()
                        + " below threshold " + qualitySpec.relevanceThreshold());
        assertTrue(scores.coherence() >= qualitySpec.coherenceThreshold(),
                "Coherence score " + scores.coherence()
                        + " below threshold " + qualitySpec.coherenceThreshold());
        assertTrue(scores.safety() >= qualitySpec.safetyThreshold(),
                "Safety score " + scores.safety()
                        + " below threshold " + qualitySpec.safetyThreshold());
        return this;
    }

    private void requireJudge(String method) {
        if (judge == null) {
            throw new IllegalStateException(
                    method + "() requires an LLM judge. Call withJudge() first.");
        }
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
