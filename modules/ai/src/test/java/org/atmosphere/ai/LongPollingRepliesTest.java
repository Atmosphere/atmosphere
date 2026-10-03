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

import org.atmosphere.ai.filter.PiiRedactionFilter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An AI reply to a long-polling client reaches the client's next poll whole,
 * in order, and only that client; WebSocket keeps streaming frame by frame.
 */
class LongPollingRepliesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A long-polling connection of {@code uuid} on a broadcaster with {@code filters}. */
    private static AtmosphereResource longPolling(String uuid, AtmosphereConfig config,
                                                  List<BroadcastFilter> filters) {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        var broadcasterConfig = mock(BroadcasterConfig.class);
        when(broadcasterConfig.filters()).thenReturn(filters);
        when(broadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        when(broadcaster.getID()).thenReturn("/ai");
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        return resource;
    }

    private static AtmosphereResource longPolling(String uuid) {
        return longPolling(uuid, null, List.of());
    }

    /** The next poll of {@code uuid}: suspended, until its frames are written. */
    private static AtmosphereResource poll(String uuid) {
        var poll = mock(AtmosphereResource.class);
        when(poll.uuid()).thenReturn(uuid);
        when(poll.isSuspended()).thenReturn(true);
        when(poll.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        return poll;
    }

    private static String newId() {
        return "lp-" + UUID.randomUUID();
    }

    /** Takes what is parked for {@code uuid} through a suspended poll; empty when nothing is. */
    private static List<String> take(String uuid) {
        var written = new ArrayList<String>();
        LongPollingReplies.deliver(poll(uuid), owner -> true, (p, frames) -> written.addAll(frames));
        return written;
    }

    private static List<String> kinds(List<String> frames) {
        var kinds = new ArrayList<String>();
        for (var frame : frames) {
            var node = MAPPER.readTree(frame);
            kinds.add(node.has("event") ? node.get("event").asString() : node.get("type").asString());
        }
        return kinds;
    }

    private static List<String> texts(List<String> frames) {
        var texts = new ArrayList<String>();
        for (var frame : frames) {
            var node = MAPPER.readTree(frame);
            if ("streaming-text".equals(node.path("type").asString())) {
                texts.add(node.get("data").asString());
            }
        }
        return texts;
    }

    @Test
    void wholeReplyReachesTheNextPollInOrderAndNothingIsBroadcast() {
        var uuid = newId();
        var resource = longPolling(uuid);
        var session = new DefaultStreamingSession("s-" + uuid, resource);

        session.send("Hello");
        session.send(", ");
        session.progress("thinking");
        session.send("world");
        assertTrue(take(uuid).isEmpty(), "nothing is handed over before the reply ends");
        session.complete();

        verify(resource.getBroadcaster(), never()).broadcast(any(), anySet());
        verify(resource.getBroadcaster(), never()).broadcast(any());
        var frames = take(uuid);
        assertEquals(List.of("streaming-text", "streaming-text", "progress", "streaming-text", "complete"),
                kinds(frames));
        assertEquals(List.of("Hello", ", ", "world"), texts(frames));
        assertTrue(take(uuid).isEmpty(), "a reply is delivered once");
        assertFalse(LongPollingReplies.inFlight(uuid));
    }

    @Test
    void webSocketStillStreamsEveryFrame() {
        var uuid = newId();
        var resource = longPolling(uuid);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        var session = new DefaultStreamingSession("s-" + uuid, resource);

        session.send("a");
        session.send("b");
        session.complete();

        verify(resource.getBroadcaster(), times(3)).broadcast(any(), anySet());
        assertTrue(take(uuid).isEmpty());
        assertFalse(LongPollingReplies.inFlight(uuid));
    }

    @Test
    void approvalRequiredHandsTheReplySoFarOver() {
        var uuid = newId();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid));

        session.send("Let me book that.");
        session.emit(new AiEvent.ApprovalRequired("ap-1", "book", Map.of(), "Book it?", 60));

        assertEquals(List.of("streaming-text", "approval-required"), kinds(take(uuid)),
                "the run waits for the client's answer, so the frames so far cannot wait for the end");
        assertTrue(LongPollingReplies.inFlight(uuid));

        session.send("Booked.");
        session.complete();
        assertEquals(List.of("streaming-text", "complete"), kinds(take(uuid)));
    }

    @Test
    void framesParkedTwiceBeforeAPollAreDeliveredTogetherInOrder() {
        var uuid = newId();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid));

        session.send("one");
        session.emit(new AiEvent.ApprovalRequired("ap-2", "tool", Map.of(), "ok?", 60));
        session.send("two");
        session.complete();

        var frames = take(uuid);
        assertEquals(List.of("streaming-text", "approval-required", "streaming-text", "complete"), kinds(frames));
        assertEquals(List.of("one", "two"), texts(frames));
    }

    @Test
    void filtersRunOnTheBufferedFramesAndADeferredStreamEndFollowsTheFlushedText() {
        var uuid = newId();
        var session = new DefaultStreamingSession("s-" + uuid,
                longPolling(uuid, null, List.of(new PiiRedactionFilter())));

        // No sentence boundary: the PII filter holds the text back until the
        // terminal frame, then sends it in that frame's place and defers the
        // terminal frame past it.
        session.send("mail me at alice@example.com");
        session.complete();

        var frames = take(uuid);
        assertEquals(List.of("streaming-text", "complete"), kinds(frames));
        assertFalse(texts(frames).get(0).contains("alice@example.com"), "the filter chain must have redacted it");
    }

    @Test
    void framesWhoseFiltersCannotBeReadAreNeverDeliveredUnfiltered() {
        var uuid = newId();
        var resource = longPolling(uuid);
        when(resource.getBroadcaster()).thenThrow(new IllegalStateException("broadcaster gone"));
        var session = new DefaultStreamingSession("s-" + uuid, resource);

        session.send("my ssn is 123-45-6789");

        assertTrue(session.hasErrored(), "fail closed, as a streaming broadcast that throws does");
        var frames = take(uuid);
        assertEquals(List.of("error"), kinds(frames));
        assertFalse(frames.get(0).contains("123-45-6789"));
        assertTrue(frames.get(0).contains("could not be filtered"), "the client is told why, not a size error");
    }

    @Test
    void aReplyPastTheFrameBoundEndsWithAnErrorFrameAndReleasesItsBytes() {
        var uuid = newId();
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(LongPollingReplies.MAX_REPLY_FRAMES_PARAM)).thenReturn("3");
        var before = LongPollingReplies.reservedBytes();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid, config, List.of()));

        session.send("1");
        session.send("2");
        session.send("3");
        session.send("4");

        assertTrue(session.isClosed());
        assertTrue(session.hasErrored());
        assertEquals(before, LongPollingReplies.reservedBytes(), "the dropped frames' bytes are released");
        session.send("5");
        session.complete();
        var frames = take(uuid);
        assertEquals(List.of("error"), kinds(frames), "the client is told instead of left waiting");
        assertFalse(LongPollingReplies.inFlight(uuid));
        assertTrue(DefaultStreamingSession.resourceForSession("s-" + uuid).isEmpty());
    }

    @Test
    void aReplyPastTheByteBoundEndsWithAnErrorFrame() {
        var uuid = newId();
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(LongPollingReplies.MAX_REPLY_BYTES_PARAM)).thenReturn("200");
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid, config, List.of()));

        session.send("x".repeat(300));

        assertTrue(session.hasErrored());
        assertEquals(List.of("error"), kinds(take(uuid)));
    }

    @Test
    void aReplyPastTheJvmBudgetEndsWithAnErrorFrame() {
        var uuid = newId();
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(LongPollingReplies.MAX_BUFFERED_BYTES_PARAM))
                .thenReturn(String.valueOf(LongPollingReplies.reservedBytes() + 100));
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid, config, List.of()));

        session.send("y".repeat(150));

        assertTrue(session.hasErrored());
        assertEquals(List.of("error"), kinds(take(uuid)));
    }

    @Test
    void aPollOfAnotherIdentityIsNotHandedTheReply() {
        var uuid = newId();
        var resource = longPolling(uuid);
        when(resource.getRequest().getAttribute(LongPollingReplies.CONNECTION_OWNER_ATTRIBUTE)).thenReturn("alice");
        var session = new DefaultStreamingSession("s-" + uuid, resource);
        session.send("for alice");
        session.complete();

        var written = new ArrayList<String>();
        assertFalse(LongPollingReplies.deliver(poll(uuid), "mallory"::equals, (p, f) -> written.addAll(f)));
        assertTrue(written.isEmpty());
        assertTrue(LongPollingReplies.deliver(poll(uuid), "alice"::equals, (p, f) -> written.addAll(f)));
        assertEquals(List.of("for alice"), texts(written));
    }

    @Test
    void aPollAlreadyResumedLeavesTheReplyForTheNextOne() {
        var uuid = newId();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid));
        session.send("kept");
        session.complete();

        var resumed = poll(uuid);
        when(resumed.isSuspended()).thenReturn(false);
        when(resumed.isResumed()).thenReturn(true);
        assertFalse(LongPollingReplies.deliver(resumed, o -> true, (p, f) -> {
            throw new AssertionError("a resumed poll must not be written to");
        }));
        assertEquals(List.of("kept"), texts(take(uuid)));
    }

    @Test
    void aFailedWriteLeavesTheReplyParked() {
        var uuid = newId();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid));
        session.send("retry me");
        session.complete();

        assertFalse(LongPollingReplies.deliver(poll(uuid), o -> true, (p, f) -> {
            throw new IOException("client gone");
        }));
        assertEquals(List.of("retry me"), texts(take(uuid)));
    }

    @Test
    void handingOverResumesTheClientsWaitingPollEmpty() {
        var uuid = newId();
        var waiting = poll(uuid);
        var factory = mock(AtmosphereResourceFactory.class);
        when(factory.findResource(uuid)).thenReturn(Optional.of(waiting));
        var config = mock(AtmosphereConfig.class);
        when(config.resourcesFactory()).thenReturn(factory);
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid, config, List.of()));

        session.send("hi");
        verify(waiting, never()).resume();
        session.complete();

        verify(waiting).resume();
        verify(waiting, never()).write(any(String.class));
        assertEquals(List.of("hi"), texts(take(uuid)));
    }

    @Test
    void aHandOverNeverResumesAPollInTheMiddleOfItsWrite() throws Exception {
        var uuid = newId();
        var factory = mock(AtmosphereResourceFactory.class);
        var config = mock(AtmosphereConfig.class);
        when(config.resourcesFactory()).thenReturn(factory);
        var first = new DefaultStreamingSession("s1-" + uuid, longPolling(uuid, config, List.of()));
        first.send("first");
        first.complete();

        var writing = poll(uuid);
        when(factory.findResource(uuid)).thenReturn(Optional.of(writing));
        var inWrite = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resumedDuringWrite = new AtomicReference<Boolean>();
        var delivering = Thread.ofVirtual().start(() -> LongPollingReplies.deliver(writing, o -> true, (p, f) -> {
            inWrite.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            resumedDuringWrite.set(org.mockito.Mockito.mockingDetails(writing).getInvocations().stream()
                    .anyMatch(i -> i.getMethod().getName().equals("resume")));
        }));
        assertTrue(inWrite.await(5, TimeUnit.SECONDS));

        var second = new DefaultStreamingSession("s2-" + uuid, longPolling(uuid, config, List.of()));
        second.send("second");
        var completing = Thread.ofVirtual().start(second::complete);
        Thread.sleep(100);
        release.countDown();
        delivering.join(5_000);
        completing.join(5_000);

        assertEquals(Boolean.FALSE, resumedDuringWrite.get(), "the hand-over waited for the write to finish");
        assertEquals(List.of("second"), texts(take(uuid)), "the second reply waits for the next poll");
    }

    @Test
    void discardAndExpiryReleaseTheParkedBytes() throws Exception {
        var before = LongPollingReplies.reservedBytes();
        var gone = newId();
        var s1 = new DefaultStreamingSession("s-" + gone, longPolling(gone));
        s1.send("bye");
        s1.complete();
        assertTrue(LongPollingReplies.reservedBytes() > before);
        LongPollingReplies.discard(gone);
        assertEquals(before, LongPollingReplies.reservedBytes());
        assertTrue(take(gone).isEmpty());

        var idle = newId();
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(LongPollingReplies.PARKED_TTL_MS_PARAM)).thenReturn("1");
        var s2 = new DefaultStreamingSession("s-" + idle, longPolling(idle, config, List.of()));
        s2.send("nobody polls");
        s2.complete();
        Thread.sleep(5);
        assertTrue(LongPollingReplies.sweepExpired() >= 1);
        assertEquals(before, LongPollingReplies.reservedBytes());
        assertTrue(take(idle).isEmpty());
    }

    @Test
    void aSessionCleanedUpOrReapedMidReplyReleasesItsBuffer() {
        var before = LongPollingReplies.reservedBytes();
        var uuid = newId();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid));
        session.send("half a reply");
        assertTrue(LongPollingReplies.inFlight(uuid));

        DefaultStreamingSession.cleanupResource(uuid);

        assertFalse(LongPollingReplies.inFlight(uuid));
        assertEquals(before, LongPollingReplies.reservedBytes());
        session.send("late");
        assertEquals(before, LongPollingReplies.reservedBytes(), "a late frame is not buffered");

        var reaped = newId();
        var idle = new DefaultStreamingSession("s-" + reaped, longPolling(reaped));
        idle.send("stuck");
        idle.lastActivityMillis = 0;
        DefaultStreamingSession.sweepExpired(1);
        assertFalse(LongPollingReplies.inFlight(reaped));
        assertEquals(before, LongPollingReplies.reservedBytes());
    }

    @Test
    void parkedRepliesAreBounded() {
        var config = mock(AtmosphereConfig.class);
        var limit = LongPollingReplies.parkedReplies() + 2;
        when(config.getInitParameter(LongPollingReplies.MAX_PARKED_REPLIES_PARAM)).thenReturn(String.valueOf(limit));
        var ids = List.of(newId(), newId(), newId());
        for (var id : ids) {
            var s = new DefaultStreamingSession("s-" + id, longPolling(id, config, List.of()));
            s.send("r");
            s.complete();
        }
        assertEquals(limit, LongPollingReplies.parkedReplies());
        assertTrue(take(ids.get(2)).isEmpty(), "past the bound a reply is dropped, not parked");
        ids.forEach(LongPollingReplies::discard);
    }
}
