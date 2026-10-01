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
package org.atmosphere.ai.intent;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.ConfidenceRoute;
import org.atmosphere.ai.ConfidenceRouting;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.Question;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Intent routing: before a request reaches the LLM, one
 * {@link Question.Choice} over the configured {@link IntentRoute routes} decides
 * which handler takes it — a deterministic Java callback, the normal LLM path,
 * or a person.
 *
 * <p>It composes with {@link org.atmosphere.ai.ModelRouter}: intent routing picks
 * the <em>handler</em> before dispatch; when the choice is an
 * {@link IntentRoute.Llm} route, the request continues down the normal path and
 * the runtime (including a {@code ModelRouter}-backed one) still picks the model.</p>
 *
 * <h2>Whether to act on the choice</h2>
 * The choice's confidence is gated by {@link #thresholds()}, the same
 * {@link ConfidenceRouting} tiers a completed turn is routed on:
 * <ul>
 *   <li>{@link ConfidenceRoute#ACT} — the chosen route runs.</li>
 *   <li>{@link ConfidenceRoute#CONFIRM} — the requester is asked to confirm
 *       through the approval machinery tools use ({@code /__approval/<id>/approve}
 *       on the same session); approved runs the chosen route, anything else
 *       (denied, timed out after {@link #confirmTimeout()}) escalates. A
 *       surface with no channel for the answer (an {@code AiPipeline} with
 *       {@code setIntentConfirmationAvailable(false)}, as the OpenAI-compatible
 *       and batch serving of an endpoint are) escalates at once.</li>
 *   <li>{@link ConfidenceRoute#ESCALATE} — the {@link IntentRoute.Human} route runs.</li>
 * </ul>
 * Fail closed: no decision model, an {@link Answer.Failed} (timeout, capacity,
 * error, unparseable or out-of-set reply), a message the request cannot carry,
 * or a model that throws all escalate, whatever {@link ConfidenceRouting#unknownRoute()}
 * says — there is no choice to act on. {@code unknownRoute} applies only to a
 * valid choice that carries no confidence at all. Outside the Built-in runtime
 * with logprobs, the confidence a {@code RuntimeDecisionModel} answers with is
 * the model's self-reported number, and the tiers gate on it. Only the thresholds and the
 * unknown route of {@link #thresholds()} are read; its decision handler is not
 * called (use {@link #onDecision()}).
 *
 * <h2>Where it runs</h2>
 * On both dispatch paths, after admission (request guardrails, governance
 * policies, and on the endpoint {@code AiInterceptor} pre-processing and the
 * per-request scope — a denied request is never classified) and before RAG
 * retrieval and the LLM call. A request a scope policy rewrote to its redirect
 * text is not classified and continues to the LLM path. A cancelled turn
 * (client disconnect, interrupted dispatching thread) runs no route:
 * the session errors with a {@code CancellationException}. It runs on
 * {@code @AiEndpoint} ({@code @AiEndpoint(intentRouting = ...)},
 * {@code AiStreamingSession#setIntentRouting}, or the {@value #METADATA_KEY}
 * request metadata set by an {@code AiInterceptor}) and
 * {@link org.atmosphere.ai.AiPipeline} ({@code setDefaultIntentRouting}, or the
 * same metadata key). Request metadata wins over the default.
 *
 * <h2>Wire signal</h2>
 * Before any reply, the turn carries {@value #ROUTE_METADATA_KEY} (the route
 * taken), {@value #TIER_METADATA_KEY}, {@value #CHOICE_METADATA_KEY} (absent
 * when the model gave no answer) and {@value #CONFIDENCE_METADATA_KEY} (absent
 * when unknown).
 *
 * @param instructions   what the decision model is asked, e.g. "Which team should
 *                       handle this customer message?"
 * @param routes         {@value #MIN_ROUTES}..{@value #MAX_ROUTES} routes with unique
 *                       names, exactly one of them {@link IntentRoute.Human}
 * @param thresholds     the ACT / CONFIRM / ESCALATE tiers for the choice's confidence
 * @param timeout        bound on the classification call
 * @param confirmTimeout how long a CONFIRM-tier request waits for the requester
 * @param decisionModel  the model to ask; {@code null} resolves one through
 *                       {@link DecisionModelResolver} on every request
 * @param onDecision     receives every decision, or {@code null}; called on the
 *                       dispatching thread before the route runs, so it must not
 *                       block, and a handler that throws is logged and ignored
 */
public record IntentRouting(String instructions, List<IntentRoute> routes, ConfidenceRouting thresholds,
                            Duration timeout, Duration confirmTimeout, DecisionModel decisionModel,
                            Consumer<IntentDecision> onDecision) {

    /** Request metadata key carrying a per-request {@link IntentRouting}. */
    public static final String METADATA_KEY = "ai.intent.routing";

    /** Wire metadata key: the name of the route the request took. */
    public static final String ROUTE_METADATA_KEY = "ai.intent.route";

    /** Wire metadata key: the route the decision model chose. */
    public static final String CHOICE_METADATA_KEY = "ai.intent.choice";

    /** Wire metadata key: the {@link ConfidenceRoute} tier of the choice. */
    public static final String TIER_METADATA_KEY = "ai.intent.tier";

    /** Wire metadata key: the choice's confidence aggregate. */
    public static final String CONFIDENCE_METADATA_KEY = "ai.intent.confidence";

    /** The decision question id. */
    public static final String QUESTION_ID = "intent";

    /** Fewest routes a routing may declare (a choice needs two options). */
    public static final int MIN_ROUTES = Question.Choice.MIN_OPTIONS;

    /**
     * Most routes a routing may declare: the largest choice the
     * {@code RuntimeDecisionModel} still answers with single-letter codes, so
     * its confidence can come from the model's distribution over the routes
     * rather than a self-reported number.
     */
    public static final int MAX_ROUTES = 16;

    /** Default wait for a CONFIRM-tier confirmation. */
    public static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofMinutes(2);

    public IntentRouting {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions must not be blank");
        }
        Objects.requireNonNull(routes, "routes");
        if (routes.size() < MIN_ROUTES || routes.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("intent routing needs " + MIN_ROUTES + ".." + MAX_ROUTES
                    + " routes, got " + routes.size());
        }
        var names = new HashSet<String>();
        var humans = 0;
        for (var route : routes) {
            Objects.requireNonNull(route, "route");
            if (!names.add(route.name())) {
                throw new IllegalArgumentException("duplicate route name '" + route.name() + "'");
            }
            if (route instanceof IntentRoute.Human) {
                humans++;
            }
        }
        if (humans != 1) {
            throw new IllegalArgumentException("intent routing needs exactly one Human route "
                    + "(escalation must have somewhere to go), got " + humans);
        }
        routes = List.copyOf(routes);
        Objects.requireNonNull(thresholds, "thresholds");
        timeout = timeout == null ? DecisionRequest.DEFAULT_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        confirmTimeout = confirmTimeout == null ? DEFAULT_CONFIRM_TIMEOUT : confirmTimeout;
        if (confirmTimeout.isNegative() || confirmTimeout.isZero()) {
            throw new IllegalArgumentException("confirmTimeout must be positive, got " + confirmTimeout);
        }
    }

    /**
     * Routes with the default tiers ({@link ConfidenceRouting#defaults()}), timeout
     * and confirmation wait, resolving the decision model per request.
     */
    public static IntentRouting of(String instructions, IntentRoute... routes) {
        return new IntentRouting(instructions, Arrays.asList(routes), ConfidenceRouting.defaults(),
                null, null, null, null);
    }

    /** Same routing with other confidence tiers. */
    public IntentRouting withThresholds(ConfidenceRouting thresholds) {
        return new IntentRouting(instructions, routes, thresholds, timeout, confirmTimeout,
                decisionModel, onDecision);
    }

    /** Same routing with another bound on the classification call. */
    public IntentRouting withTimeout(Duration timeout) {
        return new IntentRouting(instructions, routes, thresholds, timeout, confirmTimeout,
                decisionModel, onDecision);
    }

    /** Same routing with another wait for a CONFIRM-tier confirmation. */
    public IntentRouting withConfirmTimeout(Duration confirmTimeout) {
        return new IntentRouting(instructions, routes, thresholds, timeout, confirmTimeout,
                decisionModel, onDecision);
    }

    /** Same routing, always asking {@code model} instead of resolving one. */
    public IntentRouting withDecisionModel(DecisionModel model) {
        return new IntentRouting(instructions, routes, thresholds, timeout, confirmTimeout,
                model, onDecision);
    }

    /** Same routing with a decision observer. */
    public IntentRouting withHandler(Consumer<IntentDecision> handler) {
        return new IntentRouting(instructions, routes, thresholds, timeout, confirmTimeout,
                decisionModel, handler);
    }

    /** The route named {@code name}, if declared. */
    public Optional<IntentRoute> route(String name) {
        for (var route : routes) {
            if (route.name().equals(name)) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }

    /** The one {@link IntentRoute.Human} route. */
    public IntentRoute.Human humanRoute() {
        for (var route : routes) {
            if (route instanceof IntentRoute.Human human) {
                return human;
            }
        }
        throw new IllegalStateException("validated in the constructor");
    }

    /** The question asked: one choice over the route names, described by the route descriptions. */
    public Question.Choice question() {
        var options = new LinkedHashMap<String, String>();
        for (var route : routes) {
            options.put(route.name(), route.description());
        }
        return new Question.Choice(instructions, options);
    }

    /**
     * What the decision model said about {@code message}, and the tier of its
     * confidence. Never throws: every failure is an unanswered classification
     * at {@link ConfidenceRoute#ESCALATE}.
     *
     * @param message the text to classify
     * @return the classification
     */
    public Classification classify(String message) {
        DecisionModel model;
        try {
            model = decisionModel != null ? decisionModel : DecisionModelResolver.resolve().orElse(null);
        } catch (RuntimeException e) {
            return Classification.unanswered("decision model resolution failed: " + e);
        }
        if (model == null) {
            return Classification.unanswered("no decision model is available");
        }
        DecisionRequest request;
        try {
            request = DecisionRequest.of(message == null ? "" : message, QUESTION_ID, question())
                    .withTimeout(timeout);
        } catch (IllegalArgumentException e) {
            return Classification.unanswered("the message cannot be classified: " + e.getMessage());
        }
        Answer answer;
        try {
            answer = model.decide(request).answers().get(QUESTION_ID);
        } catch (RuntimeException e) {
            return Classification.unanswered("decision model " + model.name() + " failed: " + e);
        }
        return switch (answer) {
            case null -> Classification.unanswered("decision model " + model.name() + " gave no answer");
            case Answer.Failed failed -> Classification.unanswered(
                    failed.reason().name().toLowerCase(Locale.ROOT) + ": " + failed.detail());
            case Answer.Choice choice -> {
                if (route(choice.choice()).isEmpty()) {
                    yield Classification.unanswered("answer '" + choice.choice() + "' is not a route");
                }
                var tier = thresholds.route(choice.confidence());
                var aggregate = choice.confidence().aggregate();
                yield new Classification(Optional.of(choice.choice()), tier, choice.confidence(),
                        String.format(Locale.ROOT, "%s chose '%s' at %s [%s] -> %s", model.name(),
                                choice.choice(),
                                aggregate.isPresent()
                                        ? String.format(Locale.ROOT, "%.3f", aggregate.getAsDouble())
                                        : "unknown confidence",
                                choice.confidence().source(), tier));
            }
            default -> Classification.unanswered("unexpected answer type "
                    + answer.getClass().getSimpleName());
        };
    }

    /**
     * The decision model's verdict on one message.
     *
     * @param choice     the chosen route name; empty when unanswered
     * @param tier       the confidence tier; always {@link ConfidenceRoute#ESCALATE}
     *                   when unanswered
     * @param confidence the confidence of the choice; unknown when unanswered
     * @param reason     how the verdict was reached
     */
    public record Classification(Optional<String> choice, ConfidenceRoute tier,
                                 AiConfidence confidence, String reason) {

        public Classification {
            choice = choice == null ? Optional.empty() : choice;
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(confidence, "confidence");
            reason = reason == null ? "" : reason;
        }

        static Classification unanswered(String reason) {
            return new Classification(Optional.empty(), ConfidenceRoute.ESCALATE,
                    AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD), reason);
        }
    }

    /** Extract a routing from request metadata; {@code null} when absent. */
    public static IntentRouting from(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.get(METADATA_KEY) instanceof IntentRouting r ? r : null;
    }
}
