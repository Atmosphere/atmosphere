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

import org.atmosphere.ai.cache.AiResponseCacheInspector;
import org.atmosphere.ai.filter.AiStreamMessage;
import org.atmosphere.ai.filter.PiiRedactionFilter;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.ApplicationConfig;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A room reply ({@code @AiEndpoint(broadcastReply = true)}) reaches a
 * long-polling member of the room whole, as it reaches a WebSocket member,
 * against a real {@link DefaultBroadcaster}: a long-polling member is a
 * subscriber only while a poll of it waits, and that poll is answered by the
 * first frame written to it, so without a mailbox of its own the member lost
 * every frame broadcast before its next poll.
 *
 * <p>A long-polling subscriber here behaves like a poll: the first frame
 * written to it answers it and it leaves the broadcaster. Its next poll is
 * served by {@link LongPollingReplies#deliver}, as {@code AiEndpointHandler}
 * serves it.</p>
 */
// Real AtmosphereResourceImpl subscribers through the deprecated 6-arg
// constructor, as BroadcasterTest and AiEndpointHandlerBroadcastReplyTest do: no
// non-deprecated public constructor builds a standalone resource for a unit test.
@SuppressWarnings("deprecation")
class LongPollingRoomRepliesTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private final Map<String, List<String>> written = new ConcurrentHashMap<>();
    /** Init parameters of the framework, read when a client joins a room or a reply starts. */
    private final Map<String, String> params = new ConcurrentHashMap<>();
    private long reservedBefore;

    @BeforeEach
    void setUp() {
        config = spy(new AtmosphereFramework().getAtmosphereConfig());
        doAnswer(invocation -> params.get(invocation.<String>getArgument(0))).when(config).getInitParameter(anyString());
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/atmosphere/room/" + UUID.randomUUID());
        reservedBefore = LongPollingReplies.reservedBytes();
    }

    @AfterEach
    void tearDown() {
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void aLongPollingMemberWhosePollWaitsReceivesTheWholeRoomReplyOnItsNextPoll() throws Exception {
        var waiting = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("Hello ");
        session.send("room");
        session.complete();
        awaitComplete(socket);

        assertEquals(List.of("streaming-text:Hello ", "streaming-text:room", "complete"), frames(socket),
                "the WebSocket member streams the whole reply");
        assertEquals(List.of("streaming-text:Hello ", "streaming-text:room", "complete"), nextPoll(waiting),
                "the long-polling member's next poll must receive the whole reply, as the WebSocket member does");
        assertTrue(frames(waiting).isEmpty(),
                "no frame kept for the long-polling member may also be written to its poll: " + frames(waiting));
    }

    @Test
    void aLongPollingMemberBetweenPollsWhenTheReplyStartsReceivesItWhole() {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("one. ");
        session.send("two.");
        session.complete();
        awaitComplete(socket);

        assertTrue(LongPollingRoomReplies.members(room).contains(member.uuid()),
                "a long-polling client stays a member of the room between its polls");
        assertEquals(List.of("streaming-text:one. ", "streaming-text:two.", "complete"), nextPoll(member));
    }

    @Test
    void theRoomFiltersRunOnceAndTheirDeferredEndReachesTheLongPollingMember() throws Exception {
        var pii = new PiiRedactionFilter();
        room.getBroadcasterConfig().addFilter(pii);
        var waiting = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("Call me at 555-123-4567");
        session.complete();
        awaitComplete(socket);

        var expected = frames(socket);
        assertEquals(2, expected.size(), "the filtered text, then the deferred complete: " + expected);
        assertFalse(expected.get(0).contains("555-123-4567"), expected.get(0));
        assertEquals(expected, nextPoll(waiting),
                "the long-polling member receives the frames the filters let through, the deferred end included");
    }

    @Test
    void anApplicationBroadcastStillReachesAWaitingLongPollingPollDirectly() {
        LongPollingRoomReplies.install(room);
        var waiting = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);

        room.broadcast("plain chat message");

        awaitFrames(waiting, 1);
        assertEquals(List.of("plain chat message"), written.get(waiting.uuid()));
        assertTrue(nextPoll(waiting).isEmpty(), "only AI frames are kept for a member");
    }

    @Test
    void withABroadcasterCacheAWaitingMemberReceivesTheReplyWholeAndOnce() throws Exception {
        var cache = new UUIDBroadcasterCache();
        cache.configure(config);
        room.getBroadcasterConfig().setBroadcasterCache(cache);
        var waiting = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("cached.");
        session.complete();
        awaitComplete(socket);

        assertTrue(frames(waiting).isEmpty(), "the frames kept for the member are not written to its poll");
        assertEquals(List.of("streaming-text:cached.", "complete"), nextPoll(waiting));
        assertTrue(cache.retrieveFromCache(room.getID(), waiting.uuid()).isEmpty(),
                "the cache does not hand the member the frames kept for it a second time");
    }

    @Test
    void aMemberThatLeftIsNoLongerFed() {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        LongPollingReplies.discard(member.uuid());
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("bye.");
        session.complete();
        awaitComplete(socket);

        assertFalse(LongPollingRoomReplies.members(room).contains(member.uuid()));
        assertTrue(nextPoll(member).isEmpty(), "nothing is kept for a client that left");
    }

    @Test
    void aRoomReplyTooLargeForAMemberEndsWithAnErrorFrameForIt() {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("first. ");
        // Past the 8 MiB a long-polling reply may hold.
        session.send("x".repeat((int) LongPollingReplies.DEFAULT_MAX_REPLY_BYTES));
        session.send("after. ");
        session.complete();
        awaitComplete(socket);

        assertEquals(List.of("error:" + LongPollingRoomReplies.TOO_LARGE_MESSAGE), nextPoll(member),
                "a reply the member outgrew ends, for it, with an error frame in place of its frames");
        assertEquals(4, frames(socket).size(), "the WebSocket member still receives the whole reply");
    }

    @Test
    void aRoomReplyToEveryMemberLeavesRoomForAnotherClientsLongPollingReply() {
        // As many members as long-polling replies may wait in the JVM.
        params.put(LongPollingReplies.MAX_PARKED_REPLIES_PARAM, "4");
        LongPollingRoomReplies.install(room);
        var members = new ArrayList<AtmosphereResourceImpl>();
        for (var i = 0; i < 4; i++) {
            var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
            room.removeAtmosphereResource(member);
            members.add(member);
        }
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("to the room.");
        session.complete();
        awaitComplete(socket);

        var other = factory.get(DefaultBroadcaster.class, "/atmosphere/other/" + UUID.randomUUID());
        try {
            var client = subscriber(other, AtmosphereResource.TRANSPORT.LONG_POLLING);
            var unicast = StreamingSessions.start(client);
            unicast.send("to one client.");
            unicast.complete();

            assertEquals(List.of("streaming-text:to one client.", "complete"), nextPoll(client),
                    "a room reply kept for each of its members must not use up the long-polling replies"
                            + " another client's reply waits in");
        } finally {
            other.destroy();
        }
        for (var member : members) {
            assertEquals(List.of("streaming-text:to the room.", "complete"), nextPoll(member));
        }
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void aRoomFrameKeptForEveryMemberCountsItsBytesOnce() {
        LongPollingRoomReplies.install(room);
        var members = new ArrayList<AtmosphereResourceImpl>();
        for (var i = 0; i < 4; i++) {
            var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
            room.removeAtmosphereResource(member);
            members.add(member);
        }
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("a".repeat(4_000));
        session.send("b".repeat(4_000));
        session.complete();
        awaitComplete(socket);

        long replyBytes = 0;
        for (var frame : written.get(socket.uuid())) {
            replyBytes += frame.getBytes(StandardCharsets.UTF_8).length;
        }
        var held = LongPollingReplies.reservedBytes() - reservedBefore;
        assertTrue(held < 2 * replyBytes, "the " + replyBytes + " bytes of a room reply kept for 4 members"
                + " must count once against the bytes long-polling replies may hold, not once per member; held "
                + held);
        for (var member : members) {
            assertEquals(3, nextPoll(member).size());
        }
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void aReplyOneMemberOutgrowsLeavesTheOtherRepliesKeptForItWhole() {
        params.put(LongPollingReplies.MAX_REPLY_BYTES_PARAM, "2000");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var small = StreamingSessions.startRoomBroadcast(socket);
        var big = StreamingSessions.startRoomBroadcast(socket);
        small.send("small-head. ");
        big.send("x".repeat(2_500));
        small.send("small-tail.");
        small.complete();
        big.complete();
        awaitTerminals(socket, 2);

        var received = bySession(nextPollRaw(member));
        assertEquals(List.of("streaming-text:small-head. ", "streaming-text:small-tail.", "complete"),
                received.get(small.sessionId()),
                "a reply another reply outgrew the bounds next to must still reach the member whole");
        assertEquals(List.of("error:" + LongPollingRoomReplies.TOO_LARGE_MESSAGE), received.get(big.sessionId()));
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void theBoundsApplyToEachReplyNotToEveryReplyKeptForTheMember() {
        params.put(LongPollingReplies.MAX_REPLY_BYTES_PARAM, "2000");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var first = StreamingSessions.startRoomBroadcast(socket);
        var second = StreamingSessions.startRoomBroadcast(socket);
        first.send("f".repeat(1_200));
        second.send("s".repeat(900));
        first.complete();
        second.complete();
        awaitTerminals(socket, 2);

        var received = bySession(nextPollRaw(member));
        assertEquals(List.of("streaming-text:" + "f".repeat(1_200), "complete"), received.get(first.sessionId()));
        assertEquals(List.of("streaming-text:" + "s".repeat(900), "complete"), received.get(second.sessionId()),
                "two replies that each fit the bounds of one reply both reach the member whole");
    }

    @Test
    void aSecondOutgrownReplyDoesNotLetTheRestOfTheFirstReachTheMember() {
        params.put(LongPollingReplies.MAX_REPLY_BYTES_PARAM, "2000");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var a = StreamingSessions.startRoomBroadcast(socket);
        var b = StreamingSessions.startRoomBroadcast(socket);
        a.send("x".repeat(2_500));
        b.send("y".repeat(2_500));
        a.send("a-tail-after-abandon.");
        a.complete();
        b.complete();
        awaitTerminals(socket, 2);

        var received = bySession(nextPollRaw(member));
        assertEquals(List.of("error:" + LongPollingRoomReplies.TOO_LARGE_MESSAGE), received.get(a.sessionId()),
                "nothing of a reply may reach the member after the error frame that ended it for the member");
        assertEquals(List.of("error:" + LongPollingRoomReplies.TOO_LARGE_MESSAGE), received.get(b.sessionId()));
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void withABroadcasterCacheALongPollingMemberReceivesEveryFrameOfTheRoomReplyOnce() {
        var cache = new UUIDBroadcasterCache();
        cache.configure(config);
        // What AiEndpointHandler installs on the cache of an AI endpoint's broadcaster.
        cache.inspector(new AiResponseCacheInspector(false));
        room.getBroadcasterConfig().setBroadcasterCache(cache);
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("first. ");
        session.emit(new AiEvent.ToolStart("lookup", Map.of()));
        session.progress("working");
        session.send("second.");
        session.complete();
        awaitComplete(socket);

        var expected = frames(socket);
        assertEquals(5, expected.size(), "the WebSocket member streams the whole reply: " + expected);
        assertEquals(expected, nextPoll(member),
                "the long-polling member's next poll receives every frame the WebSocket member received,"
                        + " the tool event and the progress frame the cache does not keep included");
        assertTrue(cache.retrieveFromCache(room.getID(), member.uuid()).isEmpty(),
                "the cache must not hand the member the frames it already received a second time");
    }

    @Test
    void withSharedBroadcasterListenersNoRoomIsKeptForTheBroadcaster() {
        params.put(ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS, "true");
        var shared = factory.get(DefaultBroadcaster.class, "/atmosphere/shared/" + UUID.randomUUID());

        LongPollingRoomReplies.install(shared);
        var installedFilters = shared.getBroadcasterConfig().filters().stream()
                .filter(f -> f.getClass().getName().startsWith(LongPollingRoomReplies.class.getName()))
                .toList();
        shared.destroy();

        assertTrue(installedFilters.isEmpty(), "a room whose listener the broadcaster does not register would"
                + " never see its members: nothing of it may be installed; found " + installedFilters);
        assertFalse(LongPollingRoomReplies.installed(shared),
                "a destroyed broadcaster must not stay referenced by the rooms");
    }

    @Test
    void aMemberNoPollOfWhichComesBackHasItsKeptFramesReleased() throws Exception {
        params.put(LongPollingReplies.PARKED_TTL_MS_PARAM, "50");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("never polled for.");
        session.complete();
        awaitComplete(socket);
        assertTrue(LongPollingReplies.reservedBytes() > reservedBefore, "the frames are kept for the member");

        Thread.sleep(150);
        LongPollingReplies.sweepExpired();

        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(),
                "the frames kept for a member gone for its time to live are released");
        assertTrue(nextPoll(member).isEmpty());
    }

    /** A subscriber of the room on {@code transport}; a long-polling one leaves the room once written to. */
    private AtmosphereResourceImpl subscriber(AtmosphereResource.TRANSPORT transport) {
        return subscriber(room, transport);
    }

    /** A subscriber of {@code broadcaster} on {@code transport}; a long-polling one leaves once written to. */
    private AtmosphereResourceImpl subscriber(Broadcaster broadcaster, AtmosphereResource.TRANSPORT transport) {
        var r = new AtmosphereResourceImpl(config, broadcaster,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new Recorder());
        r.transport(transport);
        broadcaster.addAtmosphereResource(r);
        return r;
    }

    /** Records what the broadcaster writes to each subscriber; a written poll is answered and leaves. */
    private final class Recorder implements AtmosphereHandler {
        @Override
        public void onRequest(AtmosphereResource r) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) {
            var msg = e.getMessage();
            var text = msg instanceof RawMessage raw ? String.valueOf(raw.message()) : String.valueOf(msg);
            var resource = e.getResource();
            written.computeIfAbsent(resource.uuid(), k -> new CopyOnWriteArrayList<>()).add(text);
            if (resource.transport() == AtmosphereResource.TRANSPORT.LONG_POLLING) {
                resource.getBroadcaster().removeAtmosphereResource(resource);
            }
        }

        @Override
        public void destroy() {
        }
    }

    /** The frames a subscriber was written, as {@code type:data}. */
    private List<String> frames(AtmosphereResource r) {
        return describe(written.getOrDefault(r.uuid(), List.of()));
    }

    /** What {@code member}'s next poll receives. */
    private static List<String> nextPoll(AtmosphereResource member) {
        return describe(nextPollRaw(member));
    }

    /** The frames {@code member}'s next poll receives, as written. */
    private static List<String> nextPollRaw(AtmosphereResource member) {
        var poll = mock(AtmosphereResource.class);
        when(poll.uuid()).thenReturn(member.uuid());
        var frames = new ArrayList<String>();
        LongPollingReplies.deliver(poll, owner -> true, (p, delivered) -> frames.addAll(delivered));
        return frames;
    }

    /** {@code json} frames by session, each as {@code type:data}, in the order received. */
    private static Map<String, List<String>> bySession(List<String> json) {
        var out = new LinkedHashMap<String, List<String>>();
        for (var frame : json) {
            var msg = AiStreamMessage.parse(frame);
            var sessionId = msg != null ? msg.sessionId() : "?";
            out.computeIfAbsent(sessionId, k -> new ArrayList<>()).addAll(describe(List.of(frame)));
        }
        return out;
    }

    /** Waits until {@code r} was written {@code count} terminal frames. */
    private void awaitTerminals(AtmosphereResource r, int count) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var terminals = written.getOrDefault(r.uuid(), List.of()).stream()
                    .filter(f -> f.contains("\"type\":\"complete\"") || f.contains("\"type\":\"error\""))
                    .count();
            if (terminals >= count) {
                pause();
                return;
            }
            pause();
        }
        throw new AssertionError("fewer than " + count + " terminal frames for " + r.uuid() + ": "
                + written.get(r.uuid()));
    }

    private static List<String> describe(List<String> json) {
        var out = new ArrayList<String>();
        for (var frame : json) {
            try {
                var msg = AiStreamMessage.parse(frame);
                out.add(msg == null ? frame : msg.data() == null ? msg.type() : msg.type() + ":" + msg.data());
            } catch (RuntimeException e) {
                out.add(frame);
            }
        }
        return out;
    }

    private void awaitComplete(AtmosphereResource r) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (written.getOrDefault(r.uuid(), List.of()).stream().anyMatch(f -> f.contains("\"type\":\"complete\""))) {
                // The broadcaster writes on its own threads: let the frames before it land.
                pause();
                return;
            }
            pause();
        }
        throw new AssertionError("no complete frame for " + r.uuid() + ": " + written.get(r.uuid()));
    }

    private void awaitFrames(AtmosphereResource r, int count) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && written.getOrDefault(r.uuid(), List.of()).size() < count) {
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
