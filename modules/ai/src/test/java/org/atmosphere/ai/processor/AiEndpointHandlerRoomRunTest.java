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
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.DefaultStreamingSession;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.filter.AiStreamMessage;
import org.atmosphere.ai.resume.RunDisconnectGrace;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The run of an {@code @AiEndpoint(broadcastReply = true)} turn owns the
 * session that broadcasts its reply to the room. The prompter's connection
 * going away closed that session with it, so the room's members that never
 * left stopped receiving the reply, and a run cut short never told the room it
 * ended. And a prompter that reloads its page in an authenticated room, which
 * atmosphere.js reconnects under a new tracking id, is the run's own client by
 * its identity: its reattach within the grace keeps the run going.
 */
// Real AtmosphereResourceImpl subscribers through the deprecated 6-arg constructor,
// as AiEndpointHandlerBroadcastReplyTest does: no non-deprecated public constructor
// builds a standalone resource for a unit test.
@SuppressWarnings("deprecation")
class AiEndpointHandlerRoomRunTest {

    private static final String GRACE_PROPERTY = "atmosphere.ai.reattach.grace";
    private static final String PATH = "/ai/room-run";

    static final AtomicReference<String> RUN_ID = new AtomicReference<>();

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private AiEndpointHandler handler;
    private final Map<String, List<String>> written = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        RUN_ID.set(null);
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, PATH + "/" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(GRACE_PROPERTY);
        if (handler != null) {
            handler.destroy();
        }
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void aMemberThatNeverLeftReceivesTheRestOfTheReplyAfterThePromptersConnectionIsLost() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var member = subscriber(null);
        var prompter = startRun(runtime, null);

        lose(prompter);
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()), "a lost connection detaches the run for the grace");
        runtime.finish();
        awaitTerminal(member);

        assertEquals(List.of("streaming-text:partial ", "streaming-text:rest", "complete"), text(member),
                "the room's members receive the whole reply through complete, the prompter gone or not");
    }

    @Test
    void anExpiredGraceEndsTheRoomRunWithTheCancelledFrameAndClosesItsSession() throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var runtime = new HeldRuntime();
        var member = subscriber(null);
        var prompter = startRun(runtime, null);

        lose(prompter);
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "the grace expires and cancels the model call");
        awaitTerminal(member);

        assertEquals(List.of("streaming-text:partial ", "cancelled:disconnect"), text(member),
                "the room is told the run was cut short");
        assertTrue(DefaultStreamingSession.deliveryForSession(sessionIdOf(member)).isEmpty(),
                "the run's end closed the session that broadcast it to the room");
    }

    @Test
    void aPrompterThatLeavesCancelsTheRoomRunWithTheCancelledFrameToTheRoom() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var member = subscriber(null);
        var prompter = startRun(runtime, null);

        // atmosphere.js closes with 1000 on an explicit disconnect: a leave, cancelled at once.
        handler.onStateChange(new AtmosphereResourceEventImpl(prompter, false, false, true, null)
                .webSocketCloseCode(1000));
        room.removeAtmosphereResource(prompter);
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "a prompter that left is not waited for");
        awaitTerminal(member);

        assertEquals(List.of("streaming-text:partial ", "cancelled:disconnect"), text(member),
                "the room is told the run was cut short");
        assertTrue(DefaultStreamingSession.deliveryForSession(sessionIdOf(member)).isEmpty(),
                "the run's end closed the session that broadcast it to the room");
    }

    /**
     * atmosphere.js holds its tracking id in memory only, so a reloaded page
     * reconnects under a new one. In an authenticated room the run's owner is
     * known by its identity: the reloaded prompter's reattach within the grace
     * holds the grace off, where the tracking id alone made it a bystander
     * whose run was cancelled when the grace ended.
     */
    @Test
    void aReloadedPromptersReattachInAnAuthenticatedRoomKeepsTheRunGoing() throws Exception {
        System.setProperty(GRACE_PROPERTY, "1s");
        var runtime = new HeldRuntime();
        var member = subscriber("bob");
        var prompter = startRun(runtime, "alice");

        lose(prompter);
        var out = new StringWriter();
        reconnect("reloaded-" + UUID.randomUUID(), "alice", out);
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "the reloaded prompter's reattach rescinds the grace");
        assertFalse(runtime.cancelled.await(2_500, TimeUnit.MILLISECONDS),
                "a run its owner came back to is not cancelled when the grace would have ended");

        runtime.finish();
        awaitTerminal(member);
        assertEquals(List.of("streaming-text:partial "), describe(lines(out)),
                "the reloaded page is replayed what it missed; the room delivers it the rest");
        assertEquals(List.of("streaming-text:partial ", "streaming-text:rest", "complete"), text(member));
    }

    /**
     * Documented: in an anonymous room nothing but the tracking id tells the
     * prompter from any other member, so a reloaded page, under a new tracking
     * id, is replayed what it missed but does not keep the run going: the run
     * is cancelled when the grace ends.
     */
    @Test
    void aReloadedPromptersReattachInAnAnonymousRoomIsReplayedButTheRunEndsWithTheGrace() throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var runtime = new HeldRuntime();
        var member = subscriber(null);
        var prompter = startRun(runtime, null);

        lose(prompter);
        var out = new StringWriter();
        reconnect("reloaded-" + UUID.randomUUID(), null, out);
        assertEquals(List.of("streaming-text:partial "), describe(lines(out)), "the reloaded page gets the replay");
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "an anonymous room's run is cancelled when the grace ends, a reloaded prompter or not");
        awaitTerminal(member);
        assertEquals(List.of("streaming-text:partial ", "cancelled:disconnect"), text(member));
    }

    /**
     * The handler marks how far the run had got once the reconnecting
     * connection is suspended, a member of its room, and its replay of the run
     * writes the events up to that mark only: a frame captured after it, before
     * the replay, reaches the member through the room.
     */
    @Test
    void theReplayOfAReconnectingRoomMemberStopsAtTheRunEventsBeforeItsSuspend() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var prompter = startRun(runtime, null);

        lose(prompter);
        var streamed = new AtomicInteger();
        var out = new StringWriter();
        reconnect(prompter.uuid(), null, out, invocation -> {
            // The replay reading the run id: after the suspend, the run streamed on meanwhile.
            if (StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getMethodName().equals("replayPendingRun")))
                    && streamed.getAndIncrement() == 0) {
                runtime.stream("between");
            }
            return RUN_ID.get();
        });
        assertEquals(1, streamed.get(), "the run streamed between the suspend and the replay");
        assertEquals(List.of("streaming-text:partial "), describe(lines(out)),
                "the frame captured after the member joined its room is left to the room");
        runtime.finish();
    }

    private void lose(AtmosphereResourceImpl prompter) throws Exception {
        handler.onStateChange(new AtmosphereResourceEventImpl(prompter, true, false));
        room.removeAtmosphereResource(prompter);
    }

    /** Starts a run in the room, prompted by a new member authenticated as {@code user} ({@code null}: anonymous). */
    private AtmosphereResourceImpl startRun(HeldRuntime runtime, String user) throws Exception {
        handler = new AiEndpointHandler(new HeldEndpoint(),
                HeldEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", PATH, runtime, List.<AiInterceptor>of(), null,
                AnnotatedLifecycle.scan(HeldEndpoint.class));
        handler.setBroadcastReply(true);
        var prompter = subscriber(user);
        handler.dispatchPrompt(prompter, "explain");
        assertTrue(runtime.streaming.await(10, TimeUnit.SECONDS), "the model call must be in flight");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (RUN_ID.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return prompter;
    }

    /** A member of the room authenticated as {@code user} ({@code null}: anonymous); what it receives is recorded. */
    private AtmosphereResourceImpl subscriber(String user) {
        var request = AtmosphereRequestImpl.newInstance();
        if (user != null) {
            request.setAttribute("ai.userId", user);
        }
        var r = new AtmosphereResourceImpl(config, room, request, AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), new Recorder());
        room.addAtmosphereResource(r);
        return r;
    }

    /**
     * The prompter's reloaded page connecting over WebSocket under the new
     * tracking id {@code uuid}, authenticated as {@code user}, with the run id:
     * driven through the handler's connect path.
     */
    private void reconnect(String uuid, String user, StringWriter out) throws Exception {
        reconnect(uuid, user, out, invocation -> RUN_ID.get());
    }

    /** As above, {@code runId} answering each read of the run id the connection carries. */
    private void reconnect(String uuid, String user, StringWriter out, Answer<Object> runId) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new PrintWriter(out));
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(room);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.isSuspended()).thenReturn(true);
        when(resource.addEventListener(any())).thenReturn(resource);
        when(request.getMethod()).thenReturn("GET");
        when(request.getAttribute("ai.userId")).thenReturn(user);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenAnswer(runId);
        var attributes = new ConcurrentHashMap<String, Object>();
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(request).setAttribute(anyString(), any());
        when(request.getAttribute(RunReattachSupport.ROOM_JOIN_MARK_ATTRIBUTE))
                .thenAnswer(invocation -> attributes.get(RunReattachSupport.ROOM_JOIN_MARK_ATTRIBUTE));
        handler.onRequest(resource);
    }

    /** Records what the broadcaster writes to each subscriber. */
    private final class Recorder implements AtmosphereHandler {
        @Override
        public void onRequest(AtmosphereResource r) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) {
            var msg = e.getMessage();
            var text = msg instanceof RawMessage raw ? String.valueOf(raw.message()) : String.valueOf(msg);
            written.computeIfAbsent(e.getResource().uuid(), k -> new CopyOnWriteArrayList<>()).add(text);
        }

        @Override
        public void destroy() {
        }
    }

    /** The text and terminal frames {@code r} was written, as {@code type:data}, in order. */
    private List<String> text(AtmosphereResource r) {
        return describe(written.getOrDefault(r.uuid(), List.of())).stream()
                .filter(f -> f.startsWith("streaming-text") || f.startsWith("complete")
                        || f.startsWith("cancelled") || f.startsWith("error"))
                .toList();
    }

    private String sessionIdOf(AtmosphereResource r) {
        for (var frame : written.getOrDefault(r.uuid(), List.of())) {
            var msg = AiStreamMessage.parse(frame);
            if (msg != null && "streaming-text".equals(msg.type())) {
                return msg.sessionId();
            }
        }
        throw new AssertionError("no text frame for " + r.uuid());
    }

    private void awaitTerminal(AtmosphereResource r) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var frames = text(r);
            if (!frames.isEmpty() && (frames.get(frames.size() - 1).startsWith("complete")
                    || frames.get(frames.size() - 1).startsWith("cancelled"))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no terminal frame for " + r.uuid() + ": " + written.get(r.uuid()));
    }

    private static List<String> lines(StringWriter out) {
        var lines = new ArrayList<String>();
        for (var line : out.toString().split("\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static List<String> describe(List<String> json) {
        var out = new ArrayList<String>();
        for (var frame : json) {
            var msg = AiStreamMessage.parse(frame);
            out.add(msg == null ? frame : msg.data() == null || msg.data().isEmpty() ? msg.type()
                    : msg.type() + ":" + msg.data());
        }
        return out;
    }

    /**
     * Streams a token and holds the call open until {@link #finish()} streams
     * the rest and completes it, or a cancel completes it as runtime bridges do.
     */
    static final class HeldRuntime implements AgentRuntime {
        final CountDownLatch streaming = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        private volatile StreamingSession session;
        private volatile ExecutionHandle.Settable handle;

        @Override
        public String name() { return "held"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public int priority() { return 0; }

        @Override
        public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new UnsupportedOperationException("dispatched through executeWithHandle");
        }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
            this.session = session;
            var settable = new ExecutionHandle.Settable(() -> {
                session.complete();
                cancelled.countDown();
            });
            this.handle = settable;
            session.send("partial ");
            streaming.countDown();
            return settable;
        }

        /** The model streams {@code text}. */
        void stream(String text) {
            session.send(text);
        }

        /** The model finishes its answer. */
        void finish() {
            session.send("rest");
            session.complete();
            handle.complete();
        }
    }

    @AiEndpoint(path = PATH, broadcastReply = true)
    static final class HeldEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            RUN_ID.set(session.runId().orElseThrow());
            session.stream(message);
        }
    }
}
