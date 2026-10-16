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
import org.atmosphere.ai.resume.RunDisconnectGrace;
import org.atmosphere.ai.resume.RunEventCapturingSession;
import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistry;
import org.atmosphere.cache.BoundedMemoryCache;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.BroadcasterListener;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.Deliver;
import org.atmosphere.cpr.PerRequestBroadcastFilter;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void withABroadcasterCacheALongPollingMemberReceivesEveryFrameOfTheRoomReplyOnce() throws Exception {
        var cache = new UUIDBroadcasterCache();
        cache.configure(config);
        everyFrameOnceWith(cache);
    }

    @Test
    void withABoundedMemoryCacheALongPollingMemberReceivesEveryFrameOfTheRoomReplyOnce() throws Exception {
        // The cache the Spring Boot starter installs for atmosphere.cache.enabled=true.
        var cache = new BoundedMemoryCache();
        cache.configure(config);
        everyFrameOnceWith(cache);
    }

    private void everyFrameOnceWith(BroadcasterCache cache) throws Exception {
        // What AiEndpointHandler installs on the cache of an AI endpoint's broadcaster.
        cache.inspector(new AiResponseCacheInspector(false));
        room.getBroadcasterConfig().setBroadcasterCache(cache);
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        // A client the cache keeps broadcasts for while it is between polls.
        cache.cacheCandidate(room.getID(), member.uuid());
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
        // Not an AI frame: the cache keeps it for the member, as it would keep the reply's frames.
        room.broadcast("plain chat message").get(10, TimeUnit.SECONDS);

        assertEquals(expected, nextPoll(member),
                "the long-polling member's next poll receives every frame the WebSocket member received,"
                        + " the tool event and the progress frame the cache does not keep included");
        assertEquals(List.of("plain chat message"), cache.retrieveFromCache(room.getID(), member.uuid()),
                "the cache must not hand the member the frames it already received a second time");
    }

    @Test
    void withSharedBroadcasterListenersALongPollingMemberBetweenPollsReceivesTheRoomReplyWhole() {
        params.put(ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS, "true");
        var shared = factory.get(DefaultBroadcaster.class, "/atmosphere/shared/" + UUID.randomUUID());
        try {
            LongPollingRoomReplies.install(shared);
            var member = subscriber(shared, AtmosphereResource.TRANSPORT.LONG_POLLING);
            shared.removeAtmosphereResource(member);
            var socket = subscriber(shared, AtmosphereResource.TRANSPORT.WEBSOCKET);

            var session = StreamingSessions.startRoomBroadcast(socket);
            session.send("one. ");
            session.send("two.");
            session.complete();
            awaitComplete(socket);

            assertTrue(LongPollingRoomReplies.members(shared).contains(member.uuid()),
                    "a broadcaster sharing the factory's listeners is a room too");
            assertEquals(List.of("streaming-text:one. ", "streaming-text:two.", "complete"), nextPoll(member),
                    "with shared broadcaster listeners a long-polling member must receive the room reply whole,"
                            + " as without them");
        } finally {
            shared.destroy();
        }
        assertFalse(LongPollingRoomReplies.installed(shared),
                "a destroyed broadcaster must not stay referenced by the rooms");
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void withSharedBroadcasterListenersAndACacheAWaitingMemberReceivesTheReplyWholeAndOnce() throws Exception {
        params.put(ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS, "true");
        var shared = factory.get(DefaultBroadcaster.class, "/atmosphere/shared/" + UUID.randomUUID());
        try {
            var cache = new UUIDBroadcasterCache();
            cache.configure(config);
            shared.getBroadcasterConfig().setBroadcasterCache(cache);
            LongPollingRoomReplies.install(shared);
            var waiting = subscriber(shared, AtmosphereResource.TRANSPORT.LONG_POLLING);
            var socket = subscriber(shared, AtmosphereResource.TRANSPORT.WEBSOCKET);

            var session = StreamingSessions.startRoomBroadcast(socket);
            session.send("cached.");
            session.complete();
            awaitComplete(socket);

            assertTrue(frames(waiting).isEmpty(), "the frames kept for the member are not written to its poll");
            assertEquals(List.of("streaming-text:cached.", "complete"), nextPoll(waiting));
            assertTrue(cache.retrieveFromCache(shared.getID(), waiting.uuid()).isEmpty(),
                    "the cache does not hand the member the frames kept for it a second time");
        } finally {
            shared.destroy();
        }
    }

    @Test
    void withSharedBroadcasterListenersTheRoomsListenerLeavesTheFactoryWithItsLastRoomAndAloneOfAllListeners() {
        params.put(ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS, "true");
        BroadcasterListener application = new BroadcasterListenerAdapter() {
        };
        factory.addBroadcasterListener(application);
        var before = List.copyOf(factory.broadcasterListeners());
        var first = factory.get(DefaultBroadcaster.class, "/atmosphere/shared/" + UUID.randomUUID());
        var second = factory.get(DefaultBroadcaster.class, "/atmosphere/shared/" + UUID.randomUUID());
        try {
            LongPollingRoomReplies.install(first);
            LongPollingRoomReplies.install(second);
            assertEquals(1, roomListeners().size(), "one listener of the factory feeds every room of it");

            first.destroy();
            assertFalse(LongPollingRoomReplies.installed(first));
            assertTrue(LongPollingRoomReplies.installed(second));
            assertEquals(1, roomListeners().size(), "the listener stays while a room of the factory is left");
            var member = subscriber(second, AtmosphereResource.TRANSPORT.LONG_POLLING);
            second.removeAtmosphereResource(member);
            var socket = subscriber(second, AtmosphereResource.TRANSPORT.WEBSOCKET);
            var session = StreamingSessions.startRoomBroadcast(socket);
            session.send("still fed.");
            session.complete();
            awaitComplete(socket);
            assertEquals(List.of("streaming-text:still fed.", "complete"), nextPoll(member));
        } finally {
            first.destroy();
            second.destroy();
        }

        assertFalse(LongPollingRoomReplies.installed(second));
        assertTrue(roomListeners().isEmpty(), "the rooms' listener leaves the factory with its last room");
        assertEquals(before, List.copyOf(factory.broadcasterListeners()),
                "removing the rooms' listener must leave every other listener of the factory registered");
    }

    @Test
    void withSharedBroadcasterListenersDestroyingTheFactoryLeavesNoRoomBehind() {
        params.put(ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS, "true");
        var shared = factory.get(DefaultBroadcaster.class, "/atmosphere/shared/" + UUID.randomUUID());
        LongPollingRoomReplies.install(shared);
        var member = subscriber(shared, AtmosphereResource.TRANSPORT.LONG_POLLING);
        shared.removeAtmosphereResource(member);
        assertTrue(LongPollingRoomReplies.installed(shared));

        factory.destroy();

        assertFalse(LongPollingRoomReplies.installed(shared), "the framework's stop destroys its rooms");
        assertTrue(LongPollingRoomReplies.mailboxes(member.uuid()).isEmpty(), "and their members");
        assertTrue(roomListeners().isEmpty(), "and removes the rooms' listener from its factory");
    }

    @Test
    void theRepliesARoomMemberFollowsAtOnceAreAnInitParameter() {
        params.put("org.atmosphere.ai.longPolling.maxRoomRepliesPerMember", "1");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var a = StreamingSessions.startRoomBroadcast(socket);
        var b = StreamingSessions.startRoomBroadcast(socket);
        a.send("a1.");
        b.send("b1.");
        a.complete();
        b.complete();
        awaitTerminals(socket, 2);

        var received = bySession(nextPollRaw(member));
        assertEquals(List.of("streaming-text:a1.", "complete"), received.get(a.sessionId()));
        assertEquals(List.of("complete"), received.get(b.sessionId()),
                "following one reply at most, the member is not kept the frame of another sent meanwhile");
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void theBytesAFrameKeptForOneMoreMemberCountsAreAnInitParameter() {
        params.put("org.atmosphere.ai.longPolling.roomMemberFrameBytes", "1000");
        assertEquals(1000, heldForOneKeptFrame());
    }

    @Test
    void aMemberChargeThatIsNotAPositiveIntegerIsTheDefault() {
        params.put("org.atmosphere.ai.longPolling.roomMemberFrameBytes", "-5");
        assertEquals(16, heldForOneKeptFrame());
    }

    @Test
    void aNumberOfRepliesFollowedThatIsNotAPositiveIntegerIsTheDefault() {
        params.put("org.atmosphere.ai.longPolling.maxRoomRepliesPerMember", "0");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var a = StreamingSessions.startRoomBroadcast(socket);
        var b = StreamingSessions.startRoomBroadcast(socket);
        a.send("a1.");
        b.send("b1.");
        a.complete();
        b.complete();
        awaitTerminals(socket, 2);

        var received = bySession(nextPollRaw(member));
        assertEquals(List.of("streaming-text:a1.", "complete"), received.get(a.sessionId()));
        assertEquals(List.of("streaming-text:b1.", "complete"), received.get(b.sessionId()));
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    /**
     * The bytes one frame kept for one member holds beyond its own: one
     * member, one frame of a reply still running, then the reply ends and is taken.
     */
    private long heldForOneKeptFrame() {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("kept.");
        awaitFrames(socket, 1);
        var frameBytes = written.get(socket.uuid()).get(0).getBytes(StandardCharsets.UTF_8).length;
        var held = LongPollingReplies.reservedBytes() - reservedBefore - frameBytes;
        session.complete();
        awaitComplete(socket);
        assertEquals(List.of("streaming-text:kept.", "complete"), nextPoll(member));
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
        return held;
    }

    @Test
    void theRunFramesALongPollingMemberReceivedFromItsMailboxAreNotReplayedToIt() {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var run = new RunEventReplayBuffer();

        var session = new RunEventCapturingSession(StreamingSessions.startRoomBroadcast(socket), run);
        session.send("one. ");
        session.send("two.");
        session.complete();
        awaitComplete(socket);
        assertEquals(List.of("streaming-text:one. ", "streaming-text:two.", "complete"), nextPoll(member));

        assertTrue(run.claimNew(member.uuid()).isEmpty(), "the member received the run's frames from its mailbox:"
                + " a poll of it carrying the run id must not be handed them again");
        assertEquals(3, run.claimNew(socket.uuid()).size(),
                "a client that did not receive them through a long-polling poll is handed every retained event");
    }

    @Test
    void theRunFramesNotKeptForALongPollingMemberStayReplayableAfterALaterFrameReachedIt() {
        params.put(LongPollingReplies.MAX_ROOM_REPLIES_PER_MEMBER_PARAM, "1");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var runA = new RunEventReplayBuffer();
        var runB = new RunEventReplayBuffer();

        var a = new RunEventCapturingSession(StreamingSessions.startRoomBroadcast(socket), runA);
        var b = new RunEventCapturingSession(StreamingSessions.startRoomBroadcast(socket), runB);
        a.send("a1.");
        b.send("b1.");
        a.complete();
        b.complete();
        awaitTerminals(socket, 2);

        var received = bySession(nextPollRaw(member));
        assertEquals(List.of("streaming-text:a1.", "complete"), received.get(a.sessionId()));
        assertEquals(List.of("complete"), received.get(b.sessionId()),
                "following one reply at most, the member is not kept b1. while it follows the other");
        assertTrue(runA.claimNew(member.uuid()).isEmpty(), "the member received all of run a");
        assertEquals(List.of("b1.", ""), runB.claimNew(member.uuid()).stream().map(e -> e.payload()).toList(),
                "b1. never reached the member: its replay hands it over, with what followed it");
    }

    @Test
    void theRoomMembersPollsDoNotMakeTheRunForgetWhatItHandedAClientPollingWithItsId() {
        LongPollingRoomReplies.install(room);
        var owner = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(owner);
        var others = new ArrayList<AtmosphereResourceImpl>();
        for (int i = 0; i < RunEventReplayBuffer.MAX_REPLAY_POSITIONS; i++) {
            var other = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
            room.removeAtmosphereResource(other);
            others.add(other);
        }
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var run = new RunEventReplayBuffer();

        var session = new RunEventCapturingSession(StreamingSessions.startRoomBroadcast(socket), run);
        session.send("one. ");
        session.send("two.");
        session.complete();
        awaitComplete(socket);
        // The owner's poll carrying the run id is handed the run before the room delivers it.
        assertEquals(3, run.claimNew(owner.uuid()).size());

        for (var other : others) {
            assertEquals(List.of("streaming-text:one. ", "streaming-text:two.", "complete"), nextPoll(other));
        }

        assertTrue(run.claimNew(owner.uuid()).isEmpty(), "the room's members received the run from their"
                + " mailboxes: that must not make the run hand the owner every frame again");
    }

    /**
     * A member's mailbox already delivers the rest of the run to its polls: a
     * poll of it carrying the run id that was written the run's next event too
     * received each later frame directly, while the room kept the same frame
     * for its next poll, so the client received every later frame twice.
     */
    @Test
    void aRoomMembersPollCarryingTheRunIdIsNotAlsoWrittenTheRunsNextFrame() {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var registry = new RunRegistry();
        var run = registry.register("agent", "anonymous", "room-session", new ExecutionHandle.Settable(() -> { }),
                new RunEventReplayBuffer(), null);

        var session = new RunEventCapturingSession(StreamingSessions.startRoomBroadcast(socket),
                run.replayBuffer());
        session.sendMetadata(RunReattachSupport.RUN_ID_HEADER, run.runId());
        session.send("one.");
        awaitFrames(socket, 2);
        assertTrue(nextPoll(member).contains("streaming-text:one."));

        // The member's next poll carries the run id; its mailbox is empty, so the handler replays the run to it.
        var sink = new StringWriter();
        var resumed = new ArrayList<String>();
        var poll = suspendedPoll(member.uuid(), run.runId(), sink, resumed);
        room.addAtmosphereResource(member);
        assertEquals(0, RunReattachSupport.replayPendingRun(poll, registry), "the member received all of the run");

        session.send("two.");
        session.complete();
        awaitComplete(socket);

        assertEquals("", sink.toString(), "the room keeps the rest of the run for the member: the poll carrying"
                + " the run id must not be written it as well");
        assertTrue(resumed.isEmpty(), "the poll is left to the room: " + resumed);
        assertEquals(List.of("streaming-text:two.", "complete"), nextPoll(member));
        assertTrue(run.replayBuffer().claimNew(member.uuid()).isEmpty(), "the member received every frame of the run");
    }

    /**
     * A room member's poll carrying the run id that finds nothing new is not
     * written the run's frames, but still listens to the run while it waits:
     * a prompter that reloaded mid-reply and polls again within the grace must
     * not have its run cancelled when the grace ends while that poll waits,
     * as a unicast long-polling client's waiting poll does not.
     */
    @Test
    void aRoomMembersWaitingPollCarryingTheRunIdHoldsThePendingGraceOff() throws InterruptedException {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var registry = new RunRegistry();
        // The member is the prompter: the run was started under its tracking id.
        var run = registry.register("agent", "anonymous", member.uuid(), new ExecutionHandle.Settable(() -> { }),
                RunEventReplayBuffer.forRoomRun(null, config, room.getID()), null);
        var buffer = run.replayBuffer();

        var session = new RunEventCapturingSession(StreamingSessions.startRoomBroadcast(socket), buffer);
        session.sendMetadata(RunReattachSupport.RUN_ID_HEADER, run.runId());
        session.send("one.");
        awaitFrames(socket, 2);
        assertTrue(nextPoll(member).contains("streaming-text:one."));

        var grace = Duration.ofMillis(300);
        var cancelled = new AtomicBoolean();
        System.setProperty(AiConfig.REATTACH_GRACE_PROPERTY, grace.toMillis() + "ms");
        try {
            // The prompter's page went away mid-reply: the run waits out the grace.
            assertTrue(RunDisconnectGrace.detach(run.runId(), grace, () -> cancelled.set(true),
                    () -> buffer.subscriberCount() > 0));
            // Its new page polls with the run id; nothing is new for it, so the poll waits.
            var sink = new StringWriter();
            var resumed = new ArrayList<String>();
            var poll = suspendedPoll(member.uuid(), run.runId(), sink, resumed);
            room.addAtmosphereResource(member);
            assertEquals(0, RunReattachSupport.replayPendingRun(poll, registry), "the member received all of the run");
            assertEquals(1, buffer.subscriberCount(), "the member's waiting poll listens to the run");

            Thread.sleep(grace.toMillis() * 3);
            assertFalse(cancelled.get(), "a run a member's poll waits on must not be cancelled when the grace ends");

            session.send("two.");
            session.complete();
            awaitComplete(socket);
            assertEquals("", sink.toString(), "the room keeps the rest of the run for the member");
            assertTrue(resumed.isEmpty(), "the poll is left to the room: " + resumed);
            assertEquals(List.of("streaming-text:two.", "complete"), nextPoll(member),
                    "the reattached member receives the rest of the answer through complete");
            assertEquals(0, buffer.subscriberCount(), "the run's end releases the waiting poll");
        } finally {
            System.clearProperty(AiConfig.REATTACH_GRACE_PROPERTY);
            RunDisconnectGrace.runEnded(run.runId());
        }
    }

    /**
     * Every member of a room is handed the run id with the room's reply, and a
     * long-polling member's polls carry it. Such a poll of a member other than
     * the prompter restarted the run's reattach grace and waited on the run as a
     * watcher, so a run whose prompter had left for good was never cancelled
     * while a bystander kept polling; with WebSocket or SSE bystanders, which
     * never poll, it was.
     */
    @Test
    void aBystandersPollCarryingTheRunIdNeitherRestartsNorHoldsOffThePromptersGrace() throws InterruptedException {
        LongPollingRoomReplies.install(room);
        var prompter = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(prompter);
        var bystander = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(bystander);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var registry = new RunRegistry();
        var run = registry.register("agent", "anonymous", prompter.uuid(), new ExecutionHandle.Settable(() -> { }),
                RunEventReplayBuffer.forRoomRun(null, config, room.getID()), null);
        var buffer = run.replayBuffer();

        var session = new RunEventCapturingSession(StreamingSessions.startRoomBroadcast(socket), buffer);
        session.sendMetadata(RunReattachSupport.RUN_ID_HEADER, run.runId());
        session.send("one.");
        awaitFrames(socket, 2);
        assertTrue(nextPoll(bystander).contains("streaming-text:one."));

        var grace = Duration.ofMillis(400);
        var cancelled = new AtomicBoolean();
        System.setProperty(AiConfig.REATTACH_GRACE_PROPERTY, grace.toMillis() + "ms");
        try {
            // The prompter left for good: the run waits out the grace.
            assertTrue(RunDisconnectGrace.detach(run.runId(), grace, () -> cancelled.set(true),
                    () -> buffer.subscriberCount() > 0));
            // The bystander keeps polling with the run id it read from the room's reply.
            var deadline = System.nanoTime() + grace.multipliedBy(4).toNanos();
            while (!cancelled.get() && System.nanoTime() < deadline) {
                var poll = suspendedPoll(bystander.uuid(), run.runId(), new StringWriter(), new ArrayList<>());
                room.addAtmosphereResource(bystander);
                assertEquals(0, RunReattachSupport.replayPendingRun(poll, registry),
                        "the bystander received all of the run");
                assertEquals(0, buffer.subscriberCount(), "a bystander's poll must not wait on the run");
                assertTrue(cancelled.get() || RunDisconnectGrace.isDetached(run.runId()),
                        "a bystander's poll must not stand the prompter's grace down");
                room.removeAtmosphereResource(bystander);
                Thread.sleep(grace.toMillis() / 4);
            }
            assertTrue(cancelled.get(), "the run of a prompter gone for good is cancelled when its grace ends,"
                    + " whoever else polls with its id");
            assertFalse(RunDisconnectGrace.isDetached(run.runId()));
        } finally {
            System.clearProperty(AiConfig.REATTACH_GRACE_PROPERTY);
            RunDisconnectGrace.runEnded(run.runId());
        }
    }

    /** A suspended long-polling poll of {@code trackingId} on the room carrying {@code runId}. */
    private AtmosphereResource suspendedPoll(String trackingId, String runId, StringWriter sink, List<String> resumed) {
        var poll = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(poll.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenReturn(runId);
        when(poll.uuid()).thenReturn(trackingId);
        when(poll.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        when(poll.isSuspended()).thenReturn(true);
        when(poll.getBroadcaster()).thenReturn(room);
        when(poll.getAtmosphereConfig()).thenReturn(config);
        when(poll.resume()).thenAnswer(invocation -> {
            resumed.add("resume");
            return poll;
        });
        try {
            when(poll.getResponse()).thenReturn(response);
            when(response.getWriter()).thenReturn(new PrintWriter(sink));
        } catch (IOException e) {
            throw new AssertionError("a mock does not throw", e);
        }
        return poll;
    }

    @Test
    void aBroadcasterAlreadyDestroyedIsNotMadeARoom() {
        room.destroy();

        LongPollingRoomReplies.install(room);

        assertFalse(LongPollingRoomReplies.installed(room), "a destroyed broadcaster is no room");
        assertTrue(roomParts(room).isEmpty(),
                "nothing of a room is registered on a destroyed broadcaster: " + roomParts(room));
    }

    @Test
    void aBroadcasterDestroyedWhileItsRoomIsSetUpLeavesNoRoomBehind() {
        params.put(ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS, "true");
        var racing = factory.get(DestroyedMidInstall.class,
                "/atmosphere/racing/" + UUID.randomUUID());
        try {
            racing.armed = true;

            LongPollingRoomReplies.install(racing);

            assertTrue(racing.destroyedMidInstall, "the destroy raced the room's set-up");
            assertFalse(LongPollingRoomReplies.installed(racing),
                    "a room whose broadcaster was destroyed while it was set up leaves at once");
            assertTrue(roomListeners().isEmpty(), "and its listener leaves the factory: " + roomListeners());
        } finally {
            racing.armed = false;
            racing.destroyedMidInstall = false;
            racing.destroy();
        }
    }

    @Test
    void theSweepRemovesARoomWhoseBroadcasterWasDestroyedUnseen() {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        assertTrue(LongPollingRoomReplies.members(room).contains(member.uuid()));
        // The room does not see the destroy, as when it raced the room's set-up.
        roomParts(room).stream().filter(BroadcasterListener.class::isInstance)
                .forEach(l -> room.removeBroadcasterListener((BroadcasterListener) l));
        room.destroy();
        assertTrue(LongPollingRoomReplies.installed(room), "the destroy went by unseen");

        LongPollingRoomReplies.sweepExpired();

        assertFalse(LongPollingRoomReplies.installed(room), "the sweep removes the room of a destroyed broadcaster");
        assertTrue(LongPollingRoomReplies.mailboxes(member.uuid()).isEmpty(), "and its members");
    }

    /** The listeners and filters of a room registered on {@code broadcaster}. */
    private static List<Object> roomParts(Broadcaster broadcaster) {
        var parts = new ArrayList<Object>();
        ((DefaultBroadcaster) broadcaster).broadcasterListeners().stream()
                .filter(l -> l.getClass().getName().startsWith(LongPollingRoomReplies.class.getName() + "$"))
                .forEach(parts::add);
        broadcaster.getBroadcasterConfig().filters().stream()
                .filter(f -> f.getClass().getName().startsWith(LongPollingRoomReplies.class.getName() + "$"))
                .forEach(parts::add);
        return parts;
    }

    /**
     * A broadcaster destroyed by another thread while a room is set up on it:
     * once armed, it reads as destroyed from the moment its configuration is
     * first read, which setting up a room does after checking it is not destroyed.
     */
    public static final class DestroyedMidInstall extends DefaultBroadcaster {
        volatile boolean armed;
        volatile boolean destroyedMidInstall;

        @Override
        public BroadcasterConfig getBroadcasterConfig() {
            if (armed) {
                destroyedMidInstall = true;
            }
            return super.getBroadcasterConfig();
        }

        @Override
        public boolean isDestroyed() {
            return destroyedMidInstall || super.isDestroyed();
        }
    }

    /** The listeners of the rooms registered on the factory. */
    private List<BroadcasterListener> roomListeners() {
        return factory.broadcasterListeners().stream()
                .filter(l -> l.getClass().getName().startsWith(LongPollingRoomReplies.class.getName() + "$"))
                .toList();
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

    @Test
    void aMemberWhosePollResponseBlocksDoesNotHoldUpTheRoom() throws Exception {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var first = StreamingSessions.startRoomBroadcast(socket);
        first.send("one. ");
        first.complete();
        awaitComplete(socket);

        // The member's next poll takes the first reply, and its response blocks: a client that does not read it.
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var taken = new CopyOnWriteArrayList<String>();
        var delivery = blockedPoll(member, writing, release, taken, false);
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS), "the poll's response is being written");
            var second = StreamingSessions.startRoomBroadcast(socket);
            second.send("two.");
            awaitFrames(socket, 3);
            assertEquals(List.of("streaming-text:one. ", "complete", "streaming-text:two."), frames(socket),
                    "a long-polling member whose poll response blocks must not hold up the frames of the room's"
                            + " other members");
            second.complete();
            awaitTerminals(socket, 2);
        } finally {
            release.countDown();
        }
        assertTrue(delivery.get(10, TimeUnit.SECONDS), "the poll was answered with the frames kept for it");

        assertEquals(List.of("streaming-text:one. ", "complete"), describe(taken),
                "the poll receives the frames kept before its write started");
        assertEquals(List.of("streaming-text:two.", "complete"), nextPoll(member),
                "the frames kept while the write ran wait for the next poll, once");
        assertTrue(nextPoll(member).isEmpty(), "nothing is handed over twice");
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void theFramesAPollIsWritingNoLongerCountAgainstTheBoundsOfTheirReply() throws Exception {
        params.put(LongPollingReplies.MAX_REPLY_FRAMES_PARAM, "3");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("s1.");
        session.send("s2.");
        awaitFrames(socket, 2);

        // The member's next poll takes s1 and s2, and its response blocks: a client that reads it slowly.
        var release = new CountDownLatch(1);
        var writing = new CountDownLatch(1);
        var taken = new CopyOnWriteArrayList<String>();
        var delivery = blockedPoll(member, writing, release, taken, false);
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS), "the poll's response is being written");
            session.send("s3.");
            session.send("s4.");
            session.complete();
            awaitTerminals(socket, 1);
        } finally {
            release.countDown();
        }
        assertTrue(delivery.get(10, TimeUnit.SECONDS), "the poll was answered with the frames kept for it");

        assertEquals(List.of("streaming-text:s1.", "streaming-text:s2."), describe(taken));
        assertEquals(List.of("streaming-text:s3.", "streaming-text:s4.", "complete"), nextPoll(member),
                "the frames a poll is writing no longer wait for the member, so they must not count against the"
                        + " bounds of their reply: no more than 3 frames of it ever waited");
        assertTrue(nextPoll(member).isEmpty(), "nothing is handed over twice");
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void theFramesAFailedPollWriteLeavesKeptCountAgainstTheBoundsOfTheirReplyAgain() throws Exception {
        params.put(LongPollingReplies.MAX_REPLY_FRAMES_PARAM, "3");
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("s1.");
        session.send("s2.");
        awaitFrames(socket, 2);

        // The member's next poll takes s1 and s2, and the write of its response fails: they stay kept.
        var release = new CountDownLatch(1);
        var writing = new CountDownLatch(1);
        var delivery = blockedPoll(member, writing, release, new CopyOnWriteArrayList<>(), true);
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS), "the poll's response is being written");
            session.send("s3.");
            awaitFrames(socket, 3);
        } finally {
            release.countDown();
        }
        assertFalse(delivery.get(10, TimeUnit.SECONDS), "the poll's response was not written");
        session.send("s4.");
        session.complete();
        awaitTerminals(socket, 1);

        assertEquals(List.of("error:" + LongPollingRoomReplies.TOO_LARGE_MESSAGE), nextPoll(member),
                "s1, s2 and s3 wait for the member again, so s4 is past the 3 frames of a reply that may wait");
        assertTrue(nextPoll(member).isEmpty(), "nothing is handed over twice");
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    /**
     * A poll of {@code member} whose response write blocks until {@code release}
     * opens, then records the frames in {@code taken} or, when {@code fail}, fails.
     */
    private static CompletableFuture<Boolean> blockedPoll(AtmosphereResource member, CountDownLatch writing,
                                                          CountDownLatch release, List<String> taken,
                                                          boolean fail) {
        var poll = mock(AtmosphereResource.class);
        when(poll.uuid()).thenReturn(member.uuid());
        return CompletableFuture.supplyAsync(() -> LongPollingReplies.deliver(poll, owner -> true,
                (p, frames) -> {
                    writing.countDown();
                    try {
                        if (!release.await(60, TimeUnit.SECONDS)) {
                            throw new IOException("the test never released the write");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    if (fail) {
                        throw new IOException("the client went away in the middle of the write");
                    }
                    taken.addAll(frames);
                }));
    }

    @Test
    void aReplyPastTheRepliesAMemberFollowsIsKeptFromItsNextFrameOnceOneEnds() {
        LongPollingRoomReplies.install(room);
        var member = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        room.removeAtmosphereResource(member);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var followed = new ArrayList<StreamingSession>();
        for (var i = 0; i < LongPollingReplies.DEFAULT_MAX_ROOM_REPLIES_PER_MEMBER; i++) {
            var session = StreamingSessions.startRoomBroadcast(socket);
            session.send("head-" + i + ".");
            followed.add(session);
        }
        var extra = StreamingSessions.startRoomBroadcast(socket);
        extra.send("extra-head.");
        followed.get(0).complete();
        extra.send("extra-tail.");
        extra.complete();
        awaitTerminals(socket, 2);

        var received = bySession(nextPollRaw(member));
        assertEquals(List.of("streaming-text:extra-tail.", "complete"), received.get(extra.sessionId()),
                "a frame of a reply sent while the member follows as many replies as it may is not kept for it;"
                        + " once one of them ends, the reply is kept from its next frame on");
        assertEquals(List.of("streaming-text:head-0.", "complete"), received.get(followed.get(0).sessionId()));
        for (var session : followed.subList(1, followed.size())) {
            session.complete();
        }
        awaitTerminals(socket, 1 + LongPollingReplies.DEFAULT_MAX_ROOM_REPLIES_PER_MEMBER);
        assertEquals(LongPollingReplies.DEFAULT_MAX_ROOM_REPLIES_PER_MEMBER - 1, bySession(nextPollRaw(member)).size());
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void aFrameKeptForAMemberStaysOffItsWaitingPollWhileAnotherThreadDeliversTheNextFrame() throws Exception {
        LongPollingRoomReplies.install(room);
        var waiting = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        BroadcasterListener listener = ((DefaultBroadcaster) room).broadcasterListeners().stream()
                .filter(l -> l.getClass().getName().startsWith(LongPollingRoomReplies.class.getName()))
                .findFirst().orElseThrow();
        var suppressor = (PerRequestBroadcastFilter) room.getBroadcasterConfig().filters().stream()
                .filter(f -> f.getClass().getName().startsWith(LongPollingRoomReplies.class.getName()))
                .findFirst().orElseThrow();
        var a = new RawMessage(aiFrame("reply-a", "a0"));
        var b = new RawMessage(aiFrame("reply-b", "b0"));

        // With out-of-order broadcast the broadcaster delivers on several threads: each runs the room's
        // listener for its frame, then the per-request filters of that same frame, while another thread is
        // at another frame.
        listener.onMessage(room, new Deliver(a, null, a));
        var other = CompletableFuture.supplyAsync(() -> {
            listener.onMessage(room, new Deliver(b, null, b));
            return suppressor.filter(room.getID(), waiting, b, b).action();
        });
        var otherAction = other.get(10, TimeUnit.SECONDS);
        var action = suppressor.filter(room.getID(), waiting, a, a).action();

        assertEquals(BroadcastAction.ACTION.ABORT, action,
                "a frame kept for the member must not also be written to its waiting poll, whatever frame"
                        + " another delivery thread is at");
        assertEquals(BroadcastAction.ACTION.ABORT, otherAction);
        assertEquals(List.of("streaming-text:a0", "streaming-text:b0"), nextPoll(waiting));
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    @Test
    void withOutOfOrderBroadcastNoFrameKeptForAWaitingMemberIsAlsoWrittenToItsPoll() {
        params.put(ApplicationConfig.OUT_OF_ORDER_BROADCAST, "true");
        var unordered = factory.get(DefaultBroadcaster.class, "/atmosphere/unordered/" + UUID.randomUUID());
        try {
            LongPollingRoomReplies.install(unordered);
            var waiting = subscriber(unordered, AtmosphereResource.TRANSPORT.LONG_POLLING);
            var socket = subscriber(unordered, AtmosphereResource.TRANSPORT.WEBSOCKET);
            var a = StreamingSessions.startRoomBroadcast(socket);
            var b = StreamingSessions.startRoomBroadcast(socket);
            for (var i = 0; i < 20; i++) {
                a.send("a" + i + ".");
                b.send("b" + i + ".");
            }
            a.complete();
            b.complete();
            // Every frame, not both terminals: another thread may still be at an earlier frame. A frame is
            // written to a subscriber after the room's listener ran for it, on the same thread.
            awaitFrames(socket, 42);

            assertTrue(frames(waiting).isEmpty(), "every frame of the two replies is kept for the member, so none"
                    + " may also be written to its waiting poll: " + frames(waiting));
            var received = nextPollRaw(waiting);
            assertEquals(42, received.size(), "the next poll receives every frame: " + describe(received));
            assertEquals(42, Set.copyOf(received).size(), "and each of them once");
        } finally {
            unordered.destroy();
        }
        assertEquals(reservedBefore, LongPollingReplies.reservedBytes(), "every kept frame is released once taken");
    }

    private static String aiFrame(String sessionId, String data) {
        return "{\"type\":\"streaming-text\",\"data\":\"" + data + "\",\"sessionId\":\"" + sessionId
                + "\",\"seq\":1}";
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
