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

import org.atmosphere.ai.filter.AiStreamMessage;
import org.atmosphere.ai.filter.PiiRedactionFilter;
import org.atmosphere.cache.UUIDBroadcasterCache;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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

    @BeforeEach
    void setUp() {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/atmosphere/room/" + UUID.randomUUID());
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
    void withABroadcasterCacheTheRoomIsLeftToTheCache() throws Exception {
        var cache = new UUIDBroadcasterCache();
        cache.configure(config);
        room.getBroadcasterConfig().setBroadcasterCache(cache);
        var waiting = subscriber(AtmosphereResource.TRANSPORT.LONG_POLLING);
        var socket = subscriber(AtmosphereResource.TRANSPORT.WEBSOCKET);

        var session = StreamingSessions.startRoomBroadcast(socket);
        session.send("cached.");
        session.complete();
        awaitComplete(socket);

        assertEquals(List.of("streaming-text:cached."), frames(waiting),
                "with a cache, a waiting poll is written the first frame as before");
        assertTrue(nextPoll(waiting).isEmpty(), "nothing is kept in a mailbox: the cache hands the rest over");
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

    /** A subscriber of the room on {@code transport}; a long-polling one leaves the room once written to. */
    private AtmosphereResourceImpl subscriber(AtmosphereResource.TRANSPORT transport) {
        var r = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new Recorder());
        r.transport(transport);
        room.addAtmosphereResource(r);
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
                room.removeAtmosphereResource(resource);
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
        var poll = mock(AtmosphereResource.class);
        when(poll.uuid()).thenReturn(member.uuid());
        var frames = new ArrayList<String>();
        LongPollingReplies.deliver(poll, owner -> true, (p, delivered) -> frames.addAll(delivered));
        return describe(frames);
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
