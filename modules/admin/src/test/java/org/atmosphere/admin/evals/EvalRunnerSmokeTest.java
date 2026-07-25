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
package org.atmosphere.admin.evals;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.ai.test.LlmJudge;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Deterministic eval smoke — the CI gate proving the eval flywheel can RUN
 * evals end to end. Rides the full-reactor "Run All Tests" lane in
 * {@code .github/workflows/ci.yml}: if the runner stops replaying, scoring,
 * persisting, or aggregating, this test goes red.
 *
 * <p>The pipeline under test is the real production one: a checked-in dataset
 * ({@code eval-smoke-dataset.tsv}) replayed by {@link EvalRunner} against the
 * Built-in runtime (canned {@link LlmClient}, no network), scored through
 * {@link LlmJudgeLiveScorer} → the real ai-test {@link LlmJudge} whose canned
 * judge runtime actually checks the response against the recorded reference.
 * One case ({@code smoke-wrong}) is answered incorrectly on purpose, so the
 * gate also proves failures are detected — a scorer that rubber-stamps every
 * case fails the exact-count assertions below.</p>
 */
class EvalRunnerSmokeTest {

    private static final String DATASET_RESOURCE = "/eval-smoke-dataset.tsv";
    private static final double PASS_THRESHOLD = 0.75;

    /** Canned Built-in-runtime answers, keyed by a prompt fragment. */
    private static final Map<String, String> CANNED_ANSWERS = Map.of(
            "France", "The capital of France is Paris.",
            "Japan", "The capital of Japan is Tokyo.",
            "2 plus 2", "2 plus 2 equals 4.",
            "sky", "On a clear day the sky is blue.",
            // Deliberately wrong: the judge must fail this case.
            "Australia", "The capital of Australia is Sydney.");

    @Test
    void checkedInDatasetReplaysThroughBuiltInRuntimeAndLlmJudge() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var dataset = loadDataset();
            var cases = dataset.list();
            assertEquals(5, cases.size(), "the checked-in smoke dataset pins 5 cases");

            var runStore = new InMemoryEvalRunStore();
            var runner = new EvalRunner(
                    EvalRunnerSmokeTest::cannedBuiltInRuntime,
                    judgeModel -> new LlmJudgeLiveScorer(
                            new LlmJudge(referenceCheckingJudge(), judgeModel), PASS_THRESHOLD),
                    dataset, runStore, 2, Duration.ofSeconds(10), PASS_THRESHOLD);

            var summary = runner.run(new EvalRunner.RunRequest("ci-smoke", null, "canned-judge"));

            // The runner produced exactly one scored row per case…
            assertEquals(5, summary.totalCases());
            for (var evalCase : cases) {
                assertTrue(runStore.findById(summary.runId() + "." + evalCase.id()).isPresent(),
                        "missing scored row for " + evalCase.id());
            }
            // …detected the deliberately wrong answer…
            assertFalse(runStore.findById(summary.runId() + ".smoke-wrong").orElseThrow().passed(),
                    "the judge must fail the deliberately wrong answer");
            assertEquals(4, summary.passedCases());
            assertEquals(1, summary.failedCases());

            // …and the aggregate clears the threshold (4/5 = 0.8 >= 0.75).
            assertEquals(0.8, summary.passRate(), 1e-9);
            assertTrue(summary.passed(), "aggregate must pass at 0.8 vs threshold 0.75");
            var aggregate = runStore.findById(summary.runId()).orElseThrow();
            assertTrue(aggregate.passed());
            assertEquals(0.8, aggregate.scores().get("passRate"), 1e-9);
            assertEquals(6, runStore.list().size(), "5 case rows + 1 aggregate row");
        });
    }

    // ── Target: the Built-in runtime over a canned LlmClient ───────────────

    private static AgentRuntime cannedBuiltInRuntime() {
        var client = mock(LlmClient.class);
        doAnswer(invocation -> {
            ChatCompletionRequest request = invocation.getArgument(0);
            StreamingSession session = invocation.getArgument(1);
            var conversation = request.messages().stream()
                    .map(ChatMessage::content)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
            session.send(cannedAnswer(conversation));
            session.complete();
            return null;
        }).when(client).streamChatCompletion(
                any(ChatCompletionRequest.class), any(StreamingSession.class));
        return new TestableBuiltInRuntime(client);
    }

    private static String cannedAnswer(String conversation) {
        return CANNED_ANSWERS.entrySet().stream()
                .filter(entry -> conversation.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("I do not know.");
    }

    static final class TestableBuiltInRuntime extends BuiltInAgentRuntime {
        TestableBuiltInRuntime(LlmClient client) {
            setNativeClient(client);
        }
    }

    // ── Judge: canned runtime that actually checks response vs reference ───

    /**
     * A canned judge that parses the intent prompt LlmJudge builds
     * ({@code Agent response: …} / {@code …reference answer: …}) and returns
     * {@code {"verdict": true}} only when the response contains the reference —
     * so the smoke cannot pass with a scorer that rubber-stamps every case.
     */
    private static AgentRuntime referenceCheckingJudge() {
        return new AgentRuntime() {
            @Override public String name() { return "canned-judge"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                var judgePrompt = context.message();
                var response = lineAfter(judgePrompt, "Agent response:");
                var reference = tailAfter(judgePrompt, "reference answer:");
                var verdict = !reference.isBlank() && response.contains(reference);
                session.send("{\"verdict\": " + verdict + "}");
                session.complete();
            }
        };
    }

    private static String lineAfter(String text, String prefix) {
        for (var line : text.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String tailAfter(String text, String marker) {
        var index = text.indexOf(marker);
        if (index < 0) {
            return "";
        }
        // The reference is the remainder of that line.
        var tail = text.substring(index + marker.length());
        var newline = tail.indexOf('\n');
        return (newline < 0 ? tail : tail.substring(0, newline)).trim();
    }

    // ── Checked-in dataset ─────────────────────────────────────────────────

    private static InMemoryEvalDatasetStore loadDataset() throws IOException {
        var store = new InMemoryEvalDatasetStore();
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(
                        EvalRunnerSmokeTest.class.getResourceAsStream(DATASET_RESOURCE),
                        DATASET_RESOURCE + " missing from test resources"),
                StandardCharsets.UTF_8))) {
            var rows = new ArrayList<EvalCase>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var fields = line.split("\t", 3);
                assertEquals(3, fields.length, "malformed smoke dataset line: " + line);
                rows.add(new EvalCase(fields[0], fields[1], fields[2], "smoke",
                        List.of("smoke"), Instant.now()));
            }
            rows.forEach(store::save);
        }
        return store;
    }
}
