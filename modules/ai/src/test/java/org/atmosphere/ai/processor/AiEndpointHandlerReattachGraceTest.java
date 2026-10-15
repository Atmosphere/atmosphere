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
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.resume.RunDisconnectGrace;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.interceptor.OnDisconnectInterceptor;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A client whose connection is lost — a dropped network, a reload — comes back
 * with the run id to pick its answer up. The disconnect used to cancel the model
 * call at once, so the client reattached to a run that had already been cut
 * short. A lost connection now leaves the run going for the reattach grace: a
 * reattach within it gets the rest of the answer through {@code complete}, and
 * a grace that expires cancels the call and ends the run with the
 * {@code cancelled} frame.
 */
// Constructs a real AtmosphereResourceImpl via the deprecated 6-arg
// constructor — the same harness AiEndpointHandlerReattachCancelledRunTest uses.
@SuppressWarnings("deprecation")
class AiEndpointHandlerReattachGraceTest {

    private static final String GRACE_PROPERTY = "atmosphere.ai.reattach.grace";

    static final AtomicReference<String> RUN_ID = new AtomicReference<>();

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private AiEndpointHandler handler;

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        RUN_ID.set(null);
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/grace");
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
    void aReattachWithinTheGraceGetsTheRestOfTheRunThroughComplete() throws Exception {
        System.setProperty(GRACE_PROPERTY, "2s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        // The connection is lost mid-answer.
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "a lost connection detaches the run for the grace");
        assertEquals(1, runtime.cancelled.getCount(),
                "a lost connection must not cancel the model call before the grace expires");

        var out = new StringWriter();
        RunReattachSupport.replayPendingRun(reattachingResource(RUN_ID.get(), out), RunRegistryHolder.get());
        runtime.finish();
        awaitRunEnd();
        // Past the grace: a rescinded cancel must not fire late.
        assertFalse(runtime.cancelled.await(2_500, TimeUnit.MILLISECONDS),
                "the reattach rescinds the pending cancel; it must not fire after the grace");

        var frames = out.toString().strip().split("\n");
        assertEquals(3, frames.length, "the backlog, the rest of the run, then complete: " + out);
        assertTrue(frames[0].contains("\"data\":\"partial \""), frames[0]);
        assertTrue(frames[1].contains("\"type\":\"streaming-text\"") && frames[1].contains("\"data\":\"rest\""),
                "the run kept going and the reattached client gets what it streamed after: " + frames[1]);
        assertTrue(frames[2].contains("\"type\":\"complete\""),
                "the run reattached within the grace completes: " + frames[2]);
    }

    @Test
    void anExpiredGraceCancelsTheRunWithACancelledFrameAndReleasesIt() throws Exception {
        System.setProperty(GRACE_PROPERTY, "1s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()));
        assertEquals(1, runtime.cancelled.getCount(), "the model call keeps going during the grace");
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "the model call is cancelled when the grace expires");
        awaitRunEnd();
        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isEmpty(),
                "the run is released from the live registry once its cancel ended it");

        var out = new StringWriter();
        RunReattachSupport.replayPendingRun(reattachingResource(RUN_ID.get(), out), RunRegistryHolder.get());
        var frames = out.toString().strip().split("\n");
        assertEquals(2, frames.length, "the streamed token, then one terminal frame: " + out);
        assertTrue(frames[1].contains("\"type\":\"cancelled\"") && frames[1].contains("\"data\":\"disconnect\""),
                "a run the expired grace cut short ends with the cancelled frame: " + frames[1]);
    }

    @Test
    void aDisconnectBeforeTheModelCallPublishesItsHandleCancelsItAtTheGraceExpiry() throws Exception {
        System.setProperty(GRACE_PROPERTY, "200ms");
        var runtime = new HeldRuntime();
        runtime.holdBeforeReturning = new CountDownLatch(1);
        var original = startRun(runtime);

        // The runtime is still inside executeWithHandle: no cancel handle yet.
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        Thread.sleep(500);
        runtime.holdBeforeReturning.countDown();

        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "a grace that expired before the handle was published cancels the call once it is");
        awaitRunEnd();
        var events = RunRegistryHolder.get().lookupReplayable(RUN_ID.get()).orElseThrow().replayableEvents();
        assertEquals("cancelled", events.get(events.size() - 1).type(), events.toString());
    }

    @Test
    void aZeroGraceCancelsALostConnectionAtOnce() throws Exception {
        System.setProperty(GRACE_PROPERTY, "0");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "a disabled grace cancels the model call on the disconnect");
    }

    @Test
    void aClientThatSendsTheCloseMessageLeftAndIsCancelledAtOnce() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        // Atmosphere's close message (an HTTP transport's explicit close).
        handler.onStateChange(new AtmosphereResourceEventImpl(original, false, false, true, null));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "a client that closed on purpose is not waited for");
    }

    /**
     * SSE, long-polling and streaming tell the server a reload with the close
     * message atmosphere.js sends as a beacon on {@code pagehide}, marked
     * {@code X-Atmosphere-Close-Reason: going-away}. Driven through the real
     * {@link OnDisconnectInterceptor}: the reload is given the grace a WebSocket
     * closed with 1001 gets, where the unmarked close message cancelled at once.
     */
    @ParameterizedTest
    @ValueSource(strings = {"sse", "long-polling", "streaming"})
    void aCloseMessageSentAsThePageGoesAwayIsGivenTheGrace(String transport) throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime, transport);

        sendCloseMessage(original, transport, HeaderConfig.CLOSE_REASON_GOING_AWAY);

        assertTrue(original.getAtmosphereResourceEvent().isClosedByClient());
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "a " + transport + " page going away detaches the run for the grace");
        assertEquals(1, runtime.cancelled.getCount(), "a reload must find the run still going");
        handler.destroy();
        handler = null;
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "destroy cancels detached runs instead of leaving them to a stopped timer");
    }

    /** {@code subscription.close()} sends the close message without a reason: a leave. */
    @ParameterizedTest
    @ValueSource(strings = {"sse", "long-polling", "streaming"})
    void anExplicitCloseMessageThroughTheInterceptorIsCancelledAtOnce(String transport) throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime, transport);

        sendCloseMessage(original, transport, null);

        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "a client that left is not waited for");
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "a " + transport + " client that closed on purpose is cancelled at once");
    }

    @Test
    void aWebSocketClosedNormallyLeftAndIsCancelledAtOnce() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        // atmosphere.js closes with 1000 'Client disconnect' on an explicit disconnect.
        handler.onStateChange(new AtmosphereResourceEventImpl(original, false, false, true, null)
                .webSocketCloseCode(1000));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "a WebSocket the client closed with 1000 is a leave, cancelled at once");
    }

    @Test
    void aWebSocketClosedGoingAwayIsGivenTheGrace() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        // A browser reloading or leaving the page closes with 1001 and no reason.
        handler.onStateChange(new AtmosphereResourceEventImpl(original, false, false, true, null)
                .webSocketCloseCode(1001));
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()));
        assertEquals(1, runtime.cancelled.getCount(), "a reload must find the run still going");
        // The endpoint going away cancels a run still waiting out its grace.
        handler.destroy();
        handler = null;
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "destroy cancels detached runs instead of leaving them to a stopped timer");
    }

    @Test
    void aReattachedClientThatLeavesCancelsTheRunOnceNobodyElseListens() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));

        var first = new AtomicReference<AtmosphereResourceEventListener>();
        var second = new AtomicReference<AtmosphereResourceEventListener>();
        RunReattachSupport.replayPendingRun(
                reattachingResource(RUN_ID.get(), new StringWriter(), first), RunRegistryHolder.get());
        RunReattachSupport.replayPendingRun(
                reattachingResource(RUN_ID.get(), new StringWriter(), second), RunRegistryHolder.get());
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "the reattach rescinds the pending cancel");

        // One of two reattached clients leaves: the other still listens.
        first.get().onDisconnect(closeEvent(false, 1000));
        assertEquals(1, runtime.cancelled.getCount(), "a run another client listens to keeps going");

        // The last one leaves on purpose: nobody is coming back for the run.
        second.get().onDisconnect(closeEvent(false, 1000));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "a reattached client that leaves on purpose cancels the run");
    }

    @Test
    void aReattachedClientThatLosesItsConnectionDetachesTheRunAgain() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));

        var listener = new AtomicReference<AtmosphereResourceEventListener>();
        RunReattachSupport.replayPendingRun(
                reattachingResource(RUN_ID.get(), new StringWriter(), listener), RunRegistryHolder.get());
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()));

        listener.get().onDisconnect(closeEvent(true, -1));
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "the reattached client's lost connection starts a new grace");
        assertEquals(1, runtime.cancelled.getCount());
    }

    /**
     * A blip: the client reconnects and reattaches before the server notices the
     * original connection is gone (an idle timeout, a failed write). The late
     * disconnect must not start a grace whose expiry cancels the run under the
     * client watching it.
     */
    @Test
    void aLateDisconnectOfTheOriginalConnectionLeavesARunAReattachedClientWatches() throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        var out = new StringWriter();
        RunReattachSupport.replayPendingRun(reattachingResource(RUN_ID.get(), out), RunRegistryHolder.get());
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "a run a reattached client listens to is not waiting for anyone to come back");
        assertFalse(runtime.cancelled.await(1_500, TimeUnit.MILLISECONDS),
                "a run a reattached client is listening to must not be cancelled by the grace");

        runtime.finish();
        awaitRunEnd();
        var frames = out.toString().strip().split("\n");
        assertTrue(frames[frames.length - 1].contains("\"type\":\"complete\""),
                "the reattached client gets the run through complete: " + out);
    }

    /** The client watching it leaves after the late disconnect: nobody is left, so it is cancelled. */
    @Test
    void theWatchingClientLeavingAfterALateDisconnectCancelsTheRun() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        var listener = new AtomicReference<AtmosphereResourceEventListener>();
        RunReattachSupport.replayPendingRun(
                reattachingResource(RUN_ID.get(), new StringWriter(), listener), RunRegistryHolder.get());
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertEquals(1, runtime.cancelled.getCount());

        listener.get().onDisconnect(closeEvent(false, 1000));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "the run's last client left on purpose: the run is cancelled");
    }

    /** The same, when the watching client loses its connection in turn: the run is detached for the grace. */
    @Test
    void theWatchingClientLosingItsConnectionAfterALateDisconnectDetachesTheRun() throws Exception {
        System.setProperty(GRACE_PROPERTY, "500ms");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        var listener = new AtomicReference<AtmosphereResourceEventListener>();
        RunReattachSupport.replayPendingRun(
                reattachingResource(RUN_ID.get(), new StringWriter(), listener), RunRegistryHolder.get());
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));

        listener.get().onDisconnect(closeEvent(true, -1));
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "the run's last client lost its connection: the run waits out the grace");
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "nobody came back within the grace: the run is cancelled");
    }

    /**
     * atmosphere.js reconnects under the tracking id it already holds. The
     * connection state the lost connection's run deferred releasing belongs to
     * the reconnected connection by the time the run ends, and is kept.
     */
    @Test
    void aReconnectUnderTheSameTrackingIdKeepsTheConversationTheRunRecorded() throws Exception {
        System.setProperty(GRACE_PROPERTY, "5s");
        var memory = new InMemoryConversationMemory();
        var runtime = new HeldRuntime();
        var original = startRun(runtime, memory);
        var uuid = original.uuid();
        var runId = RUN_ID.get();

        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        var out = new StringWriter();
        var back = reconnect(uuid, runId, out);
        assertFalse(RunDisconnectGrace.isDetached(runId), "the reconnect reattached to the run");

        runtime.finish();
        awaitRunEndCallbacks();
        assertTrue(out.toString().contains("\"type\":\"complete\""), out.toString());
        var history = memory.getHistory(uuid);
        assertTrue(history.stream().anyMatch(m -> "user".equals(m.role()) && "explain".equals(m.content())),
                "the conversation of a client back under its tracking id survives the run's end: " + history);
        assertTrue(history.stream().anyMatch(m -> "assistant".equals(m.role())),
                "the turn the run recorded is kept: " + history);

        // The connection that came back owns the state now: its own departure releases it.
        handler.onStateChange(leaving(back));
        assertEquals(List.of(), memory.getHistory(uuid),
                "the reconnected connection leaving releases the conversation");
    }

    /** With nobody back under the tracking id, the state is released once the run ends. */
    @Test
    void theConversationOfAConnectionThatDoesNotComeBackIsReleasedWhenItsRunEnds() throws Exception {
        System.setProperty(GRACE_PROPERTY, "5s");
        var memory = new InMemoryConversationMemory();
        var runtime = new HeldRuntime();
        var original = startRun(runtime, memory);
        var uuid = original.uuid();
        var runId = RUN_ID.get();

        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        RunReattachSupport.replayPendingRun(reattachingResource(runId, new StringWriter()), RunRegistryHolder.get());
        runtime.finish();
        awaitRunEndCallbacks();
        assertEquals(List.of(), memory.getHistory(uuid),
                "a connection nobody came back on is released after its run recorded its turn");
    }

    /**
     * The client reconnects under its tracking id before the server notices the
     * original connection is gone, the usual order over WebSocket. The late loss
     * of the original connection is the departure of a connection the reconnected
     * one replaced: it must not release the conversation the live connection
     * carries on.
     */
    @Test
    void aLateNoticedLossAfterASameIdReconnectKeepsTheLiveConnectionsConversation() throws Exception {
        System.setProperty(GRACE_PROPERTY, "5s");
        var memory = new InMemoryConversationMemory();
        var runtime = new HeldRuntime();
        var original = startRun(runtime, memory);
        var uuid = original.uuid();

        var out = new StringWriter();
        var back = reconnect(uuid, RUN_ID.get(), out);
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        runtime.finish();
        awaitRunEndCallbacks();

        assertTrue(out.toString().contains("\"type\":\"complete\""), out.toString());
        var history = memory.getHistory(uuid);
        assertTrue(history.stream().anyMatch(m -> "user".equals(m.role()) && "explain".equals(m.content())),
                "the live same-id connection must keep its conversation: " + history);
        assertTrue(history.stream().anyMatch(m -> "assistant".equals(m.role())),
                "the turn the run recorded is kept: " + history);

        // The live connection leaving is what releases it.
        handler.onStateChange(leaving(back));
        assertEquals(List.of(), memory.getHistory(uuid),
                "the reconnected connection leaving releases the conversation");
    }

    /**
     * The same order, with a client that came back without the run id: nothing
     * reattached, and the run keeps answering the connection live under its id.
     * The late loss of the replaced connection must not start a grace that
     * cancels it; the live connection leaving does.
     */
    @Test
    void aLateNoticedLossAfterASameIdReconnectLeavesTheRunsOfTheLiveConnectionGoing() throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        var back = reconnect(original.uuid(), null, new StringWriter());
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "the loss of a replaced connection must not detach the live connection's run");
        assertFalse(runtime.cancelled.await(1_500, TimeUnit.MILLISECONDS),
                "the loss of a replaced connection must not cancel the live connection's run");

        handler.onStateChange(leaving(back));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "the live connection leaving on purpose cancels its run");
    }

    /**
     * The loss of the original connection is noticed while the reconnect under
     * its tracking id is still being set up: suspended already, looking for a run
     * to reattach. The reconnect is the live connection by then, and the late
     * loss of the one it replaced must neither detach nor cancel its run.
     */
    @ParameterizedTest
    @ValueSource(strings = {"WEBSOCKET", "SSE", "LONG_POLLING"})
    void aLossNoticedWhileTheSameIdReconnectIsSetUpLeavesTheRunsOfTheLiveConnectionGoing(String transport)
            throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        var noticed = new AtomicBoolean();
        var back = reconnect(original.uuid(), AtmosphereResource.TRANSPORT.valueOf(transport), inv -> {
            if (!noticed.getAndSet(true)) {
                handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
            }
            return null;
        }, new StringWriter());
        assertTrue(noticed.get(), "the loss must be noticed while the reconnect is set up");
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "the loss of a replaced connection must not detach the live connection's run");
        assertFalse(runtime.cancelled.await(1_500, TimeUnit.MILLISECONDS),
                "the loss of a replaced connection must not cancel the live connection's run");

        handler.onStateChange(leaving(back));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "the live connection leaving on purpose cancels its run");
    }

    /**
     * The run a lost connection's state waited for ends while the reconnect under
     * its tracking id is still being set up: the reconnect owns that state by
     * then, and the run's end must not release the conversation it carries on.
     */
    @Test
    void aDetachedRunEndingWhileTheSameIdReconnectIsSetUpKeepsTheConversation() throws Exception {
        System.setProperty(GRACE_PROPERTY, "5s");
        var memory = new InMemoryConversationMemory();
        var runtime = new HeldRuntime();
        var original = startRun(runtime, memory);
        var uuid = original.uuid();
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));

        var ended = new AtomicBoolean();
        var back = reconnect(uuid, AtmosphereResource.TRANSPORT.WEBSOCKET, inv -> {
            if (!ended.getAndSet(true)) {
                runtime.finish();
                awaitRunEndCallbacks();
            }
            return null;
        }, new StringWriter());
        assertTrue(ended.get(), "the run must end while the reconnect is set up");
        var history = memory.getHistory(uuid);
        assertTrue(history.stream().anyMatch(m -> "user".equals(m.role()) && "explain".equals(m.content())),
                "the conversation of a client back under its tracking id survives the run's end: " + history);

        handler.onStateChange(leaving(back));
        assertEquals(List.of(), memory.getHistory(uuid),
                "the reconnected connection leaving releases the conversation");
    }

    /**
     * The reconnect asks its own question, then the server notices the original
     * connection is gone: the answer streaming to the live connection goes on
     * through {@code complete}, over WebSocket and over SSE.
     */
    @ParameterizedTest
    @ValueSource(strings = {"WEBSOCKET", "SSE", "LONG_POLLING"})
    void aLateNoticedLossLeavesTheAnswerTheSameIdReconnectAskedForStreaming(String transport) throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var frames = captureFrames();
        var runtime = new HeldRuntime();
        newHandler(runtime, null);
        var original = connect(transport.toLowerCase());

        var back = reconnect(original.uuid(), AtmosphereResource.TRANSPORT.valueOf(transport),
                inv -> null, new StringWriter());
        assertTrue(handler.dispatchPrompt(back, "explain"), "the reconnect's prompt is dispatched");
        assertTrue(runtime.streaming.await(10, TimeUnit.SECONDS), "the model call must be in flight");
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertFalse(runtime.cancelled.await(1_500, TimeUnit.MILLISECONDS),
                "the loss of a replaced connection must not cancel the reconnect's own run");

        runtime.finish();
        awaitRunEnd();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (frames.stream().noneMatch(f -> f.contains("\"type\":\"complete\"")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        var partial = indexOf(frames, "\"data\":\"partial \"");
        var rest = indexOf(frames, "\"data\":\"rest\"");
        var complete = indexOf(frames, "\"type\":\"complete\"");
        assertTrue(partial >= 0 && partial < rest && rest < complete,
                "the reconnect gets its whole answer through complete over " + transport + ": " + frames);
        assertEquals(-1, indexOf(frames, "\"type\":\"cancelled\""), frames.toString());
    }

    /**
     * A WebSocket, SSE or long-polling connection whose suspend times out ends without a
     * departure reaching the handler: it stops being tracked as the live
     * connection of its tracking id all the same, or every client that times
     * out and never comes back would stay pinned for good.
     */
    @ParameterizedTest
    @ValueSource(strings = {"websocket", "sse", "long-polling"})
    void aConnectionWhoseSuspendTimesOutStopsBeingTracked(String transport) throws Exception {
        newHandler(new HeldRuntime(), null);
        var connection = connect(transport);
        assertSame(connection, handler.liveConnection(connection.uuid()).orElse(null),
                "the suspended connection is tracked under its tracking id");

        assertTrue(new BlockingIOCometSupport(config).completeLifecycle(connection, false));
        assertTrue(connection.isCancelled(), "the timed-out connection is gone");
        assertTrue(handler.liveConnection(connection.uuid()).isEmpty(),
                "a " + transport + " connection that timed out must not stay tracked");
    }

    /** The timeout of a connection replaced under its tracking id leaves the live one tracked. */
    @ParameterizedTest
    @ValueSource(strings = {"websocket", "sse", "long-polling"})
    void theTimeoutOfAReplacedConnectionLeavesTheLiveOneTracked(String transport) throws Exception {
        newHandler(new HeldRuntime(), null);
        var original = connect(transport);
        var back = reconnect(original.uuid(),
                AtmosphereResource.TRANSPORT.valueOf(transport.toUpperCase(Locale.ROOT).replace('-', '_')),
                inv -> null, new StringWriter());

        assertTrue(new BlockingIOCometSupport(config).completeLifecycle(original, false));
        assertSame(back, handler.liveConnection(original.uuid()).orElse(null),
                "the connection live under the tracking id stays tracked");
    }

    /**
     * Suspending a connection closes one of its tracking id still subscribed to
     * the broadcaster, a poll the client gave up on among them, so that
     * departure may land inside the suspend: the connection being suspended is
     * already the live one, and the departure leaves its runs going.
     */
    @ParameterizedTest
    @ValueSource(strings = {"WEBSOCKET", "SSE", "LONG_POLLING"})
    void aLossNoticedInsideTheSuspendOfTheSameIdReconnectLeavesTheRunsGoing(String transport) throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        var noticed = new AtomicBoolean();
        var back = reconnect(original.uuid(), AtmosphereResource.TRANSPORT.valueOf(transport), inv -> null, inv -> {
            noticed.set(true);
            handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
            return inv.getMock();
        }, new StringWriter());
        assertTrue(noticed.get(), "the loss must be noticed inside the reconnect's suspend");
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "the loss of a replaced connection must not detach the live connection's run");
        assertFalse(runtime.cancelled.await(1_500, TimeUnit.MILLISECONDS),
                "the loss of a replaced connection must not cancel the live connection's run");

        handler.onStateChange(leaving(back));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "the live connection leaving on purpose cancels its run");
    }

    /**
     * A poll ends once a broadcast is written to it, with no departure reaching
     * the handler: it stops being tracked as the live connection of its
     * tracking id all the same, or every long-polling client would stay pinned.
     */
    @Test
    void aPollResumedByABroadcastStopsBeingTracked() throws Exception {
        newHandler(new HeldRuntime(), null);
        var poll = connect("long-polling");
        assertSame(poll, handler.liveConnection(poll.uuid()).orElse(null),
                "the suspended poll is tracked under its tracking id");

        poll.getBroadcaster().broadcast("reply").get(10, TimeUnit.SECONDS);
        assertFalse(poll.isSuspended(), "the broadcast resumed the poll");
        assertTrue(handler.liveConnection(poll.uuid()).isEmpty(),
                "a poll resumed by its reply must not stay tracked");
    }

    /**
     * A long-polling client reattaches with a reply of the backlog and no live
     * subscription: nothing would detach the run again if it never polled
     * again. Each such poll restarts the grace instead of rescinding it.
     */
    @Test
    void aLongPollingReattachRestartsTheGraceInsteadOfRescindingIt() throws Exception {
        System.setProperty(GRACE_PROPERTY, "1s");
        var runtime = new HeldRuntime();
        var original = startRun(runtime);

        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        Thread.sleep(400);
        var out = new StringWriter();
        var reattachedAt = System.nanoTime();
        RunReattachSupport.replayPendingRun(longPollingReattach(RUN_ID.get(), out), RunRegistryHolder.get());
        assertTrue(out.toString().contains("\"data\":\"partial \""), "the poll gets the backlog: " + out);
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "a long-polling reattach leaves nothing to detach the run again, so it stays detached");
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "a long-polling client that never polls again does not keep the run going");
        assertTrue(runtime.cancelledAt - reattachedAt >= TimeUnit.MILLISECONDS.toNanos(900),
                "the poll restarted the grace: the run was cancelled "
                        + TimeUnit.NANOSECONDS.toMillis(runtime.cancelledAt - reattachedAt)
                        + " ms after it");
    }

    /** {@code resource}'s client closing it on purpose. */
    private static org.atmosphere.cpr.AtmosphereResourceEvent leaving(AtmosphereResource resource) {
        var leave = mock(org.atmosphere.cpr.AtmosphereResourceEvent.class);
        when(leave.getResource()).thenReturn(resource);
        when(leave.isClosedByClient()).thenReturn(true);
        return leave;
    }

    /**
     * Atmosphere's close message for {@code suspended}, sent over {@code transport}
     * with {@code reason} as {@code X-Atmosphere-Close-Reason} (none when null),
     * through the default {@link OnDisconnectInterceptor}; the lifecycle it
     * completes dispatches the suspended resource's event to the handler. The
     * request has the shape atmosphere.js sends: every parameter, the reason
     * included, in the query string ({@code navigator.sendBeacon} cannot set
     * headers), turned into headers by the framework as for any HTTP request.
     */
    private void sendCloseMessage(AtmosphereResourceImpl suspended, String transport, String reason)
            throws Exception {
        var query = HeaderConfig.X_ATMOSPHERE_TRACKING_ID + "=" + suspended.uuid()
                + "&X-Atmosphere-Framework=5.0.32"
                + "&" + HeaderConfig.X_ATMOSPHERE_TRANSPORT + "=" + HeaderConfig.DISCONNECT_TRANSPORT_MESSAGE
                + (reason == null ? "" : "&" + HeaderConfig.X_ATMOSPHERE_CLOSE_REASON + "=" + reason);
        var closeRequest = new AtmosphereRequestImpl.Builder()
                .method("POST").pathInfo("/ai/grace").queryString(query).build();
        config.framework().configureRequestResponse(closeRequest, AtmosphereResponseImpl.newInstance());
        assertEquals(reason, closeRequest.getHeader(HeaderConfig.X_ATMOSPHERE_CLOSE_REASON),
                "the close reason of the query string is a header of the " + transport + " close message");
        var closing = mock(AtmosphereResourceImpl.class);
        when(closing.getRequest(false)).thenReturn(closeRequest);
        when(closing.uuid()).thenReturn(suspended.uuid());

        var interceptorConfig = mock(AtmosphereConfig.class);
        var factory = mock(AtmosphereResourceFactory.class);
        var framework = mock(AtmosphereFramework.class);
        var async = mock(AsynchronousProcessor.class);
        when(interceptorConfig.resourcesFactory()).thenReturn(factory);
        when(factory.findResource(suspended.uuid())).thenReturn(Optional.of(suspended));
        when(interceptorConfig.framework()).thenReturn(framework);
        doReturn(async).when(framework).getAsyncSupport();
        when(async.completeLifecycle(suspended, false)).thenAnswer(inv -> {
            handler.onStateChange(suspended.getAtmosphereResourceEvent());
            return true;
        });

        var interceptor = new OnDisconnectInterceptor();
        interceptor.configure(interceptorConfig);
        assertEquals(Action.CANCELLED, interceptor.inspect(closing),
                "the " + transport + " close message is answered by the interceptor");
    }

    private static AtmosphereResourceEventImpl closeEvent(boolean cancelled, int closeCode) {
        var event = mock(AtmosphereResourceEventImpl.class);
        when(event.isCancelled()).thenReturn(cancelled);
        when(event.isClosedByClient()).thenReturn(!cancelled);
        when(event.webSocketCloseCode()).thenReturn(closeCode);
        return event;
    }

    private AtmosphereResourceImpl startRun(HeldRuntime runtime) throws Exception {
        return startRun(runtime, (InMemoryConversationMemory) null);
    }

    /** A run started over {@code transport}, the wire value of {@code X-Atmosphere-Transport}. */
    private AtmosphereResourceImpl startRun(HeldRuntime runtime, String transport) throws Exception {
        var request = new AtmosphereRequestImpl.Builder()
                .headers(new HashMap<>(Map.of(HeaderConfig.X_ATMOSPHERE_TRANSPORT, transport)))
                .build();
        var original = startRun(runtime, null, request);
        assertEquals(transport, original.transport().name().toLowerCase().replace('_', '-'),
                "the run's connection uses the transport under test");
        return original;
    }

    private AtmosphereResourceImpl startRun(HeldRuntime runtime, InMemoryConversationMemory memory)
            throws Exception {
        return startRun(runtime, memory, AtmosphereRequestImpl.newInstance());
    }

    private void newHandler(HeldRuntime runtime, InMemoryConversationMemory memory) throws Exception {
        handler = new AiEndpointHandler(new HeldEndpoint(),
                HeldEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", "/ai/grace", runtime, List.<AiInterceptor>of(),
                memory, AnnotatedLifecycle.scan(HeldEndpoint.class));
    }

    /**
     * A {@code transport} connection (the wire value of {@code X-Atmosphere-Transport})
     * driven through the handler's connect path, which suspends it.
     */
    private AtmosphereResourceImpl connect(String transport) throws Exception {
        var request = new AtmosphereRequestImpl.Builder().method("GET").pathInfo("/ai/grace")
                .headers(new HashMap<>(Map.of(HeaderConfig.X_ATMOSPHERE_TRANSPORT, transport)))
                .build();
        var connection = new AtmosphereResourceImpl(config, room, request, AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), handler);
        handler.onRequest(connection);
        assertTrue(connection.isSuspended(), "the " + transport + " connection is suspended");
        return connection;
    }

    /** The JSON frames the streaming sessions broadcast on {@link #room}, in order. */
    private List<String> captureFrames() {
        var frames = new CopyOnWriteArrayList<String>();
        room.getBroadcasterConfig().addFilter((BroadcastFilter) (broadcasterId, originalMessage, message) -> {
            if (message instanceof RawMessage raw) {
                frames.add(String.valueOf(raw.message()));
            }
            return new BroadcastFilter.BroadcastAction(message);
        });
        return frames;
    }

    private static int indexOf(List<String> frames, String fragment) {
        for (var i = 0; i < frames.size(); i++) {
            if (frames.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    private AtmosphereResourceImpl startRun(HeldRuntime runtime, InMemoryConversationMemory memory,
            AtmosphereRequest request) throws Exception {
        newHandler(runtime, memory);
        var original = new AtmosphereResourceImpl(config, room,
                request, AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new org.atmosphere.handler.AbstractReflectorAtmosphereHandler.Default());
        room.addAtmosphereResource(original);
        handler.dispatchPrompt(original, "explain");
        assertTrue(runtime.streaming.await(10, TimeUnit.SECONDS), "the model call must be in flight");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (RUN_ID.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return original;
    }

    private static void awaitRunEnd() throws InterruptedException {
        var registry = RunRegistryHolder.get();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (registry.lookup(RUN_ID.get()).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static AtmosphereResource reattachingResource(String runId, StringWriter out)
            throws Exception {
        return reattachingResource(runId, out, new AtomicReference<>());
    }

    /** A long-polling poll carrying the run id: its reply is the backlog, written once. */
    private static AtmosphereResource longPollingReattach(String runId, StringWriter out) throws Exception {
        var resource = reattachingResource(runId, out);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        return resource;
    }

    /**
     * The client's WebSocket coming back under its own tracking id {@code uuid}
     * with the run id, as atmosphere.js reconnects: driven through the handler's
     * connect path, which suspends it and reattaches it to the run.
     */
    private AtmosphereResource reconnect(String uuid, String runId, StringWriter out) throws Exception {
        return reconnect(uuid, AtmosphereResource.TRANSPORT.WEBSOCKET, inv -> runId, out);
    }

    /**
     * The client coming back over {@code transport} under its tracking id
     * {@code uuid}; {@code runId} answers the connect path's read of the run id
     * it reattaches with, after the connection is suspended.
     */
    private AtmosphereResource reconnect(String uuid, AtmosphereResource.TRANSPORT transport, Answer<Object> runId,
            StringWriter out) throws Exception {
        return reconnect(uuid, transport, runId, inv -> inv.getMock(), out);
    }

    /** As above, {@code suspend} answering the connect path's suspend of the connection. */
    private AtmosphereResource reconnect(String uuid, AtmosphereResource.TRANSPORT transport, Answer<Object> runId,
            Answer<Object> suspend, StringWriter out) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new PrintWriter(out));
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(room);
        when(resource.transport()).thenReturn(transport);
        when(resource.isSuspended()).thenReturn(true);
        when(resource.addEventListener(any())).thenReturn(resource);
        when(resource.suspend(30_000L)).thenAnswer(suspend);
        when(request.getMethod()).thenReturn("GET");
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenAnswer(runId);
        handler.onRequest(resource);
        return resource;
    }

    /** Wait for the run's end to reach every callback registered on it. */
    private static void awaitRunEndCallbacks() throws Exception {
        awaitRunEnd();
        Thread.sleep(300);
    }

    /** A reattaching WebSocket whose disconnect listener lands in {@code listener}. */
    private static AtmosphereResource reattachingResource(String runId, StringWriter out,
            AtomicReference<AtmosphereResourceEventListener> listener) throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.addEventListener(any())).thenAnswer(inv -> {
            listener.set(inv.getArgument(0));
            return resource;
        });
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenReturn(runId);
        when(resource.uuid()).thenReturn("reattached");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new PrintWriter(out));
        return resource;
    }

    /**
     * Streams a token and holds the call open until {@link #finish()} streams
     * the rest and completes it, or a cancel completes it as runtime bridges do.
     */
    static final class HeldRuntime implements AgentRuntime {
        final CountDownLatch streaming = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        volatile CountDownLatch holdBeforeReturning;
        /** {@link System#nanoTime()} at which the model call was cancelled. */
        volatile long cancelledAt;
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
                cancelledAt = System.nanoTime();
                session.complete();
                cancelled.countDown();
            });
            this.handle = settable;
            session.send("partial ");
            streaming.countDown();
            var hold = holdBeforeReturning;
            if (hold != null) {
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return settable;
        }

        /** The model finishes its answer. */
        void finish() {
            session.send("rest");
            session.complete();
            handle.complete();
        }
    }

    @AiEndpoint(path = "/ai/grace")
    static final class HeldEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            RUN_ID.set(session.runId().orElseThrow());
            session.stream(message);
        }
    }
}
