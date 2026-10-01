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
import org.atmosphere.ai.decision.DecisionModelResolverTestAccess;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.atmosphere.ai.decision.Question;
import org.atmosphere.ai.decision.TestDecisionModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IntentRouting}: its bounds, the question it asks, and how a decision
 * model's answer becomes a classification. Dispatch on both paths is pinned by
 * {@code IntentRoutingParityTest}.
 */
class IntentRoutingTest {

    private static final IntentHandler REPLY = decision -> "ok";

    @AfterEach
    void resetResolver() {
        DecisionModelResolverTestAccess.restore();
        TestDecisionModels.reset();
    }

    private static List<IntentRoute> routes() {
        return List.of(
                IntentRoute.handler("track", "where is my parcel", REPLY),
                IntentRoute.llm("general", "anything else"),
                IntentRoute.human("agent", "needs a person", REPLY));
    }

    private static IntentRouting routing(DecisionModel model) {
        return IntentRouting.of("Which team handles this?", routes().toArray(IntentRoute[]::new))
                .withDecisionModel(model);
    }

    // --- bounds ------------------------------------------------------------

    @Test
    void needsBetweenTwoAndSixteenRoutes() {
        var human = IntentRoute.human("agent", "", REPLY);
        assertThrows(IllegalArgumentException.class, () -> IntentRouting.of("pick", human));
        var many = new ArrayList<IntentRoute>();
        many.add(human);
        for (var i = 0; i < IntentRouting.MAX_ROUTES; i++) {
            many.add(IntentRoute.llm("llm" + i, ""));
        }
        assertThrows(IllegalArgumentException.class,
                () -> IntentRouting.of("pick", many.toArray(IntentRoute[]::new)));
        many.removeLast();
        assertEquals(IntentRouting.MAX_ROUTES,
                IntentRouting.of("pick", many.toArray(IntentRoute[]::new)).routes().size());
    }

    @Test
    void needsExactlyOneHumanRoute() {
        var none = assertThrows(IllegalArgumentException.class, () -> IntentRouting.of("pick",
                IntentRoute.llm("a", ""), IntentRoute.handler("b", "", REPLY)));
        assertTrue(none.getMessage().contains("exactly one Human"), none.getMessage());
        assertThrows(IllegalArgumentException.class, () -> IntentRouting.of("pick",
                IntentRoute.human("a", "", REPLY), IntentRoute.human("b", "", REPLY)));
    }

    @Test
    void rejectsDuplicateAndUnsafeRouteNames() {
        assertThrows(IllegalArgumentException.class, () -> IntentRouting.of("pick",
                IntentRoute.llm("same", ""), IntentRoute.human("same", "", REPLY)));
        assertThrows(IllegalArgumentException.class, () -> IntentRoute.llm("has space", ""));
        assertThrows(IllegalArgumentException.class, () -> IntentRoute.llm("a/b", ""));
        assertThrows(IllegalArgumentException.class, () -> IntentRoute.llm("", ""));
        assertThrows(IllegalArgumentException.class, () -> IntentRoute.llm("x".repeat(65), ""));
    }

    @Test
    void rejectsBlankInstructionsAndNonPositiveTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> IntentRouting.of(" ", routes().toArray(IntentRoute[]::new)));
        var routing = routing(null);
        assertThrows(IllegalArgumentException.class, () -> routing.withTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> routing.withConfirmTimeout(Duration.ofSeconds(-1)));
        assertEquals(DecisionRequest.DEFAULT_TIMEOUT, routing.timeout());
        assertEquals(IntentRouting.DEFAULT_CONFIRM_TIMEOUT, routing.confirmTimeout());
    }

    @Test
    void rejectsUnboundedTimeouts() {
        // "Wait forever" is a natural thing to write, and it overflowed Instant
        // when the confirmation expiry was computed: every CONFIRM turn threw.
        var routing = routing(null);
        var forever = java.time.temporal.ChronoUnit.FOREVER.getDuration();
        assertThrows(IllegalArgumentException.class, () -> routing.withConfirmTimeout(forever));
        assertThrows(IllegalArgumentException.class, () -> routing.withTimeout(forever));
        assertThrows(IllegalArgumentException.class,
                () -> routing.withConfirmTimeout(IntentRouting.MAX_CONFIRM_TIMEOUT.plusNanos(1)));
        assertThrows(IllegalArgumentException.class,
                () -> routing.withTimeout(IntentRouting.MAX_TIMEOUT.plusNanos(1)));
        assertEquals(IntentRouting.MAX_CONFIRM_TIMEOUT,
                routing.withConfirmTimeout(IntentRouting.MAX_CONFIRM_TIMEOUT).confirmTimeout());
        assertEquals(IntentRouting.MAX_TIMEOUT, routing.withTimeout(IntentRouting.MAX_TIMEOUT).timeout());
    }

    @Test
    void asksOneChoiceOverTheRoutesInOrder() {
        var question = routing(null).question();
        assertEquals("Which team handles this?", question.instructions());
        assertEquals(List.of("track", "general", "agent"), List.copyOf(question.options().keySet()));
        assertEquals("where is my parcel", question.options().get("track"));
    }

    // --- classification ----------------------------------------------------

    @Test
    void confidentChoiceActs() {
        var asked = new AtomicReference<DecisionRequest>();
        var model = scripted(request -> {
            asked.set(request);
            return choice("track", 0.95);
        });
        var classification = routing(model).withTimeout(Duration.ofSeconds(2)).classify("where is order 7?");

        assertEquals(Optional.of("track"), classification.choice());
        assertEquals(ConfidenceRoute.ACT, classification.tier());
        assertEquals(0.95, classification.confidence().aggregate().getAsDouble());
        assertEquals("where is order 7?", asked.get().state());
        assertEquals(Duration.ofSeconds(2), asked.get().timeout());
        assertTrue(asked.get().questions().get(IntentRouting.QUESTION_ID) instanceof Question.Choice);
    }

    @Test
    void tiersFollowTheConfiguredThresholds() {
        var model = scripted(request -> choice("track", 0.7));
        assertEquals(ConfidenceRoute.CONFIRM, routing(model).classify("m").tier());
        assertEquals(ConfidenceRoute.ACT,
                routing(model).withThresholds(ConfidenceRouting.of(0.6, 0.3)).classify("m").tier());
        assertEquals(ConfidenceRoute.ESCALATE,
                routing(model).withThresholds(ConfidenceRouting.of(0.95, 0.8)).classify("m").tier());
    }

    @Test
    void unmeasuredChoiceTakesTheUnknownRoute() {
        var model = scripted(request -> new Answer.Choice(IntentRouting.QUESTION_ID, "track", Map.of(),
                AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD)));
        var classification = routing(model).classify("m");
        assertEquals(ConfidenceRoute.ESCALATE, classification.tier());
        assertEquals(Optional.of("track"), classification.choice(),
                "the choice is kept so the observer sees what the model said");
        assertEquals(ConfidenceRoute.CONFIRM, routing(model)
                .withThresholds(ConfidenceRouting.defaults().withUnknownRoute(ConfidenceRoute.CONFIRM))
                .classify("m").tier());
    }

    @Test
    void failedAnswerEscalatesEvenWhenUnknownWouldAct() {
        var model = scripted(request -> new Answer.Failed(IntentRouting.QUESTION_ID,
                Answer.Failed.Reason.TIMEOUT, "deadline passed"));
        var permissive = routing(model)
                .withThresholds(ConfidenceRouting.defaults().withUnknownRoute(ConfidenceRoute.ACT));
        var classification = permissive.classify("m");
        assertEquals(ConfidenceRoute.ESCALATE, classification.tier());
        assertTrue(classification.choice().isEmpty());
        assertTrue(classification.confidence().aggregate().isEmpty());
        assertTrue(classification.reason().contains("timeout"), classification.reason());
    }

    @Test
    void everyFailureEscalatesWithoutAChoice() {
        var throwing = scripted(request -> {
            throw new IllegalStateException("backend down");
        });
        assertUnanswered(routing(throwing).classify("m"), "backend down");

        var missing = new DecisionModel() {
            @Override public String name() { return "missing"; }
            @Override public boolean isAvailable() { return true; }
            @Override public DecisionResult decide(DecisionRequest request) {
                return new DecisionResult(name(), Map.of(), Optional.empty(), Duration.ZERO);
            }
        };
        assertUnanswered(routing(missing).classify("m"), "no answer");

        var wrongType = scripted(request -> new Answer.Noul(IntentRouting.QUESTION_ID, true,
                java.util.OptionalDouble.empty(), AiConfidence.reported(0.99)));
        assertUnanswered(routing(wrongType).classify("m"), "unexpected answer type");

        var outOfSet = scripted(request -> choice("refund", 0.99));
        assertUnanswered(routing(outOfSet).classify("m"), "not a route");

        var neverCalled = scripted(request -> choice("track", 0.99));
        assertUnanswered(routing(neverCalled).classify("x".repeat(DecisionRequest.MAX_STATE_CHARS + 1)),
                "cannot be classified");
    }

    @Test
    void noDecisionModelEscalates() {
        // Only the canned demo runtime: no model can answer, so an
        // unconfigured deployment fails closed instead of guessing a route.
        DecisionModelResolverTestAccess.forceDemoOnly();
        assertTrue(DecisionModelResolver.resolve().isEmpty(), "precondition: no model resolves");
        assertUnanswered(routing(null).classify("m"), "no decision model");
    }

    @Test
    void withoutAnExplicitModelTheResolvedOneIsAsked() {
        TestDecisionModels.Preferred.available = true;
        TestDecisionModels.Preferred.behaviour = request -> new DecisionResult("test-preferred",
                Map.of(IntentRouting.QUESTION_ID, choice("general", 0.97)), Optional.empty(), Duration.ZERO);
        DecisionModelResolver.reset();

        var classification = routing(null).classify("tell me a joke");
        assertEquals(Optional.of("general"), classification.choice());
        assertEquals(ConfidenceRoute.ACT, classification.tier());
        assertTrue(classification.reason().contains("test-preferred"), classification.reason());
    }

    @Test
    void resolvesFromRequestMetadata() {
        var routing = routing(null);
        assertSame(routing, IntentRouting.from(Map.of(IntentRouting.METADATA_KEY, routing)));
        assertEquals(null, IntentRouting.from(Map.of(IntentRouting.METADATA_KEY, "not a routing")));
        assertEquals(null, IntentRouting.from(null));
    }

    private static void assertUnanswered(IntentRouting.Classification classification, String reason) {
        assertEquals(ConfidenceRoute.ESCALATE, classification.tier());
        assertTrue(classification.choice().isEmpty(), classification.toString());
        assertTrue(classification.confidence().aggregate().isEmpty());
        assertTrue(classification.reason().contains(reason), classification.reason());
    }

    static Answer choice(String route, double confidence) {
        return new Answer.Choice(IntentRouting.QUESTION_ID, route, Map.of(), AiConfidence.reported(confidence));
    }

    static DecisionModel scripted(Function<DecisionRequest, Answer> script) {
        return new DecisionModel() {
            @Override public String name() { return "scripted"; }
            @Override public boolean isAvailable() { return true; }
            @Override public DecisionResult decide(DecisionRequest request) {
                var answer = script.apply(request);
                return new DecisionResult(name(), Map.of(answer.id(), answer), Optional.empty(), Duration.ZERO);
            }
        };
    }
}
