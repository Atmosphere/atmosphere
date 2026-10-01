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

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.atmosphere.ai.decision.DecisionModelResolverTestAccess;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.intent.IntentDecision;
import org.atmosphere.ai.intent.IntentRoute;
import org.atmosphere.ai.intent.IntentRouting;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Intent routing on both dispatch paths. Every scenario runs once through the
 * {@code @AiEndpoint} path ({@link AiStreamingSession#stream}) and once through
 * {@link AiPipeline#execute}, with the same routing and the same scripted
 * {@link DecisionModel}, and asserts the same wire signal, the same reply and
 * the same runtime calls (Correctness Invariant #7).
 */
class IntentRoutingParityTest {

    /** The memory key on both paths: the resource uuid, and the pipeline client id. */
    private static final String CONVERSATION = "conversation-1";

    /** The two dispatch entry modes. */
    enum Path {
        ENDPOINT {
            @Override
            Turn start(Fixture fixture, String message, Map<String, Object> metadata) {
                var resource = mock(AtmosphereResource.class);
                when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
                when(resource.uuid()).thenReturn(CONVERSATION);
                var interceptors = metadata.isEmpty() ? List.<AiInterceptor>of()
                        : List.<AiInterceptor>of(new AiInterceptor() {
                            @Override
                            public AiRequest preProcess(AiRequest request, AtmosphereResource r) {
                                return request.withMetadata(metadata);
                            }
                        });
                var wire = fixture.takeWire();
                // As AiEndpointHandler does: a TracingCapturingSession under the
                // session whenever metrics are configured.
                StreamingSession leaf = fixture.metrics == AiMetrics.NOOP ? wire
                        : new TracingCapturingSession(wire, fixture.metrics, "m");
                var session = new AiStreamingSession(leaf, fixture.runtime, "system", "m", interceptors,
                        resource, fixture.memory, null, fixture.guardrails, fixture.contextProviders,
                        fixture.metrics, null);
                if (fixture.defaultRouting != null) {
                    session.setIntentRouting(fixture.defaultRouting);
                }
                var thread = Thread.startVirtualThread(() -> session.stream(message));
                return new Turn(wire, thread, session::tryResolveApproval, session::cancelInflight);
            }
        },
        PIPELINE {
            @Override
            Turn start(Fixture fixture, String message, Map<String, Object> metadata) {
                var pipeline = new AiPipeline(fixture.runtime, "system", "m", fixture.memory, null,
                        fixture.guardrails, List.of(), fixture.contextProviders, fixture.metrics, null);
                pipeline.setDefaultIntentRouting(fixture.defaultRouting);
                var wire = fixture.takeWire();
                var thread = Thread.startVirtualThread(
                        () -> pipeline.execute(CONVERSATION, message, wire, metadata));
                return new Turn(wire, thread, pipeline::tryResolveApproval,
                        () -> pipeline.approvalRegistry().cancelAllPending());
            }
        };

        abstract Turn start(Fixture fixture, String message, Map<String, Object> metadata);
    }

    // --- scenarios ---------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Path.class)
    void confidentHandlerChoiceAnswersWithoutTheLlm(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertEquals("parcel order 7 is out for delivery", wire.text());
        assertTrue(wire.completed);
        assertEquals(0, fixture.runtimeCalls.get(), "a handler route must never reach the runtime");
        assertEquals("track", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("track", wire.metadata(IntentRouting.CHOICE_METADATA_KEY));
        assertEquals("ACT", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertEquals(0.95, wire.metadata(IntentRouting.CONFIDENCE_METADATA_KEY));
        assertTrue(wire.indexOf(IntentRouting.ROUTE_METADATA_KEY) < wire.firstTextIndex(),
                "the route rides the wire before the reply");
        var decision = fixture.decisions.getFirst();
        assertEquals("track", decision.route());
        assertEquals("where is order 7?", decision.message());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void llmChoiceContinuesToTheRuntimeUnchanged(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of()), 0.97);
        var wire = path.start(fixture, "tell me a joke", Map.of()).finish();

        assertEquals(1, fixture.runtimeCalls.get());
        assertEquals("llm says hi", wire.text());
        assertEquals("general", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("ACT", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        var context = fixture.contexts.getFirst();
        assertEquals("tell me a joke", context.message());
        assertFalse(context.metadata().containsKey(IntentRouting.METADATA_KEY),
                "the routing never reaches the provider metadata");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void lowConfidenceEscalatesToTheHumanRoute(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.2);
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertEquals("a person will follow up", wire.text());
        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("track", wire.metadata(IntentRouting.CHOICE_METADATA_KEY));
        assertEquals("ESCALATE", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertEquals(0, fixture.runtimeCalls.get());
        assertEquals(1, fixture.humanHandoffs.get());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void failedClassificationFailsClosedToTheHumanRoute(Path path) throws Exception {
        var fixture = Fixture.answering(request -> new Answer.Failed(IntentRouting.QUESTION_ID,
                Answer.Failed.Reason.UNPARSEABLE, "not json"));
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("ESCALATE", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertNull(wire.metadata(IntentRouting.CHOICE_METADATA_KEY), "no choice was made");
        assertNull(wire.metadata(IntentRouting.CONFIDENCE_METADATA_KEY), "unknown is not sent as a number");
        assertEquals(0, fixture.runtimeCalls.get());
        assertTrue(fixture.decisions.getFirst().reason().contains("unparseable"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void confirmTierAsksTheRequesterAndActsOnApproval(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.7);
        var turn = path.start(fixture, "where is order 7?", Map.of());
        var approval = turn.wire.awaitApproval();

        assertEquals("intent:track", approval.toolName());
        assertEquals("track", approval.arguments().get("route"));
        assertEquals(0.7, approval.arguments().get("confidence"));
        assertTrue(turn.resolver.apply(ApprovalRegistry.APPROVAL_PREFIX + approval.approvalId() + "/approve"));
        var wire = turn.finish();

        assertEquals("parcel order 7 is out for delivery", wire.text());
        assertEquals("track", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("CONFIRM", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertEquals(0, fixture.humanHandoffs.get());
        assertTrue(fixture.decisions.getFirst().reason().contains("confirmed by the requester"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void deniedConfirmationEscalates(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.7);
        var turn = path.start(fixture, "where is order 7?", Map.of());
        var approval = turn.wire.awaitApproval();
        assertTrue(turn.resolver.apply(ApprovalRegistry.APPROVAL_PREFIX + approval.approvalId() + "/deny"));
        var wire = turn.finish();

        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("CONFIRM", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertEquals("a person will follow up", wire.text());
        assertEquals(1, fixture.humanHandoffs.get());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void unansweredConfirmationTimesOutAndEscalates(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.7);
        fixture.defaultRouting = fixture.defaultRouting.withConfirmTimeout(Duration.ofMillis(200));
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertTrue(fixture.decisions.getFirst().reason().contains("timed_out"),
                fixture.decisions.getFirst().reason());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void throwingHandlerErrorsTheTurn(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        fixture.failTracking = true;
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertInstanceOf(IllegalStateException.class, wire.error);
        assertFalse(wire.completed);
        assertEquals(0, fixture.runtimeCalls.get(), "a failing handler does not fall back to the LLM");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void blockedRequestIsNeverClassified(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        fixture.guardrails = List.of(new AiGuardrail() {
            @Override
            public GuardrailResult inspectRequest(AiRequest request) {
                return GuardrailResult.block("no orders today");
            }
        });
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertInstanceOf(SecurityException.class, wire.error);
        assertEquals(0, fixture.classifications.get(), "admission runs before classification");
        assertNull(wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void classifierSeesTheGuardrailRedactedMessage(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        fixture.guardrails = List.of(new AiGuardrail() {
            @Override
            public GuardrailResult inspectRequest(AiRequest request) {
                return GuardrailResult.modify(request.withMessage(
                        request.message().replace("alice@example.com", "[email]")));
            }
        });
        path.start(fixture, "order 7 for alice@example.com", Map.of()).finish();

        assertEquals("order 7 for [email]", fixture.states.getFirst());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void handlerExchangeIsRecordedInConversationMemory(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        fixture.memory = new InMemoryConversationMemory(10);
        path.start(fixture, "where is order 7?", Map.of()).finish();

        var stored = new ArrayList<String>();
        fixture.memory.getHistory(CONVERSATION).forEach(m -> stored.add(m.role() + ":" + m.content()));
        assertEquals(List.of("user:where is order 7?", "assistant:parcel order 7 is out for delivery"), stored);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void perRequestRoutingWinsOverTheDefault(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        var override = IntentRouting.of("Is this about billing?",
                        IntentRoute.handler("billing", "invoices", d -> "billing handled"),
                        IntentRoute.human("agent", "a person", d -> "a person will follow up"))
                .withDecisionModel(scripted(request -> choice("billing", 0.99), fixture));
        var wire = path.start(fixture, "my invoice is wrong", Map.of(IntentRouting.METADATA_KEY, override))
                .finish();

        assertEquals("billing", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("billing handled", wire.text());
        assertTrue(fixture.decisions.isEmpty(), "the default routing's observer never ran");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void disconnectWhileConfirmingCancelsInsteadOfEscalating(Path path) throws Exception {
        // Only the endpoint path knows about a client disconnect; on the pipeline a
        // cancelled approval reads as a denial and escalates, as for tools.
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.7);
        var turn = path.start(fixture, "where is order 7?", Map.of());
        turn.wire.awaitApproval();
        turn.cancel.run();
        var wire = turn.finish();

        if (path == Path.ENDPOINT) {
            assertInstanceOf(java.util.concurrent.CancellationException.class, wire.error);
            assertEquals(0, fixture.humanHandoffs.get(), "nobody is escalated for a client that left");
        } else {
            assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        }
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void handlerRoutePaysNoRetrievalAndSeesTheUnaugmentedMessage(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        var retrievals = new AtomicInteger();
        ContextProvider provider = (query, maxResults) -> {
            retrievals.incrementAndGet();
            return List.of(new ContextProvider.Document("parcels ship in 2 days", "faq.md", 0.9));
        };
        fixture.contextProviders = List.of(provider);

        var handled = path.start(fixture, "where is order 7?", Map.of()).finish();
        assertEquals("track", handled.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals(0, retrievals.get(), "a handler route must not pay for RAG retrieval");
        assertEquals("where is order 7?", fixture.decisions.getFirst().message());
        assertEquals("where is order 7?", fixture.decisions.getFirst().request().message(),
                "the handler sees the request before any RAG augmentation");
        assertEquals("where is order 7?", fixture.states.getFirst());

        // The LLM route still reaches the context providers: the endpoint
        // augments the message itself, the pipeline hands them to the runtime.
        path.start(fixture, "tell me a joke", Map.of()).finish();
        assertEquals("tell me a joke", fixture.states.get(1), "the classifier never sees RAG text");
        var context = fixture.contexts.getFirst();
        if (path == Path.ENDPOINT) {
            assertEquals(1, retrievals.get());
            assertTrue(context.message().contains("Relevant context:"), context.message());
        } else {
            assertEquals(List.of(provider), context.contextProviders());
        }
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void scopeRedirectedRequestIsNotClassifiedAndNoHandlerAnswersIt(Path path) throws Exception {
        // "python" would classify to the deterministic handler at ACT.
        var fixture = new Fixture(choiceFor(Map.of("python", "track")), 0.95);
        var scope = new ScopeConfig(
                "Mathematics tutoring — arithmetic, algebra, calculus, geometry",
                List.of("writing source code", "programming tutorials"),
                AgentScope.Breach.POLITE_REDIRECT, "I can only help with math.",
                AgentScope.Tier.RULE_BASED, 0.45, false, false, "");
        var wire = path.start(fixture, "write python code to sort an array",
                Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, scope)).finish();

        assertEquals(0, fixture.classifications.get(), "a redirected request is not classified");
        assertNull(wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals(1, fixture.runtimeCalls.get(), "the LLM path renders the redirect");
        assertEquals("I can only help with math.", fixture.contexts.getFirst().message());
        assertEquals(0, fixture.humanHandoffs.get());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void cancelDuringClassificationRunsNoRoute(Path path) throws Exception {
        // ESCALATE tier: without the cancel check the human route would run.
        var fixture = Fixture.blocking(0.2);
        var turn = path.start(fixture, "where is order 7?", Map.of());
        assertTrue(fixture.classifying.await(10, TimeUnit.SECONDS));
        cancelTurn(path, turn);
        fixture.release.countDown();
        var wire = turn.finish();

        assertInstanceOf(CancellationException.class, wire.error);
        assertEquals(0, fixture.humanHandoffs.get(), "nobody is escalated for a cancelled turn");
        assertEquals(0, fixture.runtimeCalls.get());
        assertNull(wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertTrue(fixture.decisions.isEmpty());
        if (path == Path.PIPELINE) {
            assertTrue(wire.interruptedAtTerminal, "the interrupt status survives the routing step");
        }
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void cancelBeforeAConfirmationNeverParksOnIt(Path path) throws Exception {
        // CONFIRM tier, long confirmTimeout: without the pre-registration check
        // the turn would park on an approval nobody can answer any more.
        var fixture = Fixture.blocking(0.7);
        fixture.defaultRouting = fixture.defaultRouting.withConfirmTimeout(Duration.ofMinutes(5));
        var turn = path.start(fixture, "where is order 7?", Map.of());
        assertTrue(fixture.classifying.await(10, TimeUnit.SECONDS));
        cancelTurn(path, turn);
        fixture.release.countDown();
        var wire = turn.finish();

        assertInstanceOf(CancellationException.class, wire.error);
        assertTrue(wire.approvals.isEmpty(), "no confirmation is asked of a requester who left");
        assertEquals(0, fixture.humanHandoffs.get());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void interruptedConfirmationCancelsInsteadOfEscalating(Path path) throws Exception {
        // A batch item cancelled by its job (future.cancel(true)) interrupts
        // the dispatching thread while it waits for the confirmation.
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.7);
        var turn = path.start(fixture, "where is order 7?", Map.of());
        turn.wire.awaitApproval();
        turn.thread.interrupt();
        var wire = turn.finish();

        assertInstanceOf(CancellationException.class, wire.error);
        assertEquals(0, fixture.humanHandoffs.get(), "an abandoned item files no human hand-off");
        assertTrue(wire.interruptedAtTerminal, "the interrupt status is restored");
        assertNull(wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
    }

    // --- README table rows, each through both paths ------------------------

    @ParameterizedTest
    @EnumSource(Path.class)
    void humanChoiceAtTheConfirmTierEscalatesWithoutAConfirmation(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "agent")), 0.7);
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertTrue(wire.approvals.isEmpty(), "escalation needs no confirmation");
        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("agent", wire.metadata(IntentRouting.CHOICE_METADATA_KEY));
        assertEquals("CONFIRM", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertEquals("a person will follow up", wire.text());
        assertEquals(1, fixture.humanHandoffs.get());
        assertEquals(0, fixture.runtimeCalls.get());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void noDecisionModelFailsClosedToTheHumanRoute(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        fixture.defaultRouting = fixture.defaultRouting.withDecisionModel(null);
        DecisionModelResolverTestAccess.forceDemoOnly();
        try {
            assertTrue(DecisionModelResolver.resolve().isEmpty(), "precondition: no model resolves");
            var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

            assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
            assertEquals("ESCALATE", wire.metadata(IntentRouting.TIER_METADATA_KEY));
            assertNull(wire.metadata(IntentRouting.CHOICE_METADATA_KEY));
            assertEquals(1, fixture.humanHandoffs.get());
            assertEquals(0, fixture.runtimeCalls.get());
            assertTrue(fixture.decisions.getFirst().reason().contains("no decision model"),
                    fixture.decisions.getFirst().reason());
        } finally {
            DecisionModelResolverTestAccess.restore();
        }
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void oversizedMessageFailsClosedWithoutAskingTheModel(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        var message = "where is order 7? " + "x".repeat(DecisionRequest.MAX_STATE_CHARS);
        var wire = path.start(fixture, message, Map.of()).finish();

        assertEquals(0, fixture.classifications.get(), "the model is never asked");
        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("ESCALATE", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertEquals(1, fixture.humanHandoffs.get());
        assertEquals(0, fixture.runtimeCalls.get());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void throwingModelFailsClosedToTheHumanRoute(Path path) throws Exception {
        var fixture = Fixture.answering(request -> {
            throw new IllegalStateException("model down");
        });
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("ESCALATE", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertEquals(1, fixture.humanHandoffs.get());
        assertEquals(0, fixture.runtimeCalls.get());
        assertTrue(fixture.decisions.getFirst().reason().contains("model down"),
                fixture.decisions.getFirst().reason());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void choiceWithNoConfidenceTakesTheUnknownRoute(Path path) throws Exception {
        var fixture = Fixture.answering(request -> new Answer.Choice(IntentRouting.QUESTION_ID, "track",
                Map.of(), AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD)));
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY), "unknownRoute is ESCALATE");
        assertEquals("track", wire.metadata(IntentRouting.CHOICE_METADATA_KEY));
        assertEquals("ESCALATE", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertNull(wire.metadata(IntentRouting.CONFIDENCE_METADATA_KEY));
        assertEquals(1, fixture.humanHandoffs.get());

        // A routing that acts on an unmeasured choice runs the chosen route.
        var acting = Fixture.answering(request -> new Answer.Choice(IntentRouting.QUESTION_ID, "track",
                Map.of(), AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD)));
        acting.defaultRouting = acting.defaultRouting.withThresholds(
                ConfidenceRouting.defaults().withUnknownRoute(ConfidenceRoute.ACT));
        var acted = path.start(acting, "where is order 7?", Map.of()).finish();
        assertEquals("track", acted.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("parcel order 7 is out for delivery", acted.text());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void aConfirmationThatCannotBeSentEscalatesAndEndsTheTurn(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.7);
        fixture.defaultRouting = fixture.defaultRouting.withConfirmTimeout(IntentRouting.MAX_CONFIRM_TIMEOUT);
        fixture.wire.failApprovalEmit = true;
        var wire = path.start(fixture, "where is order 7?", Map.of()).finish();

        assertTrue(wire.completed, "the turn ends on a route instead of parking or throwing");
        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("CONFIRM", wire.metadata(IntentRouting.TIER_METADATA_KEY));
        assertEquals("a person will follow up", wire.text());
        assertEquals(1, fixture.humanHandoffs.get());
        assertTrue(fixture.decisions.getFirst().reason().contains("confirmation failed"),
                fixture.decisions.getFirst().reason());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void theLongestConfirmTimeoutParksAndStillEndsOnARoute(Path path) throws Exception {
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.7);
        fixture.defaultRouting = fixture.defaultRouting.withConfirmTimeout(IntentRouting.MAX_CONFIRM_TIMEOUT);
        var turn = path.start(fixture, "where is order 7?", Map.of());
        var approval = turn.wire.awaitApproval();
        assertTrue(approval.expiresIn() > IntentRouting.MAX_CONFIRM_TIMEOUT.toSeconds() - 60, approval.toString());
        assertTrue(turn.resolver.apply(ApprovalRegistry.APPROVAL_PREFIX + approval.approvalId() + "/deny"));
        var wire = turn.finish();

        assertEquals("agent", wire.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals(1, fixture.humanHandoffs.get());
    }

    // --- observability -----------------------------------------------------

    @ParameterizedTest
    @EnumSource(Path.class)
    void routedTurnsRecordNoModelMetrics(Path path) throws Exception {
        // AiEndpointHandler wraps every turn in a TracingCapturingSession; it
        // recorded a handler or human turn (and a CONFIRM wait) as the model's
        // latency and usage, which AiPipeline never did.
        var fixture = Fixture.answering(request -> {
            var state = request.state();
            if (state.contains("joke")) {
                return choice("general", 0.95);
            }
            return choice("track", state.contains("vague") ? 0.2 : 0.95);
        });
        var metrics = new RecordingMetrics();
        fixture.metrics = metrics;

        path.start(fixture, "where is order 7?", Map.of()).finish();
        path.start(fixture, "something vague", Map.of()).finish();
        fixture.failTracking = true;
        assertInstanceOf(IllegalStateException.class,
                path.start(fixture, "where is order 7?", Map.of()).finish().error);
        fixture.failTracking = false;
        assertEquals(List.of(), metrics.modelMeasures(),
                "a handler, human or failed handler turn calls no model");
        assertEquals(1, fixture.humanHandoffs.get());
        assertEquals(metrics.started.get(), metrics.ended.get(), "session gauge stays balanced");

        // Control: the same recorder sees the LLM route on both paths.
        path.start(fixture, "tell me a joke", Map.of()).finish();
        assertEquals(1, fixture.runtimeCalls.get());
        assertTrue(metrics.modelMeasures().contains("latency(m)"), metrics.calls.toString());
    }

    /** Endpoint: the client disconnects. Pipeline: the dispatching thread is interrupted. */
    private static void cancelTurn(Path path, Turn turn) {
        if (path == Path.ENDPOINT) {
            turn.cancel.run();
        } else {
            turn.thread.interrupt();
        }
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void intentRoutingComposesWithTheModelRouter(Path path) throws Exception {
        // Intent routing picks the handler; the ModelRouter still picks the
        // backend (here: failing over from a broken primary) on the LLM route.
        var primaryCalls = new AtomicInteger();
        var secondaryCalls = new AtomicInteger();
        var fixture = new Fixture(choiceFor(Map.of("order", "track")), 0.95);
        fixture.runtime = new RoutingAiSupport(
                new DefaultModelRouter(ModelRouter.FallbackStrategy.FAILOVER, 1, Duration.ofMinutes(1)),
                List.of(backend("primary", primaryCalls, null), backend("secondary", secondaryCalls, "from secondary")));

        var llm = path.start(fixture, "tell me a joke", Map.of()).finish();
        assertEquals("general", llm.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals("from secondary", llm.text());
        assertEquals(1, primaryCalls.get());
        assertEquals(1, secondaryCalls.get());

        var handled = path.start(fixture, "where is order 7?", Map.of()).finish();
        assertEquals("track", handled.metadata(IntentRouting.ROUTE_METADATA_KEY));
        assertEquals(1, primaryCalls.get(), "a handler route reaches no backend");
        assertEquals(1, secondaryCalls.get());
    }

    /** A backend that replies {@code reply}, or throws when it is {@code null}. */
    private static AgentRuntime backend(String name, AtomicInteger calls, String reply) {
        return new AgentRuntime() {
            @Override public String name() { return name; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                calls.incrementAndGet();
                if (reply == null) {
                    throw new IllegalStateException(name + " is down");
                }
                session.send(reply);
                session.complete();
            }
        };
    }

    // --- fixture -----------------------------------------------------------

    /** Records every measure; the session gauge is counted apart. */
    static final class RecordingMetrics implements AiMetrics {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger ended = new AtomicInteger();

        @Override
        public void recordStreamingTextUsage(String model, int promptStreamingTexts, int completionStreamingTexts) {
            calls.add("streamingText(" + model + ")");
        }

        @Override
        public void recordLatency(String model, Duration timeToFirstStreamingText, Duration totalDuration) {
            calls.add("latency(" + model + ")");
        }

        @Override
        public void recordCost(String model, java.math.BigDecimal cost) {
            calls.add("cost(" + model + ")");
        }

        @Override
        public void recordToolCall(String model, String toolName, Duration duration, boolean success) {
            calls.add("tool(" + model + ")");
        }

        @Override
        public void recordError(String model, String errorType) {
            calls.add("error(" + model + ")");
        }

        @Override
        public void sessionStarted(String model) {
            started.incrementAndGet();
        }

        @Override
        public void sessionEnded(String model) {
            ended.incrementAndGet();
        }

        List<String> modelMeasures() {
            return List.copyOf(calls);
        }
    }

    /** Classifies "order" messages to the scripted route, everything else to "general". */
    private static Function<String, String> choiceFor(Map<String, String> keywords) {
        return state -> {
            for (var entry : keywords.entrySet()) {
                if (state.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return "general";
        };
    }

    private static Answer choice(String route, double confidence) {
        return new Answer.Choice(IntentRouting.QUESTION_ID, route, Map.of(), AiConfidence.reported(confidence));
    }

    private static DecisionModel scripted(Function<DecisionRequest, Answer> script, Fixture fixture) {
        return new DecisionModel() {
            @Override public String name() { return "scripted"; }
            @Override public boolean isAvailable() { return true; }
            @Override public DecisionResult decide(DecisionRequest request) {
                fixture.classifications.incrementAndGet();
                fixture.states.add(request.state());
                var answer = script.apply(request);
                return new DecisionResult(name(), Map.of(answer.id(), answer), Optional.empty(), Duration.ZERO);
            }
        };
    }

    static final class Fixture {
        final AtomicInteger runtimeCalls = new AtomicInteger();
        final AtomicInteger classifications = new AtomicInteger();
        final AtomicInteger humanHandoffs = new AtomicInteger();
        final List<String> states = new CopyOnWriteArrayList<>();
        final List<AgentExecutionContext> contexts = new CopyOnWriteArrayList<>();
        final List<IntentDecision> decisions = new CopyOnWriteArrayList<>();
        volatile boolean failTracking;
        AiConversationMemory memory;
        List<AiGuardrail> guardrails = List.of();
        List<ContextProvider> contextProviders = List.of();
        /** Counted down when a blocking model starts classifying. */
        final CountDownLatch classifying = new CountDownLatch(1);
        /** Releases a blocking model. */
        final CountDownLatch release = new CountDownLatch(1);
        IntentRouting defaultRouting;
        AiMetrics metrics = AiMetrics.NOOP;
        /** The wire of the next turn; a test may swap in a failing one. */
        Wire wire = new Wire();
        AgentRuntime runtime = new AgentRuntime() {
            @Override public String name() { return "intent-test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public Set<AiCapability> capabilities() {
                return Set.of(AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT);
            }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                runtimeCalls.incrementAndGet();
                contexts.add(context);
                session.send("llm says hi");
                session.complete();
            }
        };

        /**
         * @param classify   message → chosen route ("track", "general"), or a Failed answer
         * @param confidence the reported confidence of every choice
         */
        Fixture(Function<String, String> classify, double confidence) {
            this(request -> choice(classify.apply(request.state()), confidence));
        }

        /** The wire for the turn being started; the next turn gets a fresh one. */
        Wire takeWire() {
            var taken = wire;
            wire = new Wire();
            return taken;
        }

        static Fixture answering(Function<DecisionRequest, Answer> script) {
            return new Fixture(script);
        }

        /**
         * A model that blocks until {@link #release} and then chooses "track"
         * at {@code confidence}. An interrupt while blocked is remembered and
         * re-asserted once the answer is given, as a model that does not
         * abort on interrupt would leave the thread.
         */
        static Fixture blocking(double confidence) {
            var holder = new Fixture[1];
            holder[0] = new Fixture(request -> {
                holder[0].classifying.countDown();
                var interrupted = false;
                while (true) {
                    try {
                        if (holder[0].release.await(10, TimeUnit.SECONDS)) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return choice("track", confidence);
            });
            return holder[0];
        }

        private Fixture(Function<DecisionRequest, Answer> script) {
            defaultRouting = IntentRouting.of("Which team should handle this customer message?",
                            IntentRoute.handler("track", "where is my parcel", decision -> {
                                if (failTracking) {
                                    throw new IllegalStateException("tracking service down");
                                }
                                return "parcel order 7 is out for delivery";
                            }),
                            IntentRoute.llm("general", "anything else"),
                            IntentRoute.human("agent", "needs a person", decision -> {
                                humanHandoffs.incrementAndGet();
                                return "a person will follow up";
                            }))
                    .withDecisionModel(scripted(script, this))
                    .withHandler(decisions::add);
        }
    }

    /** One in-flight turn on either path. */
    record Turn(Wire wire, Thread thread, Function<String, Boolean> resolver, Runnable cancel) {
        Wire finish() throws InterruptedException {
            assertTrue(wire.terminal.await(10, TimeUnit.SECONDS), "the turn must reach a terminal state");
            thread.join(Duration.ofSeconds(10));
            return wire;
        }
    }

    /** The client side of the wire. */
    static class Wire implements StreamingSession {
        final List<Map.Entry<String, Object>> frames = new CopyOnWriteArrayList<>();
        final List<AiEvent.ApprovalRequired> approvals = new CopyOnWriteArrayList<>();
        final CountDownLatch terminal = new CountDownLatch(1);
        final CountDownLatch approvalSeen = new CountDownLatch(1);
        volatile boolean completed;
        volatile Throwable error;
        /** Whether the dispatching thread was interrupted when the turn ended. */
        volatile boolean interruptedAtTerminal;
        /** Fails the approval-required frame, as a broken connection would. */
        volatile boolean failApprovalEmit;

        @Override public String sessionId() { return "wire"; }

        @Override
        public void send(String text) {
            frames.add(Map.entry("text", text));
        }

        @Override
        public void sendMetadata(String key, Object value) {
            frames.add(Map.entry(key, value));
        }

        @Override public void progress(String message) { }

        @Override
        public void complete() {
            completed = true;
            interruptedAtTerminal = Thread.currentThread().isInterrupted();
            terminal.countDown();
        }

        @Override
        public void complete(String summary) {
            complete();
        }

        @Override
        public void error(Throwable t) {
            error = t;
            interruptedAtTerminal = Thread.currentThread().isInterrupted();
            terminal.countDown();
        }

        @Override
        public boolean isClosed() {
            return terminal.getCount() == 0;
        }

        @Override
        public boolean hasErrored() {
            return error != null;
        }

        @Override
        public void emit(AiEvent event) {
            if (event instanceof AiEvent.ApprovalRequired required) {
                if (failApprovalEmit) {
                    throw new IllegalStateException("connection broken");
                }
                approvals.add(required);
                approvalSeen.countDown();
                return;
            }
            StreamingSession.super.emit(event);
        }

        AiEvent.ApprovalRequired awaitApproval() throws InterruptedException {
            assertTrue(approvalSeen.await(10, TimeUnit.SECONDS), "a confirmation must be requested");
            return approvals.getFirst();
        }

        Object metadata(String key) {
            for (var frame : frames) {
                if (frame.getKey().equals(key)) {
                    return frame.getValue();
                }
            }
            return null;
        }

        int indexOf(String key) {
            for (var i = 0; i < frames.size(); i++) {
                if (frames.get(i).getKey().equals(key)) {
                    return i;
                }
            }
            return -1;
        }

        int firstTextIndex() {
            return indexOf("text");
        }

        String text() {
            var builder = new StringBuilder();
            for (var frame : frames) {
                if (frame.getKey().equals("text")) {
                    builder.append(frame.getValue());
                }
            }
            return builder.toString();
        }
    }
}
