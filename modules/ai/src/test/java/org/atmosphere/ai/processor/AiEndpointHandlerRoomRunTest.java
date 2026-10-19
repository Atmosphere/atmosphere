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
import org.atmosphere.ai.resume.MessageFramingWriter;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
    private static final String MAX_OPEN_PROPERTY = "atmosphere.ai.max-runs-open-after-prompt";

    static final AtomicReference<String> RUN_ID = new AtomicReference<>();
    /** Counted down once a self-writing {@code @Prompt} sent its first text. */
    static final AtomicReference<CountDownLatch> SENT_FIRST = new AtomicReference<>();
    /** Released to let a self-writing {@code @Prompt} send its text after the leave, and return. */
    static final AtomicReference<CountDownLatch> RESUME = new AtomicReference<>();
    /** The thread the {@code @Prompt} method runs on. */
    static final AtomicReference<Thread> PROMPT_THREAD = new AtomicReference<>();

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private AiEndpointHandler handler;
    /** Handlers a test starts besides {@link #handler}, destroyed after it. */
    private final List<AiEndpointHandler> otherHandlers = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> written = new ConcurrentHashMap<>();
    /** The connections {@link #reconnect} made, by tracking id. */
    private final Map<String, AtmosphereResource> reconnected = new ConcurrentHashMap<>();
    /** The listeners added to each of them, by tracking id. */
    private final Map<String, List<org.atmosphere.cpr.AtmosphereResourceEventListener>> listeners =
            new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        RUN_ID.set(null);
        SENT_FIRST.set(new CountDownLatch(1));
        RESUME.set(new CountDownLatch(1));
        PROMPT_THREAD.set(null);
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, PATH + "/" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(GRACE_PROPERTY);
        System.clearProperty(MAX_OPEN_PROPERTY);
        org.atmosphere.ai.resume.DurableRunSpineHolder.reset();
        org.atmosphere.ai.resume.DurableRunScopeHolder.clear();
        if (handler != null) {
            handler.destroy();
        }
        otherHandlers.forEach(AiEndpointHandler::destroy);
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

    /**
     * A prompter that leaves on purpose, in the shape each transport reports a
     * leave in: a WebSocket closed with 1000, and over SSE and long-polling the
     * close message atmosphere.js sends from {@code close()} without the
     * going-away reason. The run is cancelled at once and the room told so.
     */
    @ParameterizedTest
    @EnumSource(value = AtmosphereResource.TRANSPORT.class, names = {"WEBSOCKET", "SSE", "LONG_POLLING"})
    void aPrompterThatLeavesCancelsTheRoomRunWithTheCancelledFrameToTheRoom(AtmosphereResource.TRANSPORT transport)
            throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var member = subscriber(null);
        var prompter = startRun(runtime, null, transport);

        leave(prompter);
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "a prompter that left is not granted the grace");
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "a prompter that left is not waited for");
        awaitTerminal(member);

        assertEquals(List.of("streaming-text:partial ", "cancelled:disconnect"), text(member),
                "the room is told the run was cut short");
        assertTrue(DefaultStreamingSession.deliveryForSession(sessionIdOf(member)).isEmpty(),
                "the run's end closed the session that broadcast it to the room");
        awaitRunEnd();
        assertEquals("cancelled:disconnect", last(replayed()), "a reattach sees the end the room saw");
    }

    /**
     * An {@code @Prompt} that writes its reply itself has no model call to cut
     * short. Its prompter leaving still ends the run for the room with the
     * cancelled frame and closes the session that broadcast it; what the
     * {@code @Prompt} sends after that is dropped, from the room and the
     * replay alike, which ends with the frame the room saw.
     */
    @ParameterizedTest
    @EnumSource(value = AtmosphereResource.TRANSPORT.class, names = {"WEBSOCKET", "SSE", "LONG_POLLING"})
    void aPrompterThatLeavesEndsTheRoomRunOfAPromptThatWritesItsReplyItself(AtmosphereResource.TRANSPORT transport)
            throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var member = subscriber(null);
        var prompter = startSelfWritingRun(transport);

        leave(prompter);
        awaitTerminal(member);
        var sessionId = sessionIdOf(member);
        assertTrue(DefaultStreamingSession.deliveryForSession(sessionId).isEmpty(),
                "the leave closed the session that broadcast the run to the room");
        RESUME.get().countDown();
        awaitRunEnd();

        assertEquals(List.of("streaming-text:first", "cancelled:disconnect"), text(member),
                "the room is told the run was cut short, and is sent nothing after it");
        var replayed = replayed();
        assertEquals("cancelled:disconnect", last(replayed), "a reattach sees the end the room saw: " + replayed);
        assertFalse(replayed.contains("streaming-text:after-leave"), "nothing is recorded after the run's end");
    }

    /** The same for a run whose prompter's connection was lost and never came back within the grace. */
    @Test
    void anExpiredGraceEndsTheRoomRunOfAPromptThatWritesItsReplyItself() throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var member = subscriber(null);
        var prompter = startSelfWritingRun(AtmosphereResource.TRANSPORT.WEBSOCKET);

        lose(prompter);
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()), "a lost connection detaches the run for the grace");
        awaitTerminal(member);
        assertTrue(DefaultStreamingSession.deliveryForSession(sessionIdOf(member)).isEmpty(),
                "the expired grace closed the session that broadcast the run to the room");
        RESUME.get().countDown();
        awaitRunEnd();

        assertEquals(List.of("streaming-text:first", "cancelled:disconnect"), text(member));
        var replayed = replayed();
        assertEquals("cancelled:disconnect", last(replayed), "a reattach sees the end the room saw: " + replayed);
        assertFalse(replayed.contains("streaming-text:after-leave"), "nothing is recorded after the run's end");
    }

    /**
     * An {@code @Prompt} that returns without sending a terminal frame leaves
     * its run open, as an {@code @Prompt} whose reply other threads write on
     * after it returned needs: the room is sent no end while the prompter is
     * connected. The prompter gone for good — it left, or its connection was
     * lost and the grace expired — ends the run for the room with the
     * cancelled frame, which the run's replay ends with too.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRoomRunWhosePromptReturnedWithoutATerminalFrameStaysOpenUntilItsPrompterIsGone(boolean leaves)
            throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var member = subscriber(null);
        handler = newHandler(new HeldRuntime(), ReturningEndpoint.class);
        var prompter = subscriber(null);
        handler.dispatchPrompt(prompter, "explain");
        awaitRunId();
        awaitPromptReturned();
        // The room's broadcaster delivers on its own threads: the frame the
        // @Prompt sent may reach the member after the method returned.
        awaitText(member, 1);

        assertEquals(List.of("streaming-text:only"), text(member), "no end is sent while the prompter is connected");
        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isPresent(),
                "the run goes on while its prompter is connected");
        assertTrue(DefaultStreamingSession.deliveryForSession(sessionIdOf(member)).isPresent(),
                "the session that broadcasts the run to the room stays open");
        if (leaves) {
            leave(prompter);
        } else {
            lose(prompter);
            assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()), "a lost connection detaches the run for the grace");
        }
        awaitTerminal(member);

        assertEquals(List.of("streaming-text:only", "cancelled:disconnect"), text(member),
                "the prompter gone, the room is told the run ended");
        awaitRunEnd();
        assertEquals(text(member), replayed(), "the replay ends with what the room received; a reply written without stream() shows no prompt");
        assertTrue(DefaultStreamingSession.deliveryForSession(sessionIdOf(member)).isEmpty(),
                "the run's end closed the session that broadcast it to the room");
    }

    /** The same for a run whose reply reaches its own client only: its client leaving ends it. */
    @Test
    void aUnicastRunWhosePromptReturnedWithoutATerminalFrameEndsWhenItsClientLeaves() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        handler = newHandler(new HeldRuntime(), ReturningEndpoint.class, false);
        var prompter = subscriber(null);
        handler.dispatchPrompt(prompter, "explain");
        awaitRunId();
        awaitPromptReturned();
        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isPresent(),
                "the run goes on while its client is connected");

        leave(prompter);
        awaitRunEnd();

        assertEquals(List.of("streaming-text:only", "cancelled:disconnect"), replayed(),
                "a client gone for good ends the run, as cancelled");
    }

    /**
     * A client that leaves while its {@code @Prompt} method still writes the
     * reply itself, the method then returning without a terminal frame: the
     * run, whose reply reached that client only, is not left going with nobody
     * to end it — it ends, as cancelled, when the method returns.
     */
    @Test
    void aUnicastRunWhoseClientLeftWhileItsPromptRanEndsWhenThePromptReturns() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var prompter = startSelfWritingRun(AtmosphereResource.TRANSPORT.WEBSOCKET, false);

        leave(prompter);
        RESUME.get().countDown();
        awaitRunEnd();

        assertEquals(List.of("streaming-text:first", "streaming-text:after-leave", "cancelled:disconnect"),
                replayed(), "the run ends as cancelled once its @Prompt returned");
    }

    /**
     * An {@code @Prompt} that starts the model call on a thread of its own and
     * returns: the call's frames reach the client through its own terminal
     * frame, in a room and to the prompter alike, and the run's replay ends
     * with the frames the client received.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aPromptThatReturnsBeforeTheModelCallItStartedStreamsDeliversTheWholeAnswer(boolean toRoom)
            throws Exception {
        var runtime = new HeldRuntime();
        var member = subscriber(null);
        handler = newHandler(runtime, StreamingOnItsOwnThreadEndpoint.class, toRoom);
        var prompter = subscriber(null);
        handler.dispatchPrompt(prompter, "explain");
        assertTrue(runtime.streaming.await(10, TimeUnit.SECONDS), "the model call streams after the @Prompt returned");
        runtime.finish();
        var reader = toRoom ? member : prompter;
        awaitTerminal(reader);

        assertEquals(List.of("streaming-text:partial ", "streaming-text:rest", "complete"), text(reader),
                "the whole answer reaches the client");
        awaitRunEnd();
        assertEquals(toRoom ? promptThen(text(reader)) : text(reader), replayed(),
                "the replay ends with what the client received; a room run's starts with its prompt");
    }

    /**
     * An {@code @Prompt} whose reply a thread of its own writes after it
     * returned, as a callback of an asynchronous model client does: the reply
     * and its terminal frame reach the client, in a room and to the prompter
     * alike, and the run's replay ends with the frames the client received.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aReplyWrittenAfterThePromptReturnedReachesTheClientAndTheReplay(boolean toRoom) throws Exception {
        var member = subscriber(null);
        handler = newHandler(new HeldRuntime(), WritingAfterItReturnsEndpoint.class, toRoom);
        var prompter = subscriber(null);
        handler.dispatchPrompt(prompter, "explain");
        awaitRunId();
        awaitPromptReturned();
        RESUME.get().countDown();
        var reader = toRoom ? member : prompter;
        awaitTerminal(reader);

        assertEquals(List.of("streaming-text:async", "complete"), text(reader), "the reply reaches the client");
        awaitRunEnd();
        assertEquals(text(reader), replayed(),
                "the replay ends with what the client received; a reply written without stream() shows no prompt");
    }

    /**
     * An operator cancelling the turn of a room run whose {@code @Prompt}
     * writes its reply itself ends it for the room, as cancelling a model call
     * does: the cancelled frame, then nothing, and the replay ends the same.
     */
    @Test
    void anOperatorCancelEndsTheRoomRunOfAPromptThatWritesItsReplyItself() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var member = subscriber(null);
        var prompter = startSelfWritingRun(AtmosphereResource.TRANSPORT.WEBSOCKET);

        org.atmosphere.ai.AiStreamingSession.cancelInflightForResource(prompter.uuid());
        awaitTerminal(member);
        RESUME.get().countDown();
        awaitRunEnd();

        assertEquals(List.of("streaming-text:first", "cancelled:operator"), text(member),
                "the room is told the run was cut short, and is sent nothing after it");
        assertEquals(text(member), replayed(), "the replay ends with what the room received; a reply written without stream() shows no prompt");
    }

    /**
     * An operator cancelling a run whose reply reaches its own client only,
     * and whose {@code @Prompt} method returned with the session open, ends
     * it with the cancelled frame of the operator.
     */
    @Test
    void anOperatorCancelEndsAUnicastRunWhosePromptReturnedOpen() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        handler = newHandler(new HeldRuntime(), ReturningEndpoint.class, false);
        var prompter = subscriber(null);
        handler.dispatchPrompt(prompter, "explain");
        awaitRunId();
        awaitPromptReturned();

        assertTrue(org.atmosphere.ai.AiStreamingSession.resourceHasActiveSessions(prompter.uuid()));
        org.atmosphere.ai.AiStreamingSession.cancelInflightForResource(prompter.uuid());
        awaitRunEnd();

        assertEquals(List.of("streaming-text:only", "cancelled:operator"), replayed(),
                "the operator's cancel ends the run, as cancelled");
    }

    /**
     * A run left open after its {@code @Prompt} returned whose client lost its
     * connection and reattached within the grace — under its tracking id, or
     * a reloaded page's new one — is still reached by the idle sweep, which
     * ends it with its cancelled frame. The reattach registers the session
     * under the connection it came back on, so the sweep finds it there; a
     * run whose connection was lost and that nobody reattached to is reached
     * through its run only
     * ({@code AiStreamingSessionLeftOpenTurnTest#theSweepReachesALeftOpenTurnWhoseConnectionWasLostThroughItsRunOnly}).
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theIdleSweepReachesALeftOpenRunAfterItsClientReattached(boolean sameId) throws Exception {
        var reattached = startLeftOpenRunThenReattach(sameId);

        sweep(1L);
        awaitRunEnd();

        assertEquals("cancelled:sweep", last(replayed()), "the sweep ends the reattached run");
        assertFalse(org.atmosphere.ai.AiStreamingSession.resourceHasActiveSessions(reattached),
                "the ended run's session is registered under no tracking id");
    }

    /**
     * The same run is reached by an operator's cancel addressed to the
     * connection its client reattached on, as the admin plane's
     * {@code cancel-inflight} addresses it: it is found (no 404) and ended
     * with the cancelled frame of the operator.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anOperatorCancelReachesALeftOpenRunAfterItsClientReattached(boolean sameId) throws Exception {
        var reattached = startLeftOpenRunThenReattach(sameId);

        assertTrue(org.atmosphere.ai.AiStreamingSession.resourceHasActiveSessions(reattached),
                "the admin plane finds the reattached run's session");
        org.atmosphere.ai.AiStreamingSession.cancelInflightForResource(reattached);
        awaitRunEnd();

        assertEquals("cancelled:operator", last(replayed()), "the operator's cancel ends the reattached run");
    }

    /**
     * Runs left open after their {@code @Prompt} returned are capped per
     * registry: past the cap, a run whose method returns open ends at the
     * return, as {@code complete}, and leaves the live runs; a left-open run
     * that ends frees its place.
     */
    @Test
    void aRunReturningOpenPastTheCapEndsAtItsReturn() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        System.setProperty(MAX_OPEN_PROPERTY, "1");
        RunRegistryHolder.install(new org.atmosphere.ai.resume.RunRegistry());
        handler = newHandler(new HeldRuntime(), ReturningEndpoint.class, false);
        var first = subscriber(null);
        handler.dispatchPrompt(first, "explain");
        awaitRunId();
        awaitPromptReturned();
        var firstRun = RUN_ID.get();
        assertTrue(RunRegistryHolder.get().lookup(firstRun).isPresent(), "the first run is left open");

        RUN_ID.set(null);
        handler.dispatchPrompt(subscriber(null), "explain");
        awaitRunId();
        awaitPromptReturned();
        awaitRunEnd();
        assertEquals(List.of("streaming-text:only", "complete"), replayed(),
                "past the cap the run ends at its return, as complete");
        assertTrue(RunRegistryHolder.get().lookup(firstRun).isPresent(), "the run left open first goes on");

        var secondRun = RUN_ID.get();
        RUN_ID.set(firstRun);
        leave(first);
        awaitRunEnd();
        RUN_ID.set(null);
        handler.dispatchPrompt(subscriber(null), "explain");
        awaitRunId();
        awaitPromptReturned();
        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isPresent(),
                "the left-open run that ended freed its place");
        assertFalse(RUN_ID.get().equals(secondRun));
    }

    /**
     * A durable run whose {@code @Prompt} returned with its session open is
     * marked terminal, and its lease released, when the run really ends — as
     * succeeded only when its session completed — not when the method
     * returned: the run still writes, and its journaled seams still record.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aDurableRunLeftOpenIsMarkedTerminalAtItsRealEnd(boolean completes) throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var journal = mock(org.atmosphere.ai.resume.EffectJournal.class,
                org.mockito.AdditionalAnswers.delegatesTo(new org.atmosphere.ai.resume.InMemoryEffectJournal()));
        org.atmosphere.ai.resume.DurableRunSpineHolder.install(new org.atmosphere.ai.resume.DurableRunSpine(
                journal, new org.atmosphere.ai.resume.DurableRunConfig(true, java.time.Duration.ofMinutes(5), true),
                "test-owner"));
        handler = newHandler(new HeldRuntime(),
                completes ? WritingAfterItReturnsEndpoint.class : ReturningEndpoint.class, false);
        var prompter = subscriber("alice");
        handler.dispatchPrompt(prompter, "explain");
        awaitRunId();
        awaitPromptReturned();

        verify(journal, never()).markTerminal(anyString(), any());
        verify(journal, never()).releaseLease(anyString(), anyString());
        if (completes) {
            RESUME.get().countDown();
        } else {
            leave(prompter);
        }
        awaitRunEnd();

        verify(journal, timeout(10_000)).markTerminal(RUN_ID.get(),
                completes ? org.atmosphere.ai.resume.EffectStatus.COMMITTED
                        : org.atmosphere.ai.resume.EffectStatus.FAILED);
        verify(journal, timeout(10_000)).releaseLease(RUN_ID.get(), "test-owner");
    }

    /**
     * Destroying one endpoint ends the runs it left open after their
     * {@code @Prompt} returned, with the cancelled frame of an operator, and
     * no other endpoint's: the run another endpoint left open goes on, and
     * the session sweeper, which that endpoint still uses, keeps running and
     * still ends that run. The destroy stopped the process-wide sweeper and
     * ended no run.
     */
    @Test
    void destroyingAnEndpointEndsItsOwnLeftOpenRunsAndLeavesTheSweeperToTheOthers() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var other = newHandler(new HeldRuntime(), ReturningEndpoint.class, false);
        otherHandlers.add(other);
        other.dispatchPrompt(subscriber("alice"), "explain");
        awaitRunId();
        awaitPromptReturned();
        var othersRun = RUN_ID.get();
        RUN_ID.set(null);
        handler = newHandler(new HeldRuntime(), ReturningEndpoint.class, false);
        handler.dispatchPrompt(subscriber("bob"), "explain");
        awaitRunId();
        awaitPromptReturned();

        handler.destroy();
        handler = null;

        awaitRunEnd();
        assertEquals(List.of("streaming-text:only", "cancelled:operator"), replayed(),
                "the destroyed endpoint's left-open run is ended");
        assertTrue(RunRegistryHolder.get().lookup(othersRun).isPresent(), "another endpoint's run goes on");
        assertTrue(sweeperRunning(), "the sweeper keeps running for the endpoint still live");
        RUN_ID.set(othersRun);
        sweep(1L);
        awaitRunEnd();
        assertEquals("cancelled:sweep", last(replayed()), "the sweep still ends the other endpoint's run");
    }

    /**
     * The runs a destroyed endpoint left open free their places under the
     * per-registry cap: a run another endpoint leaves open afterwards is
     * kept open, not ended at its return.
     */
    @Test
    void destroyingAnEndpointFreesTheCapPlacesOfItsLeftOpenRuns() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        System.setProperty(MAX_OPEN_PROPERTY, "1");
        RunRegistryHolder.install(new org.atmosphere.ai.resume.RunRegistry());
        handler = newHandler(new HeldRuntime(), ReturningEndpoint.class, false);
        handler.dispatchPrompt(subscriber("alice"), "explain");
        awaitRunId();
        awaitPromptReturned();
        handler.destroy();
        handler = null;

        RUN_ID.set(null);
        var other = newHandler(new HeldRuntime(), ReturningEndpoint.class, false);
        otherHandlers.add(other);
        other.dispatchPrompt(subscriber("bob"), "explain");
        awaitRunId();
        awaitPromptReturned();

        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isPresent(),
                "the destroyed endpoint's run freed its place: this run is left open");
    }

    /**
     * Destroying one endpoint cancels the runs it started that wait out a
     * reattach grace, with the cancelled frame of an operator, and no other
     * endpoint's: the run another endpoint started keeps its grace armed. The
     * destroy cancelled every run waiting out a grace in the JVM.
     */
    @Test
    void destroyingAnEndpointCancelsOnlyTheGracesOfTheRunsItStarted() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var othersRuntime = new HeldRuntime();
        var other = newHandler(othersRuntime, HeldEndpoint.class, false);
        otherHandlers.add(other);
        var othersPrompter = subscriber("alice");
        other.dispatchPrompt(othersPrompter, "explain");
        assertTrue(othersRuntime.streaming.await(10, TimeUnit.SECONDS));
        awaitRunId();
        var othersRun = RUN_ID.get();
        other.onStateChange(new AtmosphereResourceEventImpl(othersPrompter, true, false));
        assertTrue(RunDisconnectGrace.isDetached(othersRun), "the other endpoint's run waits out its grace");
        RUN_ID.set(null);
        var runtime = new HeldRuntime();
        var prompter = startRun(runtime, "bob");
        lose(prompter);
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()), "this endpoint's run waits out its grace");

        handler.destroy();
        handler = null;

        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "the destroyed endpoint's run is cancelled");
        awaitRunEnd();
        assertEquals("cancelled:operator", last(replayed()), "cancelled by the endpoint's teardown");
        assertTrue(RunDisconnectGrace.isDetached(othersRun), "another endpoint's run keeps its grace");
        assertEquals(1, othersRuntime.cancelled.getCount(), "another endpoint's run is not cancelled");
        assertTrue(RunRegistryHolder.get().lookup(othersRun).isPresent(), "another endpoint's run goes on");
    }

    /**
     * The last endpoint destroyed stops the session sweeper and the reattach
     * grace timer — also one that only answered slash commands, whose session
     * ({@code StreamingSessions.start}) started the sweeper without a prompt —
     * and cancels the run still waiting out a grace rather than leave it to a
     * stopped timer; nothing restarts them afterwards. Its destroy left the
     * sweeper thread running.
     */
    @Test
    void theLastEndpointsDestroyStopsTheSweeperAndTheGraceTimer() throws Exception {
        resetHolds(org.atmosphere.ai.StreamingSessionSweeper.class);
        resetHolds(RunDisconnectGrace.class);
        handler = newHandler(new HeldRuntime(), HeldEndpoint.class);
        var command = org.atmosphere.ai.StreamingSessions.start(subscriber("alice"));
        assertTrue(sweeperRunning(), "the command's session starts the sweeper");
        var cancelled = new AtomicInteger();
        RunDisconnectGrace.detach("run-waiting", java.time.Duration.ofMinutes(5), cancelled::incrementAndGet);
        assertTrue(graceTimerRunning(), "a grace waits on the timer");

        handler.destroy();
        handler = null;

        assertFalse(sweeperRunning(), "the last endpoint gone, the sweeper stops");
        assertFalse(graceTimerRunning(), "the last endpoint gone, the grace timer stops");
        assertEquals(1, cancelled.get(), "the run waiting on the stopped timer is cancelled");
        org.atmosphere.ai.StreamingSessions.start(subscriber("bob"));
        assertFalse(sweeperRunning(), "nothing restarts the sweeper");
        command.complete();
    }

    /** Clears the holds earlier tests of this class left on {@code shared} (its {@code holders} count). */
    private static void resetHolds(Class<?> shared) throws Exception {
        var holders = shared.getDeclaredField("holders");
        holders.setAccessible(true);
        holders.setInt(null, 0);
    }

    /** Whether the process-wide reattach grace timer is scheduled. */
    private static boolean graceTimerRunning() throws Exception {
        var running = RunDisconnectGrace.class.getDeclaredMethod("timerRunning");
        running.setAccessible(true);
        return (boolean) running.invoke(null);
    }

    /** Whether the process-wide streaming-session sweeper is scheduled. */
    private static boolean sweeperRunning() throws Exception {
        var running = org.atmosphere.ai.StreamingSessionSweeper.class.getDeclaredMethod("isRunning");
        running.setAccessible(true);
        return (boolean) running.invoke(null);
    }

    /**
     * Starts a run whose {@code @Prompt} returns with its session open, its
     * reply reaching its client only, loses that client's connection and
     * reattaches it within the grace with the run id: under the same tracking
     * id ({@code sameId}) or a reloaded page's new one, authenticated as the
     * run's owner. Returns the tracking id it reattached under.
     */
    private String startLeftOpenRunThenReattach(boolean sameId) throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        handler = newHandler(new HeldRuntime(), ReturningEndpoint.class, false);
        var prompter = subscriber("alice");
        handler.dispatchPrompt(prompter, "explain");
        awaitRunId();
        awaitPromptReturned();
        lose(prompter);
        var uuid = sameId ? prompter.uuid() : "reloaded-" + UUID.randomUUID();
        reconnect(uuid, "alice", new StringWriter());
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "the reattach rescinds the grace");
        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isPresent(), "the reattached run goes on");
        return uuid;
    }

    /** One idle sweep of the AI sessions with {@code ttlMs}, as the session sweeper runs it. */
    private static void sweep(long ttlMs) throws Exception {
        Thread.sleep(ttlMs + 5);
        var sweep = org.atmosphere.ai.AiStreamingSession.class.getDeclaredMethod("sweepExpired", long.class);
        sweep.setAccessible(true);
        sweep.invoke(null, ttlMs);
    }

    /**
     * A room destroyed (its last member gone, an empty-destroy lifecycle
     * policy) before the run's end is not brought back by the session that
     * broadcast to it: its frames are dropped, and no empty room of the same
     * id is left in the factory.
     */
    @Test
    void aRoomRunDoesNotRecreateARoomDestroyedBeforeItsEnd() throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var runtime = new HeldRuntime();
        var prompter = startRun(runtime, null);
        var roomId = room.getID();

        lose(prompter);
        room.destroy();
        assertNull(factory.lookup(roomId, false), "the room is gone");
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "the grace expires and cancels the model call");
        awaitRunEnd();

        assertNull(factory.lookup(roomId, false), "the run's end must not recreate the destroyed room");
    }

    /**
     * A room destroyed and created again under the same id (an empty- or
     * idle-destroy lifecycle policy, a member joining afterwards) receives the
     * rest of the run: the session broadcasts to the room live under the id,
     * and does not add the departed prompter to it.
     */
    @Test
    void aRoomCreatedAgainUnderTheSameIdReceivesTheRestOfTheRun() throws Exception {
        var runtime = new HeldRuntime();
        var prompter = startRun(runtime, null);
        var roomId = room.getID();

        room.destroy();
        room = factory.get(DefaultBroadcaster.class, roomId);
        var member = subscriber(null);
        runtime.finish();
        awaitTerminal(member);

        assertEquals(List.of("streaming-text:rest", "complete"), text(member),
                "a member of the room created again receives the rest of the run");
        assertFalse(room.getAtmosphereResources().contains(prompter), "the departed prompter is not added back");
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
        System.setProperty(GRACE_PROPERTY, "3s");
        var runtime = new HeldRuntime();
        var member = subscriber("bob");
        var prompter = startRun(runtime, "alice");

        lose(prompter);
        var out = new StringWriter();
        reconnect("reloaded-" + UUID.randomUUID(), "alice", out);
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "the reloaded prompter's reattach rescinds the grace");
        // Waits past the grace: were the reattach not the owner's, its cancel would fire meanwhile.
        assertFalse(runtime.cancelled.await(4_500, TimeUnit.MILLISECONDS),
                "a run its owner came back to is not cancelled when the grace would have ended");

        runtime.finish();
        awaitTerminal(member);
        assertEquals(List.of("prompt:explain", "streaming-text:partial "), describe(lines(out)),
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
        assertEquals(List.of("prompt:explain", "streaming-text:partial "), describe(lines(out)), "the reloaded page gets the replay");
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "an anonymous room's run is cancelled when the grace ends, a reloaded prompter or not");
        awaitTerminal(member);
        assertEquals(List.of("streaming-text:partial ", "cancelled:disconnect"), text(member));
    }

    /**
     * In an authenticated room the prompter's other connection — another tab
     * of the same user, which read the run id from the room's reply — that
     * reattaches after a blip is the run's own client by its identity. That
     * connection leaving, or losing its connection, while the one the run
     * started on is still live is no reason to end the run: the room still
     * receives it through its {@code complete}. Its departure cancelled the
     * run at once (a leave) or when the grace expired (a loss).
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void thePromptersOtherConnectionThatReattachedThenDepartsLeavesTheRoomRunGoing(boolean leaves)
            throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var runtime = new HeldRuntime();
        var member = subscriber("bob");
        var prompter = startRun(runtime, "alice");
        var otherTab = "other-tab-" + UUID.randomUUID();
        reconnect(otherTab, "alice", new StringWriter());

        depart(otherTab, leaves);

        // Waits past the grace: a loss treated as the prompter's would cancel the run meanwhile.
        assertFalse(runtime.cancelled.await(1_500, TimeUnit.MILLISECONDS),
                "the run its prompter is still connected for is not cancelled");
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "no grace runs for it");
        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isPresent(), "the run goes on");
        runtime.finish();
        awaitTerminal(member);
        assertEquals(List.of("streaming-text:partial ", "streaming-text:rest", "complete"), text(member),
                "the room receives the run through its complete");
        assertTrue(room.getAtmosphereResources().contains(prompter));
    }

    /**
     * The connection the run started on was lost and the prompter reattached
     * on another one: that connection departing in turn ends the run as the
     * prompter's departure does — at once when it leaves, when the grace
     * expires when it is lost.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aReattachedConnectionDepartingAfterTheOneTheRunStartedOnWasLostEndsTheRun(boolean leaves)
            throws Exception {
        System.setProperty(GRACE_PROPERTY, leaves ? "30s" : "300ms");
        var runtime = new HeldRuntime();
        var member = subscriber("bob");
        var prompter = startRun(runtime, "alice");
        lose(prompter);
        var otherTab = "reloaded-" + UUID.randomUUID();
        reconnect(otherTab, "alice", new StringWriter());
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "the reattach rescinds the grace");

        depart(otherTab, leaves);

        if (!leaves) {
            assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()), "a lost connection detaches the run again");
        }
        // Well within the 30s grace for a leave: it is cancelled at once.
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "the prompter gone, the run is cancelled");
        awaitTerminal(member);
        assertEquals("cancelled:disconnect", last(text(member)), "the room is told the run was cut short");
    }

    /**
     * The connection the run started on was lost and the prompter reattached
     * on another one, which the application then closes: a close the
     * application makes is a leave, so the run is cancelled at once — the
     * listener the reattach holds is told of it as a close, not a disconnect.
     */
    @Test
    void anApplicationClosingAReattachedConnectionAfterTheOneTheRunStartedOnWasLostCancelsTheRun()
            throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var member = subscriber("bob");
        var prompter = startRun(runtime, "alice");
        lose(prompter);
        var otherTab = "reloaded-" + UUID.randomUUID();
        reconnect(otherTab, "alice", new PrintWriter(new StringWriter()));

        closeByApplication(otherTab);

        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "a close the application made is a leave");
        awaitTerminal(member);
        assertEquals("cancelled:disconnect", last(text(member)));
    }

    /**
     * The replay to a reattached connection fails to write: that connection
     * listens no more, so the run, whose own connection was lost, waits out
     * its grace again at once, and the connection departing afterwards ends
     * it as any reattached connection's departure does — at once when it
     * leaves, keeping the grace when it is lost.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aReattachWhoseReplayFailsToWriteLeavesTheRunToItsGraceAndItsDeparture(boolean leaves)
            throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        var member = subscriber("bob");
        var prompter = startRun(runtime, "alice");
        lose(prompter);
        var otherTab = "reloaded-" + UUID.randomUUID();
        var writer = new SwitchableWriter();
        writer.fail = true;
        reconnect(otherTab, "alice", new MessageFramingWriter(writer));
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "a reattach whose replay failed listens no more: the run waits out its grace again");

        depart(otherTab, leaves);

        if (leaves) {
            assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "the leave cancels the run at once");
            awaitTerminal(member);
            assertEquals("cancelled:disconnect", last(text(member)));
        } else {
            assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()), "the loss keeps the run's grace");
            assertEquals(1, runtime.cancelled.getCount(), "the run waits out its grace");
        }
    }

    /**
     * A frame forwarded live to a reattached connection fails to write: the
     * run, whose own connection was lost, waits out its grace again, and that
     * connection leaving afterwards cancels it at once. The failed write
     * removed the listener, so the leave reached nothing.
     */
    @Test
    void aReattachWhoseLiveWriteFailsLeavesTheRunToItsGraceAndItsLeave() throws Exception {
        System.setProperty(GRACE_PROPERTY, "30s");
        var runtime = new HeldRuntime();
        handler = newHandler(runtime, HeldEndpoint.class, false);
        var prompter = subscriber("alice");
        handler.dispatchPrompt(prompter, "explain");
        assertTrue(runtime.streaming.await(10, TimeUnit.SECONDS));
        awaitRunId();
        lose(prompter);
        var otherTab = "reloaded-" + UUID.randomUUID();
        var writer = new SwitchableWriter();
        reconnect(otherTab, "alice", new MessageFramingWriter(writer));
        assertFalse(RunDisconnectGrace.isDetached(RUN_ID.get()), "the reattach rescinds the grace");

        writer.fail = true;
        runtime.stream("lost");
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()),
                "a reattach whose write failed listens no more: the run waits out its grace again");

        depart(otherTab, true);

        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS), "the leave cancels the run at once");
        awaitRunEnd();
        assertEquals("cancelled:disconnect", last(replayed()));
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
        assertEquals(List.of("prompt:explain", "streaming-text:partial "), describe(lines(out)),
                "the frame captured after the member joined its room is left to the room");
        runtime.finish();
    }

    private void lose(AtmosphereResourceImpl prompter) throws Exception {
        handler.onStateChange(new AtmosphereResourceEventImpl(prompter, true, false));
        room.removeAtmosphereResource(prompter);
    }

    /**
     * The prompter leaving on purpose, in the shape its transport reports it:
     * the resource's own event, closed by the client, with the 1000 a WebSocket
     * closes with ({@code DefaultWebSocketProcessor.executeClose}), or, over an
     * HTTP transport, as the close message without the going-away reason
     * leaves it ({@code OnDisconnectInterceptor}).
     */
    private void leave(AtmosphereResourceImpl prompter) throws Exception {
        var event = prompter.getAtmosphereResourceEvent();
        if (prompter.transport() == AtmosphereResource.TRANSPORT.WEBSOCKET) {
            event.webSocketCloseCode(1000);
        }
        event.isClosedByClient(true);
        assertFalse(RunDisconnectGrace.isTransportLoss(event), "a leave, not a lost connection");
        handler.onStateChange(event);
        room.removeAtmosphereResource(prompter);
    }

    /** Starts a run in the room, prompted by a new member authenticated as {@code user} ({@code null}: anonymous). */
    private AtmosphereResourceImpl startRun(HeldRuntime runtime, String user) throws Exception {
        return startRun(runtime, user, AtmosphereResource.TRANSPORT.WEBSOCKET);
    }

    /** As above, the prompter connected over {@code transport}. */
    private AtmosphereResourceImpl startRun(HeldRuntime runtime, String user, AtmosphereResource.TRANSPORT transport)
            throws Exception {
        handler = newHandler(runtime, HeldEndpoint.class);
        var prompter = subscriber(user, transport);
        handler.dispatchPrompt(prompter, "explain");
        assertTrue(runtime.streaming.await(10, TimeUnit.SECONDS), "the model call must be in flight");
        awaitRunId();
        return prompter;
    }

    /** Starts a run of a {@code @Prompt} that writes its reply itself, prompted over {@code transport}. */
    private AtmosphereResourceImpl startSelfWritingRun(AtmosphereResource.TRANSPORT transport) throws Exception {
        return startSelfWritingRun(transport, true);
    }

    /** As above, its reply going to the room ({@code toRoom}) or to its prompter only. */
    private AtmosphereResourceImpl startSelfWritingRun(AtmosphereResource.TRANSPORT transport, boolean toRoom)
            throws Exception {
        handler = newHandler(new HeldRuntime(), SelfWritingEndpoint.class, toRoom);
        var prompter = subscriber(null, transport);
        handler.dispatchPrompt(prompter, "explain");
        assertTrue(SENT_FIRST.get().await(10, TimeUnit.SECONDS), "the @Prompt must have sent its first text");
        awaitRunId();
        return prompter;
    }

    private AiEndpointHandler newHandler(HeldRuntime runtime, Class<?> endpoint) throws Exception {
        return newHandler(runtime, endpoint, true);
    }

    /** A handler of {@code endpoint} whose reply goes to the room ({@code toRoom}) or to its prompter only. */
    private AiEndpointHandler newHandler(HeldRuntime runtime, Class<?> endpoint, boolean toRoom) throws Exception {
        var h = new AiEndpointHandler(endpoint.getDeclaredConstructor().newInstance(),
                endpoint.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", PATH, runtime, List.<AiInterceptor>of(), null,
                AnnotatedLifecycle.scan(endpoint));
        h.setBroadcastReply(toRoom);
        // The replay is read right after the connection opened: run it on the
        // opening thread (AiEndpointHandlerReattachOffOpenThreadTest covers the default).
        h.webSocketReattachExecutor(Runnable::run);
        return h;
    }

    /** Waits for the {@code @Prompt} method to have returned and the handler to be done with its thread. */
    private static void awaitPromptReturned() throws InterruptedException {
        var thread = PROMPT_THREAD.get();
        assertTrue(thread != null, "the @Prompt method must have run");
        assertTrue(thread.join(java.time.Duration.ofSeconds(10)), "the @Prompt method must have returned");
    }

    /** Waits for {@code thread} to end, as an {@code @Prompt}'s own thread does to write after it returned. */
    static void awaitEnd(Thread thread) {
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitRunId() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (RUN_ID.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(RUN_ID.get() != null, "the run must have started");
    }

    /** Waits for the run to leave the live registry: its execution ended. */
    private static void awaitRunEnd() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (RunRegistryHolder.get().lookup(RUN_ID.get()).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isEmpty(), "the run must have ended");
    }

    /** What the run's replay buffer holds, as {@code type:data}. */
    private static List<String> replayed() {
        var run = RunRegistryHolder.get().lookupReplayable(RUN_ID.get()).orElseThrow();
        return run.replayBuffer().snapshot().stream()
                .map(e -> e.payload() == null || e.payload().isEmpty() ? e.type() : e.type() + ":" + e.payload())
                .toList();
    }

    /**
     * What a room run's replay holds for {@code frames} received: the echo of
     * the prompt that started the run ("explain"), its first event, then them.
     */
    private static List<String> promptThen(List<String> frames) {
        var out = new ArrayList<String>();
        out.add("prompt:explain");
        out.addAll(frames);
        return out;
    }

    private static String last(List<String> frames) {
        return frames.isEmpty() ? null : frames.get(frames.size() - 1);
    }

    /** A member of the room authenticated as {@code user} ({@code null}: anonymous); what it receives is recorded. */
    private AtmosphereResourceImpl subscriber(String user) {
        return subscriber(user, AtmosphereResource.TRANSPORT.WEBSOCKET);
    }

    /** As above, connected over {@code transport}. */
    private AtmosphereResourceImpl subscriber(String user, AtmosphereResource.TRANSPORT transport) {
        var request = AtmosphereRequestImpl.newInstance();
        if (user != null) {
            request.setAttribute("ai.userId", user);
        }
        var r = new AtmosphereResourceImpl(config, room, request, AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), new Recorder());
        r.transport(transport);
        room.addAtmosphereResource(r);
        return r;
    }

    /**
     * The prompter's reloaded page connecting over WebSocket under the new
     * tracking id {@code uuid}, authenticated as {@code user}, with the run id:
     * driven through the handler's connect path.
     */
    private void reconnect(String uuid, String user, StringWriter out) throws Exception {
        reconnect(uuid, user, new MessageFramingWriter(out), invocation -> RUN_ID.get());
    }

    /** As above, its response written through {@code out}. */
    private void reconnect(String uuid, String user, PrintWriter out) throws Exception {
        reconnect(uuid, user, out, invocation -> RUN_ID.get());
    }

    /** As above, {@code runId} answering each read of the run id the connection carries. */
    private void reconnect(String uuid, String user, StringWriter out, Answer<Object> runId) throws Exception {
        reconnect(uuid, user, new MessageFramingWriter(out), runId);
    }

    /** As above, its response written through {@code out}. */
    private void reconnect(String uuid, String user, PrintWriter out, Answer<Object> runId) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(out);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(room);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.isSuspended()).thenReturn(true);
        doAnswer(invocation -> {
            listeners.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>()).add(invocation.getArgument(0));
            return resource;
        }).when(resource).addEventListener(any());
        doAnswer(invocation -> {
            listeners.getOrDefault(uuid, List.of()).remove(invocation.getArgument(0));
            return resource;
        }).when(resource).removeEventListener(any());
        reconnected.put(uuid, resource);
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

    /**
     * The connection {@link #reconnect} made under {@code uuid} departs: it
     * leaves ({@code leaves}, closed by the client with the 1000 of a
     * WebSocket) or its connection is lost. The handler and the listeners the
     * connection holds are told, as the resource notifies them.
     */
    private void depart(String uuid, boolean leaves) throws Exception {
        var resource = reconnected.get(uuid);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isClosedByClient()).thenReturn(leaves);
        when(event.isCancelled()).thenReturn(!leaves);
        assertEquals(!leaves, RunDisconnectGrace.isTransportLoss(event));
        handler.onStateChange(event);
        for (var listener : List.copyOf(listeners.getOrDefault(uuid, List.of()))) {
            listener.onDisconnect(event);
        }
    }

    /**
     * The application closes the connection {@link #reconnect} made under
     * {@code uuid}: the handler is told, and the listeners the connection
     * holds are told of a close, as the resource notifies them.
     */
    private void closeByApplication(String uuid) throws Exception {
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(reconnected.get(uuid));
        when(event.isClosedByApplication()).thenReturn(true);
        handler.onStateChange(event);
        for (var listener : List.copyOf(listeners.getOrDefault(uuid, List.of()))) {
            listener.onClose(event);
        }
    }

    /** A writer that fails every write once {@link #fail} is set, as a broken connection's does. */
    private static final class SwitchableWriter extends java.io.Writer {
        volatile boolean fail;

        @Override
        public void write(char[] buffer, int offset, int length) throws java.io.IOException {
            if (fail) {
                throw new java.io.IOException("connection broken");
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            if (fail) {
                throw new java.io.IOException("connection broken");
            }
        }

        @Override
        public void close() {
        }
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

    /** Waits for {@code r} to have received at least {@code count} text or terminal frames. */
    private void awaitText(AtmosphereResource r, int count) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (text(r).size() >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("fewer than " + count + " frames for " + r.uuid() + ": " + written.get(r.uuid()));
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
            out.add(msg == null ? frame : msg.isPrompt() ? "prompt:" + msg.text()
                    : msg.data() == null || msg.data().isEmpty() ? msg.type()
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

    /** Writes its reply itself: a first text, then, once released, a second, and returns without completing. */
    @AiEndpoint(path = PATH, broadcastReply = true)
    static final class SelfWritingEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) throws InterruptedException {
            RUN_ID.set(session.runId().orElseThrow());
            session.send("first");
            SENT_FIRST.get().countDown();
            RESUME.get().await(10, TimeUnit.SECONDS);
            session.send("after-leave");
        }
    }

    /** Sends a text and returns without a terminal frame. */
    @AiEndpoint(path = PATH, broadcastReply = true)
    static final class ReturningEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            PROMPT_THREAD.set(Thread.currentThread());
            RUN_ID.set(session.runId().orElseThrow());
            session.send("only");
        }
    }

    /** Starts the model call on a thread of its own once it returned. */
    @AiEndpoint(path = PATH, broadcastReply = true)
    static final class StreamingOnItsOwnThreadEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            RUN_ID.set(session.runId().orElseThrow());
            var prompt = Thread.currentThread();
            Thread.startVirtualThread(() -> {
                awaitEnd(prompt);
                session.stream(message);
            });
        }
    }

    /**
     * Returns at once; a thread of its own writes the reply and completes it
     * after it returned, once released, as an asynchronous client's callback does.
     */
    @AiEndpoint(path = PATH, broadcastReply = true)
    static final class WritingAfterItReturnsEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            PROMPT_THREAD.set(Thread.currentThread());
            RUN_ID.set(session.runId().orElseThrow());
            var prompt = Thread.currentThread();
            Thread.startVirtualThread(() -> {
                awaitEnd(prompt);
                try {
                    RESUME.get().await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                session.send("async");
                session.complete();
            });
        }
    }
}
