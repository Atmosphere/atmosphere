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

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.atmosphere.ai.test.AiAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class LlmJudgeTest {

    /**
     * Fake runtime that returns canned JSON responses for testing the judge.
     */
    static class FakeJudgeRuntime implements AgentRuntime {
        private final String cannedResponse;

        FakeJudgeRuntime(String response) {
            this.cannedResponse = response;
        }

        @Override public String name() { return "fake-judge"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send(cannedResponse);
            session.complete();
        }
    }

    @Test
    void meetsIntentReturnsTrueWhenJudgeApproves() {
        var judge = new LlmJudge(new FakeJudgeRuntime("{\"verdict\": true}"), "test-model");
        assertTrue(judge.meetsIntent("What time?", "It's 3pm.", "Tell the time"));
    }

    @Test
    void meetsIntentReturnsFalseWhenJudgeRejects() {
        var judge = new LlmJudge(new FakeJudgeRuntime("{\"verdict\": false}"), "test-model");
        assertFalse(judge.meetsIntent("What time?", "I like cats.", "Tell the time"));
    }

    @Test
    void isGroundedReturnsTrueWhenGrounded() {
        var judge = new LlmJudge(new FakeJudgeRuntime("{\"verdict\": true}"), "test-model");
        assertTrue(judge.isGrounded("It's 22C in Paris.", "get_weather: Paris: 22C"));
    }

    @Test
    void isGroundedReturnsFalseWhenHallucinated() {
        var judge = new LlmJudge(new FakeJudgeRuntime("{\"verdict\": false}"), "test-model");
        assertFalse(judge.isGrounded("It's 40C in Paris.", "get_weather: Paris: 22C"));
    }

    @Test
    void scoreQualityReturnsScores() {
        var judge = new LlmJudge(
                new FakeJudgeRuntime("{\"relevance\": 0.9, \"coherence\": 0.8, \"safety\": 1.0}"),
                "test-model");
        var scores = judge.scoreQuality("What time?", "It's 3pm in Tokyo.");
        assertEquals(0.9, scores.relevance(), 0.01);
        assertEquals(0.8, scores.coherence(), 0.01);
        assertEquals(1.0, scores.safety(), 0.01);
    }

    @Test
    void scoreQualityHandlesMarkdownCodeBlocks() {
        var judge = new LlmJudge(
                new FakeJudgeRuntime("```json\n{\"relevance\": 0.7, \"coherence\": 0.6, \"safety\": 0.9}\n```"),
                "test-model");
        var scores = judge.scoreQuality("test", "test response");
        assertEquals(0.7, scores.relevance(), 0.01);
        assertEquals(0.6, scores.coherence(), 0.01);
        assertEquals(0.9, scores.safety(), 0.01);
    }

    @Test
    void judgeRuntimeFailureThrowsAssertionError() {
        var failingRuntime = new AgentRuntime() {
            @Override public String name() { return "failing"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.error(new RuntimeException("Judge LLM unavailable"));
            }
        };

        var judge = new LlmJudge(failingRuntime, "test-model");
        assertThrows(AssertionError.class,
                () -> judge.meetsIntent("test", "test", "test"));
    }

    @Test
    void assertionsMeetsIntentWithFakeJudge() {
        var judge = new LlmJudge(new FakeJudgeRuntime("{\"verdict\": true}"), "test-model");
        var response = new AiResponse("It's 3pm in Tokyo.",
                List.of(), Map.of(), List.of(), Duration.ofMillis(100), true);

        assertThat(response)
                .withJudge(judge)
                .forPrompt("What time is it in Tokyo?")
                .meetsIntent("Tells the current time in Tokyo");
    }

    @Test
    void assertionsIsGroundedInWithToolResults() {
        var judge = new LlmJudge(new FakeJudgeRuntime("{\"verdict\": true}"), "test-model");
        var response = new AiResponse("It's 22C in Paris.",
                List.of(
                        new AiEvent.ToolStart("get_weather", Map.of("city", "Paris")),
                        new AiEvent.ToolResult("get_weather", "Paris: 22C")
                ),
                Map.of(), List.of(), Duration.ofMillis(100), true);

        assertThat(response)
                .withJudge(judge)
                .isGroundedIn("get_weather");
    }

    @Test
    void assertionsHasQualityWithThresholds() {
        var judge = new LlmJudge(
                new FakeJudgeRuntime("{\"relevance\": 0.9, \"coherence\": 0.8, \"safety\": 1.0}"),
                "test-model");
        var response = new AiResponse("Good response",
                List.of(), Map.of(), List.of(), Duration.ofMillis(100), true);

        assertThat(response)
                .withJudge(judge)
                .forPrompt("test question")
                .hasQuality(q -> q.relevance(0.8).coherence(0.7).safety(0.9));
    }

    @Test
    void assertionsHasQualityFailsBelowThreshold() {
        var judge = new LlmJudge(
                new FakeJudgeRuntime("{\"relevance\": 0.5, \"coherence\": 0.8, \"safety\": 1.0}"),
                "test-model");
        var response = new AiResponse("Poor response",
                List.of(), Map.of(), List.of(), Duration.ofMillis(100), true);

        assertThrows(AssertionError.class, () ->
                assertThat(response)
                        .withJudge(judge)
                        .forPrompt("test")
                        .hasQuality(q -> q.relevance(0.8)));
    }

    @Test
    void meetsIntentWithoutJudgeThrows() {
        var response = new AiResponse("text",
                List.of(), Map.of(), List.of(), Duration.ofMillis(100), true);

        assertThrows(IllegalStateException.class, () ->
                assertThat(response).meetsIntent("test"));
    }

    @Test
    void isGroundedInWithoutMatchingToolsFails() {
        var judge = new LlmJudge(new FakeJudgeRuntime("{\"verdict\": true}"), "test-model");
        var response = new AiResponse("text",
                List.of(), Map.of(), List.of(), Duration.ofMillis(100), true);

        assertThrows(AssertionError.class, () ->
                assertThat(response)
                        .withJudge(judge)
                        .isGroundedIn("nonexistent_tool"));
    }
}
