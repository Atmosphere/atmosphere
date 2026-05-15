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

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Persisted LLM-as-judge baseline for prompt regression tests.
 *
 * @param name stable case name
 * @param prompt exact judge prompt sent to the evaluator model
 * @param judgeResponse raw judge response captured for the case
 * @param verdict parsed boolean verdict
 * @param quality parsed quality scores, when the case is a quality eval
 */
public record GoldenEvalBaseline(
        String name,
        String prompt,
        String judgeResponse,
        Boolean verdict,
        EvalStrategy.QualityScores quality
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Build an intent-evaluation baseline from the current strategy.
     */
    public static GoldenEvalBaseline intent(
            String name,
            String userMessage,
            String agentResponse,
            String intent,
            String judgeResponse) {
        var strategy = EvalStrategy.defaultStrategy();
        return new GoldenEvalBaseline(
                name,
                strategy.buildIntentPrompt(userMessage, agentResponse, intent),
                judgeResponse,
                strategy.parseVerdict(judgeResponse),
                null);
    }

    /**
     * Build a quality-evaluation baseline from the current strategy.
     */
    public static GoldenEvalBaseline quality(
            String name,
            String userMessage,
            String agentResponse,
            String judgeResponse) {
        var strategy = EvalStrategy.defaultStrategy();
        return new GoldenEvalBaseline(
                name,
                strategy.buildQualityPrompt(userMessage, agentResponse),
                judgeResponse,
                null,
                strategy.parseQualityScores(judgeResponse));
    }

    /**
     * Build a baseline from a captured judge run.
     */
    public static GoldenEvalBaseline fromRun(String name, LlmJudge.JudgeRun run) {
        return new GoldenEvalBaseline(
                name,
                run.prompt(),
                run.judgeResponse(),
                run.verdict(),
                run.quality());
    }

    /**
     * Load a baseline from JSON.
     */
    public static GoldenEvalBaseline read(Path path) throws IOException {
        return MAPPER.readValue(Files.readString(path), GoldenEvalBaseline.class);
    }

    /**
     * Write this baseline as JSON.
     */
    public void write(Path path) throws IOException {
        var parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, MAPPER.writeValueAsString(this));
    }

    /**
     * Assert that a freshly produced baseline still matches this golden record.
     */
    public void assertMatches(GoldenEvalBaseline actual) {
        Objects.requireNonNull(actual, "actual");
        var diff = diff(actual);
        if (!diff.isBlank()) {
            fail(diff);
        }
    }

    /**
     * Return a human-readable diff between this baseline and an actual run.
     */
    public String diff(GoldenEvalBaseline actual) {
        Objects.requireNonNull(actual, "actual");
        var diff = new StringBuilder();
        appendDiff(diff, "name", name, actual.name());
        appendDiff(diff, "prompt", prompt, actual.prompt());
        appendDiff(diff, "judgeResponse", judgeResponse, actual.judgeResponse());
        appendDiff(diff, "verdict", verdict, actual.verdict());
        appendDiff(diff, "quality", quality, actual.quality());
        return diff.toString();
    }

    private static void appendDiff(StringBuilder diff, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            diff.append("Golden eval drift in ").append(field)
                    .append("\nexpected: ").append(expected)
                    .append("\nactual:   ").append(actual)
                    .append('\n');
        }
    }
}
