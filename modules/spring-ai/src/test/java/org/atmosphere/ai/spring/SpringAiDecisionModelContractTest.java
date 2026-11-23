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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.Question;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.DecisionResponseMetadata;
import org.springframework.ai.decision.answer.ChoiceAnswer;
import org.springframework.ai.decision.answer.RefusalAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.input.TextInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpringAiDecisionModel} keeps the {@link org.atmosphere.ai.decision.DecisionModel}
 * contract over a Spring AI 2.1 decision model: every requested id answered,
 * within the timeout, provider distributions kept and tagged
 * {@link AiConfidence.Source#PROVIDER_DISTRIBUTION}, a refusal or an answer
 * outside the question failed instead of guessed.
 */
class SpringAiDecisionModelContractTest {

    /** A Spring AI decision model whose response the test scripts. */
    private static org.springframework.ai.decision.DecisionModel scripted(
            Function<DecisionPrompt, DecisionResponse> behaviour) {
        return behaviour::apply;
    }

    private static DecisionRequest request(String id, Question question) {
        return DecisionRequest.of("Customer: I was double charged.", id, question);
    }

    @Test
    void aYesNoQuestionGoesOutWithItsCriteriaAndPYesComesBack() {
        var sent = new AtomicReference<DecisionPrompt>();
        var model = new SpringAiDecisionModel(scripted(prompt -> {
            sent.set(prompt);
            return DecisionResponse.builder(prompt)
                    .answer("refund", YesNoAnswer.builder().probability(0.9).build())
                    .build();
        }), "test");

        var result = model.decide(request("refund", new Question.Noul("Is a refund due?", "charged twice", "no")));

        var question = assertInstanceOf(YesNoQuestion.class, sent.get().getQuestions().get("refund"));
        assertEquals("Is a refund due?", question.getText());
        assertEquals("charged twice", question.getWhenYes());
        assertEquals("no", question.getWhenNo());
        assertEquals("Customer: I was double charged.",
                assertInstanceOf(TextInput.class, sent.get().getInstructions()).getText());
        var answer = result.answer("refund", Answer.Noul.class).orElseThrow();
        assertTrue(answer.value());
        assertEquals(0.9, answer.probabilityTrue().getAsDouble(), 1e-9);
        assertEquals(0.8, answer.confidence().aggregate().getAsDouble(), 1e-9, "|2p - 1|");
        assertEquals(AiConfidence.Source.PROVIDER_DISTRIBUTION, answer.confidence().source());
    }

    @Test
    void aChoiceKeepsTheOptionsAndTheProviderDistribution() {
        var sent = new AtomicReference<DecisionPrompt>();
        var options = new LinkedHashMap<String, String>();
        options.put("billing", "money questions");
        options.put("support", "");
        var model = new SpringAiDecisionModel(scripted(prompt -> {
            sent.set(prompt);
            return DecisionResponse.builder(prompt)
                    .answer("route", ChoiceAnswer.builder().value("billing")
                            .probabilities(Map.of("billing", 0.7, "support", 0.3)).confidence(0.7).build())
                    .build();
        }), "test");

        var result = model.decide(request("route", new Question.Choice("Which team?", options)));

        var question = assertInstanceOf(ChoiceQuestion.class, sent.get().getQuestions().get("route"));
        assertEquals(List.of("billing", "support"), question.getOptionNames());
        assertEquals("money questions", question.getOptions().get("billing"));
        var answer = result.answer("route", Answer.Choice.class).orElseThrow();
        assertEquals("billing", answer.choice());
        assertEquals(Map.of("billing", 0.7, "support", 0.3), answer.probabilities());
        assertEquals(0.7, answer.confidence().aggregate().getAsDouble(), 1e-9);
        assertEquals(AiConfidence.Source.PROVIDER_DISTRIBUTION, answer.confidence().source());
    }

    @Test
    void aScoreMapsItsLevelsAndConvertsTheProbabilitiesByLevel() {
        var sent = new AtomicReference<DecisionPrompt>();
        var model = new SpringAiDecisionModel(scripted(prompt -> {
            sent.set(prompt);
            return DecisionResponse.builder(prompt)
                    .answer("urgency", ScoreAnswer.builder().value(1.2)
                            .probabilities(List.of(0.1, 0.6, 0.3)).confidence(0.6).build())
                    .build();
        }), "test");

        var result = model.decide(request("urgency",
                new Question.Score("How urgent?", List.of("can wait", "today", "now"))));

        var question = assertInstanceOf(ScoreQuestion.class, sent.get().getQuestions().get("urgency"));
        assertEquals(3, question.getLevels().size());
        assertEquals("today", question.getLevels().get(1).getDescription());
        var answer = result.answer("urgency", Answer.Score.class).orElseThrow();
        assertEquals(1.2, answer.score(), 1e-9);
        assertEquals(Map.of(0, 0.1, 1, 0.6, 2, 0.3), answer.probabilities());
        assertEquals(AiConfidence.Source.PROVIDER_DISTRIBUTION, answer.confidence().source());
    }

    @Test
    void noDistributionAndNoConfidenceStayEmptyAndUnknown() {
        var model = new SpringAiDecisionModel(scripted(prompt -> DecisionResponse.builder(prompt)
                .answer("route", ChoiceAnswer.builder().value("a").build())
                .build()), "test");

        var answer = model.decide(request("route", new Question.Choice("Pick", options("a", "b"))))
                .answer("route", Answer.Choice.class).orElseThrow();

        assertTrue(answer.probabilities().isEmpty(), "never inferred");
        assertTrue(answer.confidence().aggregate().isEmpty(), "never invented");
    }

    @Test
    void aRefusalFailsItsQuestionWithTheProvidersReason() {
        var model = new SpringAiDecisionModel(scripted(prompt -> DecisionResponse.builder(prompt)
                .answer("refund", RefusalAnswer.builder().reason("policy").build())
                .build()), "test");

        var failed = model.decide(request("refund", new Question.Noul("Refund?", null, null)))
                .answer("refund", Answer.Failed.class).orElseThrow();

        assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
        assertTrue(failed.detail().contains("refused") && failed.detail().contains("policy"), failed.detail());
    }

    @Test
    void aResponseSpringAiRejectsFailsEveryQuestionInRequestOrder() {
        // Spring AI's DecisionResponse builder refuses a response that skips a
        // question; the backend's call throws, and no question is guessed.
        var questions = new LinkedHashMap<String, Question>();
        questions.put("a", new Question.Noul("A?", null, null));
        questions.put("b", new Question.Noul("B?", null, null));
        var model = new SpringAiDecisionModel(scripted(prompt -> DecisionResponse.builder(prompt)
                .answer("a", YesNoAnswer.builder().probability(0.2).build())
                .build()), "test");

        var result = model.decide(new DecisionRequest("state", questions, null));

        assertEquals(List.of("a", "b"), List.copyOf(result.answers().keySet()), "request order, every id");
        for (var id : questions.keySet()) {
            var failed = result.answer(id, Answer.Failed.class).orElseThrow();
            assertEquals(Answer.Failed.Reason.ERROR, failed.reason(), id);
            assertTrue(failed.detail().contains("missing answer for question 'b'"), failed.detail());
        }
    }

    @Test
    void anAnswerOutsideTheQuestionIsInvalidNotGuessed() {
        // Spring AI's builder rejects these today; the adapter checks them again,
        // so a response built another way still never becomes a guessed answer.
        var choice = new Question.Choice("Pick", options("a", "b"));
        var score = new Question.Score("Rate", List.of("low", "high"));
        var yesNo = new Question.Noul("Yes?", null, null);
        assertInvalid(SpringAiDecisionModel.answer("c", choice, ChoiceAnswer.builder().value("z").build()));
        assertInvalid(SpringAiDecisionModel.answer("c", choice, ChoiceAnswer.builder().value("a")
                .probabilities(Map.of("a", 0.5, "z", 0.5)).build()));
        assertInvalid(SpringAiDecisionModel.answer("s", score, ScoreAnswer.builder().value(0.5)
                .probabilities(List.of(0.5, 0.3, 0.2)).build()));
        assertInvalid(SpringAiDecisionModel.answer("s", score, ScoreAnswer.builder().value(3.0).build()));
        assertInvalid(SpringAiDecisionModel.answer("y", yesNo, ChoiceAnswer.builder().value("a").build()));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE,
                assertInstanceOf(Answer.Failed.class, SpringAiDecisionModel.answer("y", yesNo, null)).reason(),
                "no answer at all");
    }

    @Test
    void aBlankStateSpringAiRefusesFailsTheQuestionsInsteadOfThrowing() {
        var model = new SpringAiDecisionModel(scripted(prompt -> {
            throw new AssertionError("never called");
        }), "test");

        var failed = model.decide(DecisionRequest.of(" ", "q", new Question.Noul("Q?", null, null)))
                .answer("q", Answer.Failed.class).orElseThrow();

        assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
        assertTrue(failed.detail().startsWith("Spring AI refuses the request"), failed.detail());
    }

    private static void assertInvalid(Answer answer) {
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, assertInstanceOf(Answer.Failed.class, answer).reason(),
                String.valueOf(answer));
    }

    @Test
    void aBackendThatHangsIsATimeoutWithinTheDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var model = new SpringAiDecisionModel(scripted(prompt -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }), "test", SpringAiDecisionModel.DEFAULT_PRIORITY, 1);

        var start = System.nanoTime();
        var result = model.decide(request("q", new Question.Noul("Q?", null, null))
                .withTimeout(Duration.ofMillis(200)));
        var took = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(Answer.Failed.Reason.TIMEOUT, result.answer("q", Answer.Failed.class).orElseThrow().reason());
        assertTrue(took.compareTo(Duration.ofSeconds(2)) < 0, "decide returned on time: " + took);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the abandoned call is interrupted");
        for (var i = 0; i < 100 && model.freeSlots() == 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(1, model.freeSlots(), "the slot comes back once the call ends");
    }

    @Test
    void noFreeSlotBeforeTheDeadlineIsCapacity() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var model = new SpringAiDecisionModel(scripted(prompt -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DecisionResponse.builder(prompt)
                    .answer("q", YesNoAnswer.builder().probability(0.5).build()).build();
        }), "test", SpringAiDecisionModel.DEFAULT_PRIORITY, 1);
        var busy = Thread.startVirtualThread(() -> model.decide(request("q", new Question.Noul("Q?", null, null))
                .withTimeout(Duration.ofSeconds(5))));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var result = model.decide(request("q", new Question.Noul("Q?", null, null))
                    .withTimeout(Duration.ofMillis(100)));
            assertEquals(Answer.Failed.Reason.CAPACITY,
                    result.answer("q", Answer.Failed.class).orElseThrow().reason());
        } finally {
            release.countDown();
            busy.join(5_000);
        }
    }

    @Test
    void aBackendThatThrowsFailsEveryQuestionAsAnError() {
        var model = new SpringAiDecisionModel(scripted(prompt -> {
            throw new IllegalStateException("401 from the provider");
        }), "test");

        var failed = model.decide(request("q", new Question.Noul("Q?", null, null)))
                .answer("q", Answer.Failed.class).orElseThrow();

        assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
        assertTrue(failed.detail().contains("401"), failed.detail());
    }

    @Test
    void theProvidersModelAndUsageAreReported() {
        var model = new SpringAiDecisionModel(scripted(prompt -> DecisionResponse.builder(prompt)
                .answer("q", YesNoAnswer.builder().probability(0.4).build())
                .metadata(DecisionResponseMetadata.builder().model("d1-omni:2b")
                        .usage(new DefaultUsage(12, 3)).build())
                .build()), "test");

        var result = model.decide(request("q", new Question.Noul("Q?", null, null)));

        assertEquals("d1-omni:2b", result.model());
        var usage = result.usage().orElseThrow();
        assertEquals(12, usage.input());
        assertEquals(3, usage.output());
        assertEquals(15, usage.total());
    }

    private static LinkedHashMap<String, String> options(String... keys) {
        var options = new LinkedHashMap<String, String>();
        for (var key : keys) {
            options.put(key, "");
        }
        return options;
    }
}
