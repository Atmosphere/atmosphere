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
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.atmosphere.ai.decision.Question;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An Atmosphere {@link DecisionModel} over a Spring AI 2.1
 * {@code org.springframework.ai.decision.DecisionModel}: the OpenAI decisions
 * API, an Ollama decision model, {@code spring-ai-systemone} or any other
 * backend Spring AI ships. Both projects name their types {@code DecisionModel}
 * and {@code DecisionResult}, and the two {@code DecisionResult}s differ (one
 * answer there, all of a request's answers here): the Spring AI types are
 * always written fully qualified in this class.
 *
 * <p>The auto-configuration registers the application's Spring AI decision
 * model with {@link org.atmosphere.ai.decision.DecisionModelResolver#register}
 * for the application's {@link org.atmosphere.ai.AiEnvironment}, so the
 * governance tiers and {@link org.atmosphere.ai.intent.IntentRouting} ask it.</p>
 *
 * <h2>Mapping</h2>
 * <ul>
 *   <li>A request is one Spring AI call: its state is the text input, every
 *       question goes out under its own id.</li>
 *   <li>{@link Question.Noul} is a yes/no question; the answer's
 *       {@code p(yes)} is {@link Answer.Noul#probabilityTrue()}, its value is
 *       {@code p >= 0.5}, and its confidence is {@code |2p - 1|}, the margin of
 *       the two-value distribution (as for the TypeSafe adapter).</li>
 *   <li>{@link Question.Choice} is a choice question over the same option keys
 *       and descriptions; the answer's probabilities and confidence are kept as
 *       the provider returned them.</li>
 *   <li>{@link Question.Score} is a score question whose level {@code i} is
 *       labelled {@code "i"} and described by the rubric's level text; the
 *       answer's per-level probabilities become level → probability.</li>
 *   <li>A refusal fails its question ({@link Answer.Failed.Reason#ERROR}, the
 *       provider's reason in the detail), never a guessed answer.</li>
 * </ul>
 * Confidences are tagged {@link AiConfidence.Source#PROVIDER_DISTRIBUTION}. A
 * provider that returns no distribution leaves the probability maps empty, and
 * one that returns no confidence leaves it unknown (Correctness Invariant #5).
 * Spring AI's own {@code DecisionResponse} builder refuses a response that skips
 * a question, answers outside its options or levels, or carries a probability
 * outside {@code [0, 1]}: the backend's call then throws, and every question
 * fails as {@link Answer.Failed.Reason#ERROR} with Spring AI's message. This
 * class checks each answer again, so one that reaches it anyway fails as
 * {@link Answer.Failed.Reason#INVALID_ANSWER} (or
 * {@link Answer.Failed.Reason#UNPARSEABLE} when missing), never guessed.
 *
 * <h2>Bounds</h2>
 * {@link #decide} returns within the request's timeout whatever the backend
 * does: the call runs on a virtual thread that is interrupted at the deadline,
 * and its questions fail as {@link Answer.Failed.Reason#TIMEOUT}. At most
 * {@code maxConcurrency} calls are in flight; a request that finds no free slot
 * before its deadline fails as {@link Answer.Failed.Reason#CAPACITY}.
 *
 * <p>The Spring AI model belongs to the application context; this class never
 * closes it. {@link #isAvailable()} is {@code true}: the bean is a configured
 * client, and Spring AI offers no probe that answers without a billed call. A
 * backend that is down fails its questions as {@link Answer.Failed.Reason#ERROR}.</p>
 */
public final class SpringAiDecisionModel implements DecisionModel {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiDecisionModel.class);

    /**
     * Default selection priority: above the TypeSafe adapter's, because a
     * decision model the application defined as a bean is a choice it made,
     * while a ServiceLoader registration is one its classpath brought.
     */
    public static final int DEFAULT_PRIORITY = 200;

    /** Default number of calls in flight. */
    public static final int DEFAULT_MAX_CONCURRENCY = 8;

    private final org.springframework.ai.decision.DecisionModel delegate;
    private final String name;
    private final int priority;
    private final Semaphore slots;

    /**
     * An adapter with the {@link #DEFAULT_PRIORITY} and {@link #DEFAULT_MAX_CONCURRENCY}.
     *
     * @param delegate the Spring AI decision model; the caller keeps ownership
     * @param name     the name used in logs and audit reasons
     */
    public SpringAiDecisionModel(org.springframework.ai.decision.DecisionModel delegate, String name) {
        this(delegate, name, DEFAULT_PRIORITY, DEFAULT_MAX_CONCURRENCY);
    }

    /**
     * @param delegate       the Spring AI decision model; the caller keeps ownership
     * @param name           the name used in logs and audit reasons
     * @param priority       the selection priority among decision models
     * @param maxConcurrency calls in flight, at least 1
     */
    public SpringAiDecisionModel(org.springframework.ai.decision.DecisionModel delegate, String name,
                                 int priority, int maxConcurrency) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1, got " + maxConcurrency);
        }
        this.name = name;
        this.priority = priority;
        this.slots = new Semaphore(maxConcurrency, true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public DecisionResult decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        var start = System.nanoTime();
        var deadline = start + request.timeout().toNanos();
        org.springframework.ai.decision.DecisionPrompt prompt;
        try {
            prompt = prompt(request);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A request ours accepts that Spring AI's builders refuse (a blank state).
            return failed(request, start, Answer.Failed.Reason.ERROR, "Spring AI refuses the request: "
                    + e.getMessage());
        }
        try {
            if (!slots.tryAcquire(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return failed(request, start, Answer.Failed.Reason.CAPACITY,
                        "no free slot before the deadline (" + slots.availablePermits() + " free)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(request, start, Answer.Failed.Reason.ERROR, "interrupted while waiting for a slot");
        }
        var call = new CompletableFuture<org.springframework.ai.decision.DecisionResponse>();
        Thread worker;
        try {
            // The slot is released when the call ends, not when decide returns:
            // a call abandoned at the deadline still holds a connection.
            worker = Thread.ofVirtual().name("atmosphere-spring-ai-decision").start(() -> {
                try {
                    call.complete(delegate.call(prompt));
                } catch (Throwable t) {
                    call.completeExceptionally(t);
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            slots.release();
            return failed(request, start, Answer.Failed.Reason.ERROR, "the call could not start: " + e);
        }
        org.springframework.ai.decision.DecisionResponse response;
        try {
            response = call.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            worker.interrupt();
            return failed(request, start, Answer.Failed.Reason.TIMEOUT,
                    "no answer within " + request.timeout().toMillis() + " ms");
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return failed(request, start, Answer.Failed.Reason.ERROR, "interrupted");
        } catch (ExecutionException e) {
            logger.debug("Spring AI decision model {} failed", name, e.getCause());
            return failed(request, start, Answer.Failed.Reason.ERROR, "the call failed: " + e.getCause());
        }
        if (response == null) {
            return failed(request, start, Answer.Failed.Reason.UNPARSEABLE, "the model returned no response");
        }
        var answers = new LinkedHashMap<String, Answer>();
        for (var entry : request.questions().entrySet()) {
            var id = entry.getKey();
            Answer answer;
            try {
                answer = answer(id, entry.getValue(), response.hasAnswer(id) ? response.getAnswer(id) : null);
            } catch (RuntimeException e) {
                logger.debug("Spring AI decision model {}: answer {} could not be read", name, id, e);
                answer = new Answer.Failed(id, Answer.Failed.Reason.UNPARSEABLE, "the answer could not be read: " + e);
            }
            answers.put(id, answer);
        }
        var metadata = response.getMetadata();
        var model = metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()
                ? metadata.getModel() : name;
        return new DecisionResult(model, answers, usage(metadata, model), elapsed(start));
    }

    /** The Spring AI prompt standing for {@code request}: its state as text, its questions under their ids. */
    static org.springframework.ai.decision.DecisionPrompt prompt(DecisionRequest request) {
        var questions = new LinkedHashMap<String, org.springframework.ai.decision.question.DecisionQuestion>();
        for (var entry : request.questions().entrySet()) {
            questions.put(entry.getKey(), question(entry.getValue()));
        }
        return org.springframework.ai.decision.DecisionPrompt.builder()
                .text(request.state())
                .questions(questions)
                .build();
    }

    private static org.springframework.ai.decision.question.DecisionQuestion question(Question question) {
        return switch (question) {
            case Question.Noul noul -> {
                var builder = org.springframework.ai.decision.question.YesNoQuestion.builder()
                        .text(noul.instructions());
                if (noul.whenTrue() != null) {
                    builder.whenYes(noul.whenTrue());
                }
                if (noul.whenFalse() != null) {
                    builder.whenNo(noul.whenFalse());
                }
                yield builder.build();
            }
            case Question.Choice choice -> {
                var builder = org.springframework.ai.decision.question.ChoiceQuestion.builder()
                        .text(choice.instructions());
                for (var option : choice.options().entrySet()) {
                    if (option.getValue().isBlank()) {
                        builder.option(option.getKey());
                    } else {
                        builder.option(option.getKey(), option.getValue());
                    }
                }
                yield builder.build();
            }
            case Question.Score score -> {
                var builder = org.springframework.ai.decision.question.ScoreQuestion.builder()
                        .text(score.instructions());
                for (var level = 0; level < score.levels().size(); level++) {
                    builder.level(Integer.toString(level), score.levels().get(level));
                }
                yield builder.build();
            }
        };
    }

    /** Our answer to {@code question} from the provider's {@code answer} ({@code null}: none came back). */
    static Answer answer(String id, Question question,
                         org.springframework.ai.decision.answer.DecisionAnswer answer) {
        if (answer == null) {
            return new Answer.Failed(id, Answer.Failed.Reason.UNPARSEABLE, "the model returned no answer to it");
        }
        if (answer instanceof org.springframework.ai.decision.answer.RefusalAnswer refusal) {
            var reason = refusal.getReason();
            return new Answer.Failed(id, Answer.Failed.Reason.ERROR, "the model refused to answer"
                    + (reason == null || reason.isBlank() ? "" : ": " + reason));
        }
        return switch (question) {
            case Question.Noul ignored
                    when answer instanceof org.springframework.ai.decision.answer.YesNoAnswer yesNo -> noul(id, yesNo);
            case Question.Choice choice
                    when answer instanceof org.springframework.ai.decision.answer.ChoiceAnswer picked ->
                    choice(id, choice, picked);
            case Question.Score score
                    when answer instanceof org.springframework.ai.decision.answer.ScoreAnswer scored ->
                    score(id, score, scored);
            default -> new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER, "a "
                    + answer.getClass().getSimpleName() + " does not answer a "
                    + question.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT) + " question");
        };
    }

    private static Answer noul(String id, org.springframework.ai.decision.answer.YesNoAnswer answer) {
        var p = answer.getProbability();
        if (!isProbability(p)) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER, "p(yes) " + p + " is outside [0, 1]");
        }
        return new Answer.Noul(id, p >= 0.5, OptionalDouble.of(p), confidence(Math.abs(2.0 * p - 1.0)));
    }

    private static Answer choice(String id, Question.Choice question,
                                 org.springframework.ai.decision.answer.ChoiceAnswer answer) {
        var value = answer.getValue();
        if (value == null || !question.options().containsKey(value)) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "choice '" + value + "' is not one of the options");
        }
        var probabilities = new LinkedHashMap<String, Double>();
        var returned = answer.getProbabilities();
        if (returned != null) {
            for (var entry : returned.entrySet()) {
                if (!question.options().containsKey(entry.getKey())) {
                    return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                            "probabilities name '" + entry.getKey() + "', which is not an option");
                }
                if (entry.getValue() == null || !isProbability(entry.getValue())) {
                    return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                            "probability " + entry.getValue() + " of '" + entry.getKey() + "' is outside [0, 1]");
                }
                probabilities.put(entry.getKey(), entry.getValue());
            }
        }
        var invalid = invalidConfidence(id, answer.getConfidence());
        if (invalid != null) {
            return invalid;
        }
        return new Answer.Choice(id, value, probabilities, providerConfidence(answer.getConfidence()));
    }

    private static Answer score(String id, Question.Score question,
                                org.springframework.ai.decision.answer.ScoreAnswer answer) {
        var levels = question.levels().size();
        var value = answer.getValue();
        if (!Double.isFinite(value) || value < 0.0 || value > levels - 1) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "score " + value + " is outside [0, " + (levels - 1) + "]");
        }
        var probabilities = new LinkedHashMap<Integer, Double>();
        List<Double> returned = answer.getProbabilities();
        if (returned != null && !returned.isEmpty()) {
            if (returned.size() != levels) {
                return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                        "probabilities name " + returned.size() + " levels, the rubric has " + levels);
            }
            for (var level = 0; level < levels; level++) {
                var p = returned.get(level);
                if (p == null || !isProbability(p)) {
                    return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                            "probability " + p + " of level " + level + " is outside [0, 1]");
                }
                probabilities.put(level, p);
            }
        }
        var invalid = invalidConfidence(id, answer.getConfidence());
        if (invalid != null) {
            return invalid;
        }
        return new Answer.Score(id, value, probabilities, providerConfidence(answer.getConfidence()));
    }

    /** The failed answer a reported confidence outside {@code [0, 1]} makes, else {@code null}. */
    private static Answer.Failed invalidConfidence(String id, Double reported) {
        return reported != null && !isProbability(reported)
                ? new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER, "confidence " + reported + " is outside [0, 1]")
                : null;
    }

    /** The provider's confidence; unknown, never invented, when it returned none. */
    private static AiConfidence providerConfidence(Double reported) {
        return reported == null ? AiConfidence.unknown(AiConfidence.Source.PROVIDER_DISTRIBUTION)
                : confidence(reported);
    }

    private static AiConfidence confidence(double aggregate) {
        return new AiConfidence(OptionalDouble.of(aggregate), List.of(), AiConfidence.Source.PROVIDER_DISTRIBUTION);
    }

    private static boolean isProbability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static Optional<TokenUsage> usage(org.springframework.ai.decision.DecisionResponseMetadata metadata,
                                              String model) {
        if (metadata == null || metadata.getUsage() == null) {
            return Optional.empty();
        }
        var usage = metadata.getUsage();
        var input = usage.getPromptTokens();
        var output = usage.getCompletionTokens();
        if (input == null && output == null) {
            return Optional.empty();
        }
        var in = input == null ? 0L : input;
        var out = output == null ? 0L : output;
        var total = usage.getTotalTokens() != null ? usage.getTotalTokens() : in + out;
        return Optional.of(TokenUsage.of(in, out, total, model));
    }

    private DecisionResult failed(DecisionRequest request, long start, Answer.Failed.Reason reason, String detail) {
        var answers = new LinkedHashMap<String, Answer>();
        for (var id : request.questions().keySet()) {
            answers.put(id, new Answer.Failed(id, reason, detail));
        }
        return new DecisionResult(name, answers, Optional.empty(), elapsed(start));
    }

    private static Duration elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    @Override
    public String toString() {
        return "SpringAiDecisionModel[" + name + "]";
    }

    /** Test hook: free slots. */
    int freeSlots() {
        return slots.availablePermits();
    }
}
