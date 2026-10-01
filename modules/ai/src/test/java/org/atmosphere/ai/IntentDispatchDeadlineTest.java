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
package org.atmosphere.ai;

import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.atmosphere.ai.intent.IntentDecision;
import org.atmosphere.ai.intent.IntentRoute;
import org.atmosphere.ai.intent.IntentRouting;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CONFIRM-tier wait against a turn deadline: the surface that ends the turn
 * on its own (the {@code @AiEndpoint} prompt watchdog) must not outlive the
 * wait, or an unanswered confirmation runs no route at all.
 */
class IntentDispatchDeadlineTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void withoutADeadlineTheWaitIsTheConfirmTimeout() {
        assertEquals(Optional.of(NOW.plusSeconds(120)),
                IntentDispatch.confirmationExpiry(NOW, Duration.ofMinutes(2), null));
    }

    @Test
    void aWaitShorterThanTheDeadlineIsKept() {
        assertEquals(Optional.of(NOW.plusSeconds(30)),
                IntentDispatch.confirmationExpiry(NOW, Duration.ofSeconds(30), NOW.plusSeconds(120)));
    }

    @Test
    void aWaitAsLongAsTheDeadlineEndsTheMarginBeforeIt() {
        // The defaults: confirmTimeout == @AiEndpoint.timeout().
        assertEquals(Optional.of(NOW.plusSeconds(115)),
                IntentDispatch.confirmationExpiry(NOW, Duration.ofMinutes(2), NOW.plusSeconds(120)));
    }

    @Test
    void aShortDeadlineKeepsATenthBack() {
        assertEquals(Optional.of(NOW.plusMillis(1_350)),
                IntentDispatch.confirmationExpiry(NOW, Duration.ofSeconds(30), NOW.plusMillis(1_500)));
    }

    @Test
    void aPassedDeadlineLeavesNoWait() {
        assertEquals(Optional.empty(),
                IntentDispatch.confirmationExpiry(NOW, Duration.ofSeconds(30), NOW));
        assertEquals(Optional.empty(),
                IntentDispatch.confirmationExpiry(NOW, Duration.ofSeconds(30), NOW.minusSeconds(1)));
    }

    @Test
    void anOverflowingWaitIsCappedNotThrown() {
        var forever = java.time.temporal.ChronoUnit.FOREVER.getDuration();
        // Capped by the deadline: the margin before it, never now + forever.
        assertEquals(Optional.of(NOW.plusSeconds(115)),
                IntentDispatch.confirmationExpiry(NOW, forever, NOW.plusSeconds(120)));
        // No deadline: saturates at Instant.MAX.
        assertEquals(Optional.of(Instant.MAX), IntentDispatch.confirmationExpiry(NOW, forever, null));
    }

    @Test
    void aConfirmationThatCannotBeAskedEscalatesInsteadOfThrowing() {
        // Anything that breaks while asking (here the session id the approval
        // is filed under) must still end the turn on the human route.
        var decisions = new CopyOnWriteArrayList<IntentDecision>();
        var wire = new IntentRoutingParityTest.Wire() {
            @Override
            public String sessionId() {
                throw new IllegalStateException("session gone");
            }
        };

        var outcome = IntentDispatch.route(step(routing(decisions), wire, new ApprovalRegistry(), null));

        assertEquals(IntentDispatch.Outcome.HANDLED, outcome);
        assertTrue(wire.completed);
        assertTrue(wire.approvals.isEmpty());
        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("CONFIRM", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertTrue(decisions.getFirst().reason().contains("confirmation failed"), decisions.getFirst().reason());
    }

    @Test
    void noTimeLeftEscalatesAtOnceWithoutAskingTheRequester() {
        var decisions = new CopyOnWriteArrayList<IntentDecision>();
        var wire = new IntentRoutingParityTest.Wire();
        var registry = new ApprovalRegistry();

        var outcome = IntentDispatch.route(step(routing(decisions), wire, registry, Instant.now().minusSeconds(1)));

        assertEquals(IntentDispatch.Outcome.HANDLED, outcome);
        assertTrue(wire.approvals.isEmpty(), "no confirmation is asked when it cannot be waited for");
        assertTrue(wire.completed);
        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertTrue(wire.frames.contains(Map.entry("text", "a person will follow up")), wire.frames.toString());
        assertTrue(decisions.getFirst().reason().contains("no time left to confirm"), decisions.getFirst().reason());
    }

    @Test
    void anUnansweredConfirmationEscalatesBeforeTheDeadline() {
        var decisions = new CopyOnWriteArrayList<IntentDecision>();
        var wire = new IntentRoutingParityTest.Wire();
        var deadline = Instant.now().plusMillis(1_500);

        IntentDispatch.route(step(routing(decisions).withConfirmTimeout(Duration.ofMinutes(5)),
                wire, new ApprovalRegistry(), deadline));

        assertTrue(Instant.now().isBefore(deadline), "the wait must end before the turn deadline");
        assertEquals(1, wire.approvals.size());
        assertTrue(wire.approvals.getFirst().expiresIn() <= 1,
                "expiresIn advertises the wait kept, not confirmTimeout: " + wire.approvals.getFirst());
        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertTrue(decisions.getFirst().reason().contains("confirmation timed_out"), decisions.getFirst().reason());
    }

    /** "track" at 0.7: the CONFIRM tier under the default thresholds. */
    private static IntentRouting routing(List<IntentDecision> decisions) {
        return IntentRouting.of("Which team handles this?",
                        IntentRoute.handler("track", "where is my parcel", d -> "tracked"),
                        IntentRoute.llm("general", "anything else"),
                        IntentRoute.human("agent", "a person", d -> "a person will follow up"))
                .withHandler(decisions::add)
                .withDecisionModel(new DecisionModel() {
                    @Override public String name() { return "deadline-test"; }
                    @Override public boolean isAvailable() { return true; }
                    @Override public DecisionResult decide(DecisionRequest request) {
                        var answer = new Answer.Choice(IntentRouting.QUESTION_ID, "track", Map.of(),
                                AiConfidence.reported(0.7));
                        return new DecisionResult(name(), Map.of(answer.id(), answer), Optional.empty(),
                                Duration.ZERO);
                    }
                });
    }

    private static IntentDispatch.Step step(IntentRouting routing, StreamingSession wire,
                                            ApprovalRegistry registry, Instant deadline) {
        var request = new AiRequest("where is order 7?");
        return new IntentDispatch.Step(routing, request.message(), request.message(), request, wire,
                null, "client", registry, () -> false, deadline);
    }
}
