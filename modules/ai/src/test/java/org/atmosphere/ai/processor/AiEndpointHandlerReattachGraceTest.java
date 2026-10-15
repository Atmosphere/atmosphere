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
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.resume.RunDisconnectGrace;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private static AtmosphereResourceEventImpl closeEvent(boolean cancelled, int closeCode) {
        var event = mock(AtmosphereResourceEventImpl.class);
        when(event.isCancelled()).thenReturn(cancelled);
        when(event.isClosedByClient()).thenReturn(!cancelled);
        when(event.webSocketCloseCode()).thenReturn(closeCode);
        return event;
    }

    private AtmosphereResourceImpl startRun(HeldRuntime runtime) throws Exception {
        handler = new AiEndpointHandler(new HeldEndpoint(),
                HeldEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", "/ai/grace", runtime, List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(HeldEndpoint.class));
        var original = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
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
