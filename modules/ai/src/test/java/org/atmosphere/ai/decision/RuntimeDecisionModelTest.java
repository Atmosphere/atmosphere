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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiConfidenceElicitation;
import org.atmosphere.ai.DecisionDistribution;
import org.atmosphere.ai.NativeStructuredOutput;
import org.atmosphere.ai.TokenLogprob;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.llm.DecisionField;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link RuntimeDecisionModel} reference implementation, driven through a
 * scripted {@link org.atmosphere.ai.AgentRuntime} so every terminal path is
 * reproducible without a model.
 */
class RuntimeDecisionModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Question.Noul NOUL = new Question.Noul("Is it raining?", "wet", "dry");

    // --- (a) schema encoding -------------------------------------------------

    @Test
    void noulAnswerIsABooleanTheBuiltInRuntimeCanScore() {
        var schema = RuntimeDecisionModel.QuestionSpec.of(NOUL).schema();
        var answer = MAPPER.readTree(schema).path("properties").path("answer");
        assertEquals("boolean", answer.path("type").stringValue());
        var field = DecisionField.fromSchema(schema, "answer").orElseThrow();
        assertEquals(List.of("true", "false"), field.values());
        assertFalse(field.quoted());
    }

    @Test
    void choiceUpToSixteenOptionsIsCodedAndScorable() {
        var schema = RuntimeDecisionModel.QuestionSpec.of(new Question.Choice("pick", options(16))).schema();
        var enumValues = enumOf(schema);
        assertEquals(16, enumValues.size());
        assertEquals("A", enumValues.getFirst());
        assertEquals("P", enumValues.getLast());
        assertEquals(enumValues, DecisionField.fromSchema(schema, "answer").orElseThrow().values(),
                "the Built-in runtime must be able to score a coded choice");
    }

    @Test
    void choiceAboveSixteenOptionsUsesKeysAndIsModelReportedOnly() {
        var schema = RuntimeDecisionModel.QuestionSpec.of(new Question.Choice("pick", options(17))).schema();
        assertEquals(new ArrayList<>(options(17).keySet()), enumOf(schema));
        // With choiceUpToSixteenOptionsIsCodedAndScorable this pins
        // MAX_CODED_OPTIONS to the Built-in runtime's own ceiling
        // (DecisionField.MAX_VALUES): if either moves, a coded choice would
        // silently lose its distribution.
        assertTrue(DecisionField.fromSchema(schema, "answer").isEmpty());
        var atCeiling = RuntimeDecisionModel.QuestionSpec.of(
                new Question.Choice("pick", options(RuntimeDecisionModel.MAX_CODED_OPTIONS))).schema();
        assertTrue(DecisionField.fromSchema(atCeiling, "answer").isPresent());
    }

    @Test
    void scoreIsAStringEnumOfLevelIndexes() {
        var schema = RuntimeDecisionModel.QuestionSpec.of(
                new Question.Score("rate", List.of("low", "mid", "high"))).schema();
        assertEquals(List.of("0", "1", "2"), enumOf(schema));
        var root = MAPPER.readTree(schema);
        assertEquals("number", root.path("properties").path("confidence").path("type").stringValue());
        assertFalse(root.path("additionalProperties").asBoolean(true));
        assertEquals(2, root.path("required").size());
    }

    @Test
    void contextCarriesResponseTypeElicitationAndSchema() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) ->
                ScriptedDecisionRuntime.reply(s, "{\"answer\":true,\"confidence\":0.9}"));
        new RuntimeDecisionModel(runtime).decide(DecisionRequest.of("state", "q", NOUL));
        var ctx = runtime.contexts().getFirst();
        assertNotNull(ctx.responseType(), "Built-in enters JSON mode only with a response type");
        var elicitation = AiConfidenceElicitation.from(ctx);
        assertEquals("answer", elicitation.decisionField());
        assertEquals("confidence", elicitation.fieldName());
        assertTrue(NativeStructuredOutput.shouldApply(ctx), "native schema when the runtime advertises it");
        assertEquals(RuntimeDecisionModel.QuestionSpec.of(NOUL).schema(), NativeStructuredOutput.schema(ctx));
        assertTrue(ctx.systemPrompt().contains(NativeStructuredOutput.schema(ctx)),
                "the schema is always in the prompt");

        var promptOnly = runtime.withoutNativeSchema();
        new RuntimeDecisionModel(promptOnly).decide(DecisionRequest.of("state", "q", NOUL));
        var plain = promptOnly.contexts().getFirst();
        assertFalse(NativeStructuredOutput.shouldApply(plain));
        assertEquals(RuntimeDecisionModel.QuestionSpec.of(NOUL).schema(), NativeStructuredOutput.schema(plain),
                "the schema still rides in metadata so the decision field resolves");
    }

    // --- (b) isolation --------------------------------------------------------

    @Test
    void eachQuestionIsIsolated() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) ->
                ScriptedDecisionRuntime.reply(s, "{\"answer\":false,\"confidence\":0.9}"));
        var questions = new LinkedHashMap<String, Question>();
        questions.put("first", new Question.Noul("FIRST-QUESTION-TEXT", null, null));
        questions.put("second", new Question.Noul("SECOND-QUESTION-TEXT", null, null));
        new RuntimeDecisionModel(runtime).decide(new DecisionRequest("the state", questions, null));
        assertEquals(2, runtime.contexts().size());
        for (var ctx : runtime.contexts()) {
            assertTrue(ctx.history().isEmpty());
            assertTrue(ctx.tools().isEmpty());
            assertTrue(ctx.contextProviders().isEmpty(), "no RAG recursion from inside the screen");
            assertTrue(ctx.listeners().isEmpty());
            assertNull(ctx.memory());
            assertEquals("STATE:\nthe state", ctx.message());
            var first = ctx.systemPrompt().contains("FIRST-QUESTION-TEXT");
            var second = ctx.systemPrompt().contains("SECOND-QUESTION-TEXT");
            assertTrue(first ^ second, "a question never sees another question's text");
        }
    }

    // --- (c) parallelism and the concurrency bound -----------------------------

    @Test
    void questionsRunInParallel() {
        var bothInFlight = new CountDownLatch(2);
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            bothInFlight.countDown();
            // Releases only if the other question is in flight at the same time.
            var together = bothInFlight.await(2, TimeUnit.SECONDS);
            ScriptedDecisionRuntime.reply(s, "{\"answer\":" + together + ",\"confidence\":0.9}");
        });
        var questions = new LinkedHashMap<String, Question>();
        questions.put("a", NOUL);
        questions.put("b", NOUL);
        var result = new RuntimeDecisionModel(runtime).decide(
                new DecisionRequest("s", questions, Duration.ofSeconds(5)));
        assertTrue(result.answer("a", Answer.Noul.class).orElseThrow().value());
        assertTrue(result.answer("b", Answer.Noul.class).orElseThrow().value());
    }

    @Test
    void inFlightNeverExceedsMaxConcurrency() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            Thread.sleep(15);
            ScriptedDecisionRuntime.reply(s, "{\"answer\":true,\"confidence\":0.9}");
        });
        var questions = new LinkedHashMap<String, Question>();
        for (var i = 0; i < DecisionRequest.MAX_QUESTIONS; i++) {
            questions.put("q" + i, NOUL);
        }
        var result = new RuntimeDecisionModel(runtime, 4, 0.5).decide(
                new DecisionRequest("s", questions, Duration.ofSeconds(20)));
        assertEquals(64, result.answers().size());
        result.answers().values().forEach(a -> assertInstanceOf(Answer.Noul.class, a));
        assertTrue(runtime.maxInFlight() <= 4, "max in flight " + runtime.maxInFlight());
        assertTrue(runtime.maxInFlight() >= 2, "questions should overlap, got " + runtime.maxInFlight());
    }

    // --- (d) decision-level confidence (#51) ----------------------------------

    @Test
    void choiceProbabilitiesAreMappedBackFromCodes() {
        var distribution = distribution(Map.of("A", 0.7, "B", 0.2, "C", 0.1), 0.9);
        var runtime = emitting(AiConfidence.fromDecision(distribution, List.of()),
                "{\"answer\":\"A\",\"confidence\":0.4}");
        var options = new LinkedHashMap<String, String>();
        options.put("approve", "");
        options.put("reject", "");
        options.put("defer", "");
        var answer = decideOne(runtime, new Question.Choice("pick", options), Answer.Choice.class);
        assertEquals("approve", answer.choice());
        assertEquals(Map.of("approve", 0.7, "reject", 0.2, "defer", 0.1), answer.probabilities());
        assertEquals(List.of("approve", "reject", "defer"), List.copyOf(answer.probabilities().keySet()));
        assertEquals(AiConfidence.Source.DECISION_LOGPROBS, answer.confidence().source());
        assertEquals(distribution.normalizedMargin(), answer.confidence().aggregate().getAsDouble(), 1e-9);
    }

    @Test
    void scoreIsTheExpectedLevel() {
        var runtime = emitting(AiConfidence.fromDecision(
                distribution(Map.of("0", 0.0, "1", 0.95, "2", 0.05), 1.0), List.of()),
                "{\"answer\":\"1\",\"confidence\":0.9}");
        var answer = decideOne(runtime, new Question.Score("rate", List.of("low", "mid", "high")),
                Answer.Score.class);
        assertEquals(1.05, answer.score(), 1e-9);
        assertEquals(Map.of(0, 0.0, 1, 0.95, 2, 0.05), answer.probabilities());
    }

    @Test
    void noulCarriesProbabilityTrue() {
        var confidence = AiConfidence.fromDecision(distribution(Map.of("true", 0.8, "false", 0.2), 1.0),
                List.of());
        var answer = decideOne(emitting(confidence, "{\"answer\":true,\"confidence\":0.99}"), NOUL,
                Answer.Noul.class);
        assertTrue(answer.value());
        assertEquals(0.8, answer.probabilityTrue().getAsDouble(), 1e-9);
        assertSame(confidence, answer.confidence(), "the measured confidence is passed through unchanged");
    }

    @Test
    void lowObservedMassFallsBackToTheReportedConfidence() {
        var runtime = emitting(AiConfidence.fromDecision(
                distribution(Map.of("true", 0.9, "false", 0.1), 0.3), List.of()),
                "{\"answer\":true,\"confidence\":0.4}");
        var answer = decideOne(runtime, NOUL, Answer.Noul.class);
        assertEquals(AiConfidence.Source.MODEL_REPORTED_FIELD, answer.confidence().source());
        assertEquals(0.4, answer.confidence().aggregate().getAsDouble(), 1e-9);
        assertTrue(answer.probabilityTrue().isEmpty());
    }

    @Test
    void distributionOverOtherValuesIsNotTrusted() {
        var runtime = emitting(AiConfidence.fromDecision(
                distribution(Map.of("yes", 0.9, "no", 0.1), 1.0), List.of()),
                "{\"answer\":true,\"confidence\":0.4}");
        var answer = decideOne(runtime, NOUL, Answer.Noul.class);
        assertEquals(AiConfidence.Source.MODEL_REPORTED_FIELD, answer.confidence().source());
    }

    // --- (e) model-reported path --------------------------------------------

    @Test
    void modelReportedAnswerHasNoProbabilities() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) ->
                ScriptedDecisionRuntime.reply(s, "{\"answer\":\"B\",\"confidence\":0.66}"));
        var answer = decideOne(runtime, new Question.Choice("pick", options(3)), Answer.Choice.class);
        assertEquals("o1", answer.choice());
        assertTrue(answer.probabilities().isEmpty());
        assertEquals(AiConfidence.Source.MODEL_REPORTED_FIELD, answer.confidence().source());
        assertEquals(0.66, answer.confidence().aggregate().getAsDouble(), 1e-9);

        var noul = decideOne(new ScriptedDecisionRuntime((ctx, s) ->
                ScriptedDecisionRuntime.reply(s, "{\"answer\":false,\"confidence\":0.66}")), NOUL, Answer.Noul.class);
        assertTrue(noul.probabilityTrue().isEmpty(), "never inferred from a self-reported number");

        var score = decideOne(new ScriptedDecisionRuntime((ctx, s) ->
                        ScriptedDecisionRuntime.reply(s, "{\"answer\":\"2\",\"confidence\":0.66}")),
                new Question.Score("rate", List.of("a", "b", "c")), Answer.Score.class);
        assertEquals(2.0, score.score());
        assertTrue(score.probabilities().isEmpty());
    }

    @Test
    void missingOrOutOfRangeConfidenceIsUnknown() {
        for (var reply : List.of("{\"answer\":true}", "{\"answer\":true,\"confidence\":1.7}")) {
            var answer = decideOne(new ScriptedDecisionRuntime((ctx, s) ->
                    ScriptedDecisionRuntime.reply(s, reply)), NOUL, Answer.Noul.class);
            assertTrue(answer.confidence().aggregate().isEmpty(), reply);
            assertEquals(AiConfidence.Source.MODEL_REPORTED_FIELD, answer.confidence().source());
        }
    }

    // --- (f) the fluency mean is not a decision confidence ------------------

    @Test
    void wholeResponseLogprobsMeanIsIgnored() {
        var runtime = emitting(AiConfidence.fromLogprobs(List.of(new TokenLogprob("true", -0.01))),
                "{\"answer\":true,\"confidence\":0.3}");
        var answer = decideOne(runtime, NOUL, Answer.Noul.class);
        assertEquals(AiConfidence.Source.MODEL_REPORTED_FIELD, answer.confidence().source());
        assertEquals(0.3, answer.confidence().aggregate().getAsDouble(), 1e-9);
    }

    // --- (g) failures are typed, per question --------------------------------

    @Test
    void invalidUnparseableAndErrorAreSeparateAndIsolated() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            var prompt = ctx.systemPrompt();
            if (prompt.contains("OUT-OF-SET")) {
                ScriptedDecisionRuntime.reply(s, "{\"answer\":\"Z\",\"confidence\":0.9}");
            } else if (prompt.contains("NOT-JSON")) {
                ScriptedDecisionRuntime.reply(s, "yes, definitely");
            } else if (prompt.contains("WRONG-TYPE")) {
                ScriptedDecisionRuntime.reply(s, "{\"answer\":\"true\",\"confidence\":0.9}");
            } else if (prompt.contains("THROWS")) {
                throw new IllegalStateException("provider down");
            } else {
                ScriptedDecisionRuntime.reply(s, "{\"answer\":true,\"confidence\":0.9}");
            }
        });
        var questions = new LinkedHashMap<String, Question>();
        questions.put("invalid", new Question.Choice("OUT-OF-SET", options(2)));
        questions.put("unparseable", new Question.Noul("NOT-JSON", null, null));
        questions.put("wrongType", new Question.Noul("WRONG-TYPE", null, null));
        questions.put("error", new Question.Noul("THROWS", null, null));
        questions.put("fine", NOUL);
        var result = new RuntimeDecisionModel(runtime).decide(new DecisionRequest("s", questions, null));
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, failed(result, "invalid").reason());
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, failed(result, "unparseable").reason());
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, failed(result, "wrongType").reason());
        assertEquals(Answer.Failed.Reason.ERROR, failed(result, "error").reason());
        assertTrue(failed(result, "error").detail().contains("provider down"));
        assertTrue(result.answer("fine", Answer.Noul.class).orElseThrow().value());
    }

    @Test
    void emptyReplyIsUnparseable() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> s.complete());
        var result = new RuntimeDecisionModel(runtime).decide(DecisionRequest.of("s", "q", NOUL));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, failed(result, "q").reason());
    }

    // --- (h, i) deadline -------------------------------------------------------

    @Test
    void hungQuestionTimesOutAndIsCancelledWhileOthersAnswer() throws Exception {
        var never = new CountDownLatch(1);
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            if (ctx.systemPrompt().contains("HANG")) {
                never.await();
            }
            ScriptedDecisionRuntime.reply(s, "{\"answer\":true,\"confidence\":0.9}");
        });
        var questions = new LinkedHashMap<String, Question>();
        questions.put("hung", new Question.Noul("HANG", null, null));
        questions.put("fine", NOUL);
        var start = System.nanoTime();
        var result = new RuntimeDecisionModel(runtime).decide(
                new DecisionRequest("s", questions, Duration.ofMillis(300)));
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 1_300, "decide() must return near its deadline, took " + elapsedMs + "ms");
        assertEquals(Answer.Failed.Reason.TIMEOUT, failed(result, "hung").reason());
        assertTrue(result.answer("fine", Answer.Noul.class).orElseThrow().value());
        assertTrue(runtime.cancelled().await(2, TimeUnit.SECONDS), "the hung call's handle must be cancelled");
        never.countDown();
    }

    /**
     * Correctness Invariant #3: a runtime that keeps streaming past the reply
     * bound does not grow the buffer until the deadline. The question fails as
     * unparseable at once and the dispatch is cancelled.
     */
    @Test
    void runawayReplyIsBoundedUnparseableAndCancelled() throws Exception {
        var self = new java.util.concurrent.atomic.AtomicReference<ScriptedDecisionRuntime>();
        var chunk = "x".repeat(1024);
        // Ignores isClosed() and keeps writing until its handle is cancelled.
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            s.send("{\"answer\":true,\"confidence\":0.9,\"pad\":\"");
            for (var i = 0; i < 1_000_000 && self.get().cancelled().getCount() > 0; i++) {
                s.send(chunk);
            }
            s.complete();
        });
        self.set(runtime);
        var start = System.nanoTime();
        var result = new RuntimeDecisionModel(runtime).decide(
                DecisionRequest.of("s", "q", NOUL).withTimeout(Duration.ofSeconds(30)));
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        var failure = failed(result, "q");
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, failure.reason());
        assertTrue(failure.detail().contains("exceeds " + DecisionCapturingSession.MAX_REPLY_CHARS),
                failure.detail());
        assertTrue(elapsedMs < 10_000, "an overflow must not wait for the deadline, took " + elapsedMs + "ms");
        assertTrue(runtime.cancelled().await(2, TimeUnit.SECONDS), "the runaway dispatch must be cancelled");
    }

    @Test
    void sessionStopsBufferingAtTheBound() {
        var sink = new DecisionCapturingSession("bound");
        sink.send("x".repeat(DecisionCapturingSession.MAX_REPLY_CHARS));
        assertFalse(sink.overflowed());
        assertFalse(sink.isClosed());
        sink.send("y");
        assertTrue(sink.overflowed());
        assertTrue(sink.isClosed());
        sink.send("z".repeat(1024));
        assertEquals(DecisionCapturingSession.MAX_REPLY_CHARS, sink.text().length());
    }

    @Test
    void oversizedSummaryIsBoundedToo() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) ->
                s.complete("x".repeat(DecisionCapturingSession.MAX_REPLY_CHARS + 1)));
        var result = new RuntimeDecisionModel(runtime).decide(DecisionRequest.of("s", "q", NOUL));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, failed(result, "q").reason());
        assertTrue(failed(result, "q").detail().contains("exceeds"), failed(result, "q").detail());
    }

    @Test
    void blockingRuntimeWithoutHandleIsInterrupted() throws Exception {
        var interrupted = new CountDownLatch(1);
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        }).synchronous();
        var result = new RuntimeDecisionModel(runtime).decide(
                DecisionRequest.of("s", "q", NOUL).withTimeout(Duration.ofMillis(200)));
        assertEquals(Answer.Failed.Reason.TIMEOUT, failed(result, "q").reason());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS), "the carrier must be interrupted");
    }

    @Test
    void runtimeIgnoringCancelAndInterruptCannotHoldDecide() throws Exception {
        var release = new CountDownLatch(1);
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Deliberately deaf to interrupts: the regression is a
                    // decide() that joins such a carrier.
                }
            }
            ScriptedDecisionRuntime.reply(s, "{\"answer\":true,\"confidence\":0.9}");
        }).synchronous();
        try {
            var start = System.nanoTime();
            var result = new RuntimeDecisionModel(runtime).decide(
                    DecisionRequest.of("s", "q", NOUL).withTimeout(Duration.ofMillis(200)));
            var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 1_200, "decide() blocked for " + elapsedMs + "ms");
            assertEquals(Answer.Failed.Reason.TIMEOUT, failed(result, "q").reason());
        } finally {
            release.countDown();
        }
    }

    // --- (j) native schema rejection ------------------------------------------

    @Test
    void schemaRejectionBeforeOutputRetriesOncePromptOnly() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            if (NativeStructuredOutput.shouldApply(ctx)) {
                s.error(new IllegalStateException("400: invalid json_schema in response_format"));
                return;
            }
            ScriptedDecisionRuntime.reply(s, "{\"answer\":true,\"confidence\":0.9}");
        });
        var result = new RuntimeDecisionModel(runtime).decide(DecisionRequest.of("s", "q", NOUL));
        assertTrue(result.answer("q", Answer.Noul.class).orElseThrow().value());
        assertEquals(2, runtime.contexts().size());
        var retry = runtime.contexts().get(1);
        assertFalse(NativeStructuredOutput.shouldApply(retry));
        assertNotNull(NativeStructuredOutput.schema(retry));
    }

    @Test
    void unrelatedErrorIsNotRetried() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> s.error(new IllegalStateException("429 rate limited")));
        var result = new RuntimeDecisionModel(runtime).decide(DecisionRequest.of("s", "q", NOUL));
        assertEquals(Answer.Failed.Reason.ERROR, failed(result, "q").reason());
        assertEquals(1, runtime.contexts().size());
    }

    // --- (k) capacity -----------------------------------------------------------

    @Test
    void exhaustedPermitsFailWithCapacity() {
        var never = new CountDownLatch(1);
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> never.await());
        var questions = new LinkedHashMap<String, Question>();
        questions.put("a", NOUL);
        questions.put("b", NOUL);
        try {
            var result = new RuntimeDecisionModel(runtime, 1, 0.5).decide(
                    new DecisionRequest("s", questions, Duration.ofMillis(300)));
            var reasons = List.of(failed(result, "a").reason(), failed(result, "b").reason());
            assertTrue(reasons.contains(Answer.Failed.Reason.CAPACITY), reasons.toString());
            assertTrue(reasons.contains(Answer.Failed.Reason.TIMEOUT), reasons.toString());
            assertEquals(1, runtime.contexts().size(), "only one question may be dispatched");
        } finally {
            never.countDown();
        }
    }

    // --- (l) availability -------------------------------------------------------

    @Test
    void demoRuntimeIsNeverAvailable() {
        assertFalse(new RuntimeDecisionModel(new DemoAgentRuntime()).isAvailable());
        var model = new RuntimeDecisionModel(new ScriptedDecisionRuntime((ctx, s) -> s.complete()));
        assertTrue(model.isAvailable());
        assertEquals("runtime:scripted", model.name());
    }

    // --- (m) the capturing session ---------------------------------------------

    @Test
    void capturingSessionKeepsConfidenceUsageAndError() throws Exception {
        var session = new DecisionCapturingSession("s");
        var confidence = AiConfidence.fromDecision(distribution(Map.of("true", 1.0, "false", 0.0), 1.0),
                List.of());
        session.confidence(confidence);
        session.usage(TokenUsage.of(10, 2));
        session.usage(TokenUsage.of(5, 1));
        session.send("{\"answer\":");
        session.send("true}");
        session.complete("{\"answer\":true}");
        session.send("late");
        session.confidence(AiConfidence.reported(0.1));
        assertTrue(session.await(0));
        assertSame(confidence, session.confidence());
        assertEquals(15, session.usage().input());
        assertEquals(18, session.usage().total());
        assertEquals("{\"answer\":true}", session.text(), "summary is not appended after streamed text");

        var failing = new DecisionCapturingSession("f");
        var boom = new IllegalStateException("boom");
        failing.error(boom);
        failing.complete();
        assertSame(boom, failing.failure());
        assertTrue(failing.hasErrored());

        var summaryOnly = new DecisionCapturingSession("c");
        summaryOnly.complete("{\"answer\":false}");
        assertEquals("{\"answer\":false}", summaryOnly.text());
    }

    @Test
    void usageIsSummedAcrossQuestions() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            s.usage(new TokenUsage(7, 3, 0, 10, "tiny-model"));
            ScriptedDecisionRuntime.reply(s, "{\"answer\":true,\"confidence\":0.9}");
        });
        var questions = new LinkedHashMap<String, Question>();
        questions.put("a", NOUL);
        questions.put("b", NOUL);
        var result = new RuntimeDecisionModel(runtime).decide(new DecisionRequest("s", questions, null));
        assertEquals(20, result.usage().orElseThrow().total());
        assertEquals("tiny-model", result.model());
    }

    // --- helpers -----------------------------------------------------------------

    private static ScriptedDecisionRuntime emitting(AiConfidence confidence, String reply) {
        return new ScriptedDecisionRuntime((ctx, s) -> {
            s.send(reply);
            s.confidence(confidence);
            s.complete();
        });
    }

    private static <A extends Answer> A decideOne(ScriptedDecisionRuntime runtime, Question question,
                                                  Class<A> type) {
        var result = new RuntimeDecisionModel(runtime).decide(DecisionRequest.of("state", "q", question));
        var answer = result.answers().get("q");
        return assertInstanceOf(type, answer, String.valueOf(answer));
    }

    private static Answer.Failed failed(DecisionResult result, String id) {
        return assertInstanceOf(Answer.Failed.class, result.answers().get(id),
                String.valueOf(result.answers().get(id)));
    }

    private static DecisionDistribution distribution(Map<String, Double> probabilities, double mass) {
        return new DecisionDistribution("answer", probabilities, mass);
    }

    private static List<String> enumOf(String schema) {
        var values = new ArrayList<String>();
        MAPPER.readTree(schema).path("properties").path("answer").path("enum")
                .forEach(v -> values.add(v.stringValue()));
        return values;
    }

    private static SequencedMap<String, String> options(int n) {
        var options = new LinkedHashMap<String, String>();
        for (var i = 0; i < n; i++) {
            options.put("o" + i, "");
        }
        return options;
    }
}
