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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernanceFeedbackInterceptor;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Proves the {@code @AiEndpoint(broadcastReply = true)} shared-room contract end
 * to end against a real {@link DefaultBroadcaster}: one prompt, dispatched to a
 * single {@code @Prompt} handler, produces exactly one reply that fans out to
 * <em>every</em> subscriber on the room broadcaster.
 *
 * <p>This is the observable proof behind the blog claim "for shared rooms you
 * opt into a broadcaster and one reply fans out to every subscriber":</p>
 * <ul>
 *   <li><b>Fan-out:</b> two resources subscribe to the same room broadcaster; a
 *       prompt arrives on one of them; both receive the same single reply.</li>
 *   <li><b>One model call:</b> the {@code @Prompt} method (the LLM-call site)
 *       runs exactly once — not once per subscriber. This guards the
 *       N-redundant-LLM-calls regression that targeted dispatch was built to
 *       prevent: the prompt is still dispatched to the originating resource
 *       only, broadcastReply changes only where the <em>reply</em> streams.</li>
 *   <li><b>Default unchanged:</b> with broadcastReply off (the default), the
 *       reply reaches only the originating subscriber — the bystander gets
 *       nothing.</li>
 * </ul>
 *
 * <p>The harness mirrors {@code BroadcasterTest}: a real {@code DefaultBroadcaster}
 * with real {@link AtmosphereResourceImpl} subscribers whose {@link AtmosphereHandler}
 * records every frame the broadcaster delivers to them. The reply is captured at
 * the real broadcaster's delivery boundary, so "both subscribers receive it" is a
 * genuine fan-out observation, not a verified mock overload.</p>
 */
// Constructs real AtmosphereResourceImpl subscribers via the deprecated 6-arg
// constructor — the same test harness BroadcasterTest uses to exercise real
// broadcaster delivery. No non-deprecated public constructor builds a standalone
// resource for an isolated unit test.
@SuppressWarnings("deprecation")
class AiEndpointHandlerBroadcastReplyTest {

    private static final String REPLY = "the-one-reply";

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private CapturingHandler capture;
    private Method promptMethod;

    @BeforeEach
    void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/atmosphere/room/general");
        capture = new CapturingHandler();
        promptMethod = StubEndpoint.class.getDeclaredMethod(
                "onPrompt", String.class, StreamingSession.class);
    }

    @AfterEach
    void tearDown() {
        GovernanceDecisionLog.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void broadcastReplyTrue_oneReplyFansOutToAllSubscribers_andPromptRunsOnce() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();

        var endpoint = new StubEndpoint();
        var handler = newHandler(endpoint);
        handler.setBroadcastReply(true);

        // Expect the single reply to be delivered to BOTH subscribers.
        capture.expectReplies(2);
        handler.dispatchPrompt(originating, "hello room");

        assertTrue(capture.await(10, TimeUnit.SECONDS),
                "the one reply must reach both subscribers on the room broadcaster");

        // (a) Both subscribers received the SAME single reply.
        assertTrue(capture.received(originating.uuid(), REPLY),
                "originating subscriber must receive the reply");
        assertTrue(capture.received(bystander.uuid(), REPLY),
                "bystander subscriber in the same room must receive the same reply");

        // (b) The @Prompt method (the LLM-call site) ran exactly ONCE, not once
        // per subscriber — the N-redundant-call regression guard.
        assertEquals(1, endpoint.invocations.get(),
                "@Prompt must run exactly once regardless of room size");
    }

    @Test
    void broadcastReplyFalse_replyReachesOnlyOriginatingSubscriber() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();

        var endpoint = new StubEndpoint();
        // Default: no setBroadcastReply -> per-client behavior.
        var handler = newHandler(endpoint);

        capture.expectReplies(1);
        handler.dispatchPrompt(originating, "hello room");

        assertTrue(capture.await(10, TimeUnit.SECONDS),
                "the originating subscriber must receive its own reply");

        assertEquals(1, endpoint.invocations.get(),
                "@Prompt must run exactly once");
        assertTrue(capture.received(originating.uuid(), REPLY),
                "originating subscriber must receive the reply");

        // Give any errant cross-tab delivery a chance to arrive, then assert the
        // bystander saw nothing — the default per-client path is unchanged.
        Thread.sleep(300);
        assertFalse(capture.received(bystander.uuid(), REPLY),
                "with broadcastReply off, the bystander must NOT receive the reply");
        assertTrue(capture.framesFor(bystander.uuid()).isEmpty(),
                "with broadcastReply off, the bystander must receive no frames at all");
    }

    @Test
    void broadcastReplyTrue_promptersGovernanceGuidanceNeverReachesTheRoom() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        GovernanceDecisionLog.install(100);
        recordPrefer(originating.uuid());

        var handler = newGovernanceHandler();
        handler.setBroadcastReply(true);
        handler.dispatchPrompt(originating, "deploy to prod?");

        awaitComplete(originating);
        awaitComplete(bystander);
        // The room's reply is real (both subscribers got it), and the guidance was injected
        // into the prompter's turn — but the prompter's governance history stays off the room.
        assertTrue(capture.received(bystander.uuid(), REPLY), "bystander got the room reply");
        assertTrue(GovernanceEndpoint.lastSystemPrompt.contains(PREFERRED),
                "the prompter's turn carried the guidance: " + GovernanceEndpoint.lastSystemPrompt);
        assertFalse(capture.received(bystander.uuid(), "ai.governance.feedback"),
                "no governance frame reaches the bystander: " + capture.framesFor(bystander.uuid()));
        assertFalse(capture.received(originating.uuid(), "ai.governance.feedback"),
                "nor rides the room reply at all: " + capture.framesFor(originating.uuid()));
    }

    @Test
    void broadcastReplyFalse_promptersGovernanceGuidanceIsSignalledToThePrompterOnly() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        GovernanceDecisionLog.install(100);
        recordPrefer(originating.uuid());

        var handler = newGovernanceHandler();
        handler.dispatchPrompt(originating, "deploy to prod?");

        awaitComplete(originating);
        // The handler stamped this turn per-client, so the signal goes out — to the prompter.
        assertTrue(capture.received(originating.uuid(), GovernanceFeedbackInterceptor.LINES_METADATA_KEY),
                "the prompter sees the guidance it was given: " + capture.framesFor(originating.uuid()));
        Thread.sleep(300);
        assertTrue(capture.framesFor(bystander.uuid()).isEmpty(),
                "the bystander receives nothing: " + capture.framesFor(bystander.uuid()));
    }

    @Test
    void broadcastReplyTrue_everyMemberSeesThePromptOnceBeforeTheReply() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        StreamingEndpoint.invocations.set(0);

        var handler = newStreamingHandler(List.of());
        handler.setBroadcastReply(true);
        handler.dispatchPrompt(originating, "what is a prime number?");

        awaitComplete(bystander);
        awaitComplete(originating);
        assertEquals(1, StreamingEndpoint.invocations.get(), "@Prompt runs once, on the sender's connection");
        for (var member : List.of(bystander, originating)) {
            var frames = parsed(member);
            var prompts = ofType(frames, "prompt");
            assertEquals(1, prompts.size(), "exactly one prompt frame: " + frames);
            var prompt = prompts.get(0);
            assertEquals("what is a prime number?", prompt.get("text").stringValue());
            assertEquals(org.atmosphere.ai.RoomMemberIds.of(originating.uuid()), prompt.get("from").stringValue(),
                    "from is the asker's room member id");
            assertFalse(capture.framesFor(member.uuid()).stream().anyMatch(f -> f.contains(originating.uuid())),
                    "no frame the room receives carries the asker's tracking id: " + frames);
            assertFalse(prompt.has("data"), "the prompt rides in text, never data: " + prompt);
            var firstText = ofType(frames, "streaming-text").get(0);
            assertTrue(frames.indexOf(prompt) < frames.indexOf(firstText),
                    "the prompt precedes the first reply frame: " + frames);
            assertEquals(firstText.get("sessionId").stringValue(), prompt.get("sessionId").stringValue(),
                    "the prompt belongs to the turn's session");
        }
    }

    @Test
    void broadcastReplyTrue_promptOfAMethodAnsweringWithoutStreamPrecedesItsReply() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        var endpoint = new StubEndpoint();
        var handler = newHandler(endpoint);
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(originating, "hello room");

        awaitComplete(bystander);
        var frames = parsed(bystander);
        var prompts = ofType(frames, "prompt");
        assertEquals(1, prompts.size(), "exactly one prompt frame: " + frames);
        assertEquals("hello room", prompts.get(0).get("text").stringValue());
        assertTrue(frames.indexOf(prompts.get(0)) < frames.indexOf(ofType(frames, "streaming-text").get(0)),
                "the prompt precedes the reply: " + frames);
        assertEquals(1, endpoint.invocations.get());
    }

    @Test
    void broadcastReplyFalse_noPromptFrameIsSent() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        var handler = newStreamingHandler(List.of());

        handler.dispatchPrompt(originating, "what is a prime number?");

        awaitComplete(originating);
        // Byte-identical to the per-client output before room prompts existed.
        var frames = capture.framesFor(originating.uuid());
        var first = JSON.readTree(frames.get(0));
        var sessionId = first.get("sessionId").stringValue();
        var runId = first.get("value").stringValue();
        assertEquals(List.of(
                "{\"type\":\"metadata\",\"key\":\"X-Atmosphere-Run-Id\",\"value\":\"" + runId
                        + "\",\"sessionId\":\"" + sessionId + "\",\"seq\":1}",
                "{\"type\":\"streaming-text\",\"data\":\"" + REPLY + "\",\"sessionId\":\"" + sessionId
                        + "\",\"seq\":2}",
                "{\"type\":\"complete\",\"sessionId\":\"" + sessionId + "\",\"seq\":3}"), frames,
                "a per-client endpoint's frames are unchanged");
        Thread.sleep(300);
        assertTrue(capture.framesFor(bystander.uuid()).isEmpty(), "the bystander receives nothing");
    }

    @Test
    void broadcastReplyTrue_anApprovalAnswerIsNeverEchoed() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        StreamingEndpoint.invocations.set(0);
        var handler = newStreamingHandler(List.of());
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(originating, "/__approval/unknown-id/approve");

        Thread.sleep(300);
        assertEquals(0, StreamingEndpoint.invocations.get(), "an approval answer is not a prompt");
        assertTrue(ofType(parsed(bystander), "prompt").isEmpty(),
                "an approval answer is never shown to the room: " + capture.framesFor(bystander.uuid()));
        assertTrue(ofType(parsed(originating), "prompt").isEmpty());
    }

    @Test
    void broadcastReplyTrue_aPromptTheRoomsScopeRefusesIsNeverShownToTheRoom() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        var handler = newStreamingHandler(List.of(new MathRoomScope()));
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(originating, "help me with writing source code please");

        awaitComplete(bystander);
        assertTrue(capture.received(bystander.uuid(), MathRoomScope.REDIRECT),
                "the room sees the scope's redirect: " + capture.framesFor(bystander.uuid()));
        assertTrue(ofType(parsed(bystander), "prompt").isEmpty(),
                "a refused prompt is never echoed: " + capture.framesFor(bystander.uuid()));
        assertTrue(ofType(parsed(originating), "prompt").isEmpty());
    }

    @Test
    void broadcastReplyTrue_aPromptTheRoomsScopeAdmitsIsShownToTheRoom() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        var handler = newStreamingHandler(List.of(new MathRoomScope()));
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(originating, "what is the derivative of x squared?");

        awaitComplete(bystander);
        var prompts = ofType(parsed(bystander), "prompt");
        assertEquals(1, prompts.size(), "an admitted prompt is echoed: " + capture.framesFor(bystander.uuid()));
        assertEquals("what is the derivative of x squared?", prompts.get(0).get("text").stringValue());
    }

    @Test
    void broadcastReplyTrue_theRoomIsShownThePromptAsAGuardrailRewroteIt() throws Exception {
        var originating = subscriber();
        var bystander = subscriber();
        org.atmosphere.ai.AiGuardrail redact = new org.atmosphere.ai.AiGuardrail() {
            @Override
            public GuardrailResult inspectRequest(org.atmosphere.ai.AiRequest request) {
                return new GuardrailResult.Modify(
                        request.withMessage(request.message().replace("alice@example.com", "[redacted-email]")));
            }
        };
        var method = StreamingEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class);
        var handler = new AiEndpointHandler(new StreamingEndpoint(), method, 30_000L, "base",
                "/atmosphere/room/{room}", new ReplyingRuntime(), List.of(), null,
                AnnotatedLifecycle.scan(StreamingEndpoint.class), null, List.of(redact), List.of(),
                org.atmosphere.ai.AiMetrics.NOOP);
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(originating, "mail alice@example.com the answer");

        awaitComplete(bystander);
        var prompts = ofType(parsed(bystander), "prompt");
        assertEquals(1, prompts.size(), capture.framesFor(bystander.uuid()).toString());
        assertEquals("mail [redacted-email] the answer", prompts.get(0).get("text").stringValue(),
                "the room sees what the model sees, not the address the guardrail removed");
    }

    @Test
    void broadcastReplyTrue_aGuardrailBlockDropsTheEchoOfAPromptAnsweredWithoutStream() throws Exception {
        var bystander = subscriber();
        ScriptedEndpoint.script = (message, session) -> {
            session.send(REPLY);
            session.complete();
        };
        var handler = newScriptedHandler(new WordGuardrail());
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(subscriber(), "a forbidden question");

        awaitComplete(bystander);
        assertTrue(capture.received(bystander.uuid(), REPLY), "the reply reaches the room");
        assertTrue(ofType(parsed(bystander), "prompt").isEmpty(),
                "a prompt the guardrail blocks is never shown: " + capture.framesFor(bystander.uuid()));
    }

    @Test
    void broadcastReplyTrue_aGuardrailModifyRewritesTheEchoOfAPromptAnsweredWithoutStream() throws Exception {
        var bystander = subscriber();
        ScriptedEndpoint.script = (message, session) -> {
            session.send(REPLY);
            session.complete();
        };
        var handler = newScriptedHandler(new WordGuardrail());
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(subscriber(), "my secret plan");

        awaitComplete(bystander);
        var prompts = ofType(parsed(bystander), "prompt");
        assertEquals(1, prompts.size(), capture.framesFor(bystander.uuid()).toString());
        assertEquals("my [hidden] plan", prompts.get(0).get("text").stringValue());
    }

    @Test
    void broadcastReplyTrue_progressBeforeABlockedStreamShowsNothing() throws Exception {
        var bystander = subscriber();
        ScriptedEndpoint.script = (message, session) -> {
            session.progress("Thinking...");
            session.stream(message);
        };
        var handler = newScriptedHandler(new WordGuardrail());
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(subscriber(), "a forbidden question");

        awaitFrame(bystander, "\"type\":\"error\"");
        assertTrue(ofType(parsed(bystander), "prompt").isEmpty(),
                "progress does not show the prompt before stream() refuses it: "
                        + capture.framesFor(bystander.uuid()));
    }

    @Test
    void broadcastReplyTrue_aHandoffEventDoesNotShowThePrompt() throws Exception {
        var bystander = subscriber();
        ScriptedEndpoint.script = (message, session) -> {
            session.emit(new org.atmosphere.ai.AiEvent.Handoff("tutor", "grader", "routed"));
            session.complete();
        };
        var handler = newScriptedHandler(new WordGuardrail());
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(subscriber(), "a forbidden question");

        awaitComplete(bystander);
        assertTrue(ofType(parsed(bystander), "prompt").isEmpty(),
                "a handoff is not reply text: " + capture.framesFor(bystander.uuid()));
    }

    @Test
    void broadcastReplyTrue_aTypedPromptTheGuardrailBlocksIsNotShownWhenStreamCarriesAnotherText()
            throws Exception {
        var bystander = subscriber();
        ScriptedEndpoint.script = (message, session) -> session.stream("Rephrase politely: the question");
        var handler = newScriptedHandler(new WordGuardrail());
        handler.setBroadcastReply(true);

        handler.dispatchPrompt(subscriber(), "a forbidden question");

        awaitComplete(bystander);
        assertTrue(capture.received(bystander.uuid(), REPLY), "the composed turn runs");
        assertTrue(ofType(parsed(bystander), "prompt").isEmpty(),
                "the typed prompt is screened on its own: " + capture.framesFor(bystander.uuid()));
    }

    /** Blocks a message naming "forbidden"; rewrites "secret" to "[hidden]". */
    static final class WordGuardrail implements org.atmosphere.ai.AiGuardrail {
        @Override
        public GuardrailResult inspectRequest(org.atmosphere.ai.AiRequest request) {
            if (request.message().contains("forbidden")) {
                return new GuardrailResult.Block("forbidden");
            }
            if (request.message().contains("secret")) {
                return new GuardrailResult.Modify(request.withMessage(request.message().replace("secret", "[hidden]")));
            }
            return new GuardrailResult.Pass();
        }
    }

    @AiEndpoint(path = "/atmosphere/room/{room}", broadcastReply = true)
    static final class ScriptedEndpoint {

        static volatile java.util.function.BiConsumer<String, StreamingSession> script;

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            script.accept(message, session);
        }
    }

    private AiEndpointHandler newScriptedHandler(org.atmosphere.ai.AiGuardrail guardrail) throws NoSuchMethodException {
        var method = ScriptedEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class);
        return new AiEndpointHandler(new ScriptedEndpoint(), method, 30_000L, "base",
                "/atmosphere/room/{room}", new ReplyingRuntime(), List.of(), null,
                AnnotatedLifecycle.scan(ScriptedEndpoint.class), null, List.of(guardrail), List.of(),
                org.atmosphere.ai.AiMetrics.NOOP);
    }

    private void awaitFrame(AtmosphereResource resource, String text) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (capture.received(resource.uuid(), text)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no " + text + " frame for " + resource.uuid() + ": "
                + capture.framesFor(resource.uuid()));
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private List<tools.jackson.databind.JsonNode> parsed(AtmosphereResource resource) {
        var nodes = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        for (var frame : capture.framesFor(resource.uuid())) {
            try {
                nodes.add(JSON.readTree(frame));
            } catch (RuntimeException e) {
                // not a JSON frame
            }
        }
        return nodes;
    }

    private static List<tools.jackson.databind.JsonNode> ofType(List<tools.jackson.databind.JsonNode> frames,
                                                              String type) {
        return frames.stream()
                .filter(f -> f.has("type") && type.equals(f.get("type").stringValue()))
                .toList();
    }

    private AiEndpointHandler newStreamingHandler(List<AiInterceptor> interceptors) throws NoSuchMethodException {
        var method = StreamingEndpoint.class.getDeclaredMethod(
                "onPrompt", String.class, StreamingSession.class);
        return new AiEndpointHandler(new StreamingEndpoint(), method, 30_000L, "base",
                "/atmosphere/room/{room}", new ReplyingRuntime(), interceptors,
                null, AnnotatedLifecycle.scan(StreamingEndpoint.class));
    }

    /** Installs a per-request math-room scope, as the classroom sample's interceptor does. */
    static final class MathRoomScope implements AiInterceptor {
        static final String REDIRECT = "This is the math room - ask me about mathematics.";

        @Override
        public org.atmosphere.ai.AiRequest preProcess(org.atmosphere.ai.AiRequest request,
                                                      AtmosphereResource resource) {
            return request.withMetadata(Map.of(
                    org.atmosphere.ai.governance.scope.ScopePolicy.REQUEST_SCOPE_METADATA_KEY,
                    new org.atmosphere.ai.governance.scope.ScopeConfig("Mathematics tutoring",
                            List.of("writing source code"),
                            org.atmosphere.ai.annotation.AgentScope.Breach.POLITE_REDIRECT, REDIRECT,
                            org.atmosphere.ai.annotation.AgentScope.Tier.RULE_BASED, 0.45,
                            false, false, "")));
        }
    }

    @AiEndpoint(path = "/atmosphere/room/{room}", broadcastReply = true)
    static final class StreamingEndpoint {

        static final AtomicInteger invocations = new AtomicInteger();

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            invocations.incrementAndGet();
            session.stream(message);
        }
    }

    private static final String PREFERRED = "open a CHG ticket and run release-bot";

    private static void recordPrefer(String conversationId) {
        GovernanceDecisionLog.installed().record(GovernanceDecisionLog.entryWithSnapshot(
                new AdmitPolicy(), Map.of("conversation_id", conversationId,
                        GovernanceDecisionLog.PREFERRED_KEY, PREFERRED),
                "prefer", "change management", 0.1));
    }

    private void awaitComplete(AtmosphereResource resource) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (capture.received(resource.uuid(), "\"type\":\"complete\"")) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no complete frame for " + resource.uuid() + ": "
                + capture.framesFor(resource.uuid()));
    }

    private AiEndpointHandler newGovernanceHandler() throws NoSuchMethodException {
        var method = GovernanceEndpoint.class.getDeclaredMethod(
                "onPrompt", String.class, StreamingSession.class);
        return new AiEndpointHandler(new GovernanceEndpoint(), method, 30_000L, "base",
                "/atmosphere/room/{room}", new ReplyingRuntime(),
                List.<AiInterceptor>of(new GovernanceFeedbackInterceptor()),
                null, AnnotatedLifecycle.scan(GovernanceEndpoint.class));
    }

    /** Streams the one reply and completes, recording the system prompt it was given. */
    private static final class ReplyingRuntime implements AgentRuntime {
        @Override public String name() { return "replying"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            GovernanceEndpoint.lastSystemPrompt = String.valueOf(context.systemPrompt());
            session.send(REPLY);
            session.complete();
        }
    }

    private static final class AdmitPolicy implements GovernancePolicy {
        @Override public String name() { return "production-release-advisor"; }
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext context) {
            return PolicyDecision.admit();
        }
    }

    @AiEndpoint(path = "/atmosphere/room/{room}", broadcastReply = true)
    static final class GovernanceEndpoint {

        static volatile String lastSystemPrompt = "";

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            // The real streaming path: interceptors, then the runtime.
            session.stream(message);
        }
    }

    // Uses the path-aware constructor so the handler carries a non-null path
    // template; the run-registry dispatch derives a non-null agent id from it.
    private AiEndpointHandler newHandler(StubEndpoint endpoint) {
        return new AiEndpointHandler(endpoint, promptMethod, 30_000L, "",
                "/atmosphere/room/{room}",
                mock(AgentRuntime.class), List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(StubEndpoint.class));
    }

    private AtmosphereResource subscriber() {
        var r = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                capture);
        room.addAtmosphereResource(r);
        return r;
    }

    /**
     * Records every frame the broadcaster delivers to a subscribed resource,
     * keyed by resource UUID, and counts down a latch when the AI reply lands.
     */
    private static final class CapturingHandler implements AtmosphereHandler {

        private final Map<String, List<String>> framesByUuid = new ConcurrentHashMap<>();
        private volatile CountDownLatch replyLatch = new CountDownLatch(0);

        void expectReplies(int n) {
            replyLatch = new CountDownLatch(n);
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return replyLatch.await(timeout, unit);
        }

        @Override
        public void onRequest(AtmosphereResource r) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) {
            var msg = e.getMessage();
            // The reply is broadcast as a RawMessage carrying the JSON wire frame;
            // unwrap to the JSON payload (mirrors AiEndpointHandler.onStateChange).
            var json = msg instanceof RawMessage raw ? String.valueOf(raw.message()) : String.valueOf(msg);
            framesByUuid.computeIfAbsent(e.getResource().uuid(), k -> new CopyOnWriteArrayList<>()).add(json);
            if (json.contains(REPLY)) {
                replyLatch.countDown();
            }
        }

        @Override
        public void destroy() {
        }

        boolean received(String uuid, String text) {
            return framesByUuid.getOrDefault(uuid, List.of()).stream()
                    .anyMatch(frame -> frame.contains(text));
        }

        List<String> framesFor(String uuid) {
            return framesByUuid.getOrDefault(uuid, List.of());
        }
    }

    @AiEndpoint(path = "/atmosphere/room/{room}", broadcastReply = true)
    static final class StubEndpoint {

        private final AtomicInteger invocations = new AtomicInteger();

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            // The @Prompt body is the LLM-call site. It must run exactly once per
            // prompt regardless of how many subscribers share the room — if it
            // ran once per subscriber, this counter would exceed 1 and the model
            // would be billed N times for one question.
            invocations.incrementAndGet();
            session.send(REPLY);
            session.complete();
        }
    }
}
