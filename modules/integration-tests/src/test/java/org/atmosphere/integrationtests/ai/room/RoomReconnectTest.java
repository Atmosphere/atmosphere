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
package org.atmosphere.integrationtests.ai.room;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.atmosphere.integrationtests.ai.room.cached.CachedRoomTestEndpoint;
import org.atmosphere.integrationtests.ai.room.uncached.OwnReplyTestEndpoint;
import org.atmosphere.integrationtests.ai.room.uncached.UncachedRoomTestEndpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A member of an {@code @AiEndpoint(broadcastReply = true)} room whose
 * connection is lost, and that reconnects as atmosphere.js does (same
 * tracking id, the run id of a turn it saw start and not end), is written
 * every frame of the room's turns it missed, each once, with one terminal
 * frame per turn: a whole turn asked while it was away, the rest of the turn
 * it dropped in, and both. The member's drop is a lost TCP connection, not a
 * close, as a phone put in the background loses it.
 *
 * <p>The room with a broadcaster cache delivers the missed frames from the
 * cache and the run's replay writes none of them again; the member sat in
 * the room past the cache's client idle time before it dropped, which a cache
 * that remembers a client only from its connect forgot. The room without a
 * cache cannot deliver a whole missed turn; the replay of the turn the member
 * dropped in writes it the frames under the room session's id and numbering,
 * so the frames it already had are the same {@code (sessionId, seq)} it holds.</p>
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RoomReconnectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern HANDSHAKE = Pattern.compile("^([0-9a-fA-F-]{36})\\|");
    private static final String RUN_ID_KEY = "X-Atmosphere-Run-Id";
    /** Seconds the cache keeps a client it was not given a connection of. */
    private static final int CLIENT_IDLE_SECONDS = 4;
    /** How long a member stays in the room before its connection drops: past the idle time. */
    private static final long SEATED_MS = 5_500;

    /** The most frames the cache of {@link #overflowServer} keeps per client: fewer than a turn has. */
    private static final int SMALL_CACHE = 5;

    private EmbeddedAtmosphereServer cachedServer;
    private EmbeddedAtmosphereServer uncachedServer;
    private EmbeddedAtmosphereServer overflowServer;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        cachedServer = server("org.atmosphere.integrationtests.ai.room.cached", 1000);
        uncachedServer = server("org.atmosphere.integrationtests.ai.room.uncached", 1000);
        overflowServer = server("org.atmosphere.integrationtests.ai.room.cached", SMALL_CACHE);
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (var server : List.of(cachedServer, uncachedServer, overflowServer)) {
            // The framework initialises on the first request.
            httpClient.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        }
    }

    private static EmbeddedAtmosphereServer server(String annotationPackage, int maxPerClient) throws Exception {
        var server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage(annotationPackage)
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.ai.processor")
                .withInitParam("org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient", String.valueOf(maxPerClient))
                .withInitParam("org.atmosphere.cache.UUIDBroadcasterCache.clientIdleTime",
                        String.valueOf(CLIENT_IDLE_SECONDS))
                .withInitParam("org.atmosphere.cache.UUIDBroadcasterCache.invalidateCacheInterval", "1");
        server.start();
        return server;
    }

    @AfterAll
    public void tearDown() throws Exception {
        httpClient.shutdownNow();
        cachedServer.close();
        uncachedServer.close();
        overflowServer.close();
    }

    @BeforeEach
    public void reset() {
        RoomTurns.RELEASE.countDown();
        RoomTurns.RELEASE = new java.util.concurrent.CountDownLatch(1);
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void aMemberAwayForAWholeTurnReceivesItOnceOnReconnect() throws Exception {
        try (var proxy = new DroppingProxy(cachedServer.getPort())) {
            var member = connect(proxy.port(), CachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(cachedServer.getPort(), CachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(SEATED_MS);

            proxy.drop();
            Thread.sleep(300);
            classmate.send("turn:away");
            classmate.await(frames -> count(frames, "complete") == 1, "the classmate's turn must end");

            var back = connect(proxy.port(), CachedRoomTestEndpoint.PATH, member.trackingId(), member.heldRunId());
            back.await(frames -> count(frames, "complete") == 1, "the reconnected member must receive the turn");
            Thread.sleep(500);

            assertExactlyOnce(classmate.runFrames(), member.runFrames(), back.runFrames());
            assertEquals(1, count(back.runFrames(), "complete"), "one terminal frame: " + back.describe());
            back.close();
            classmate.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void aMemberDroppedMidAnswerReceivesTheRestOnceOnReconnect() throws Exception {
        try (var proxy = new DroppingProxy(cachedServer.getPort())) {
            var member = connect(proxy.port(), CachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(cachedServer.getPort(), CachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(SEATED_MS);

            classmate.send("hold:mid");
            member.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the member must see the start of the answer");
            proxy.drop();
            Thread.sleep(300);
            RoomTurns.RELEASE.countDown();
            classmate.await(frames -> count(frames, "complete") == 1, "the classmate's turn must end");

            var runId = member.heldRunId();
            assertTrue(runId != null, "the member holds the run id of the turn it dropped in");
            var back = connect(proxy.port(), CachedRoomTestEndpoint.PATH, member.trackingId(), runId);
            back.await(frames -> count(frames, "complete") == 1, "the reconnected member must receive the rest");
            Thread.sleep(500);

            assertExactlyOnce(classmate.runFrames(), member.runFrames(), back.runFrames());
            assertEquals(1, count(back.runFrames(), "complete"), "one terminal frame: " + back.describe());
            back.close();
            classmate.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void aMemberDroppedMidAnswerThatMissedTheNextTurnReceivesBothOnce() throws Exception {
        try (var proxy = new DroppingProxy(cachedServer.getPort())) {
            var member = connect(proxy.port(), CachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(cachedServer.getPort(), CachedRoomTestEndpoint.PATH, "0", null);
            var other = connect(cachedServer.getPort(), CachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(SEATED_MS);

            classmate.send("hold:first");
            member.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the member must see the start of the first answer");
            proxy.drop();
            Thread.sleep(300);
            RoomTurns.RELEASE.countDown();
            classmate.await(frames -> count(frames, "complete") == 1, "the first turn must end");
            other.send("turn:second");
            other.await(frames -> count(frames, "complete") == 2, "the second turn must end");

            var back = connect(proxy.port(), CachedRoomTestEndpoint.PATH, member.trackingId(), member.heldRunId());
            back.await(frames -> count(frames, "complete") == 2, "the reconnected member must receive both turns");
            Thread.sleep(500);

            assertExactlyOnce(other.runFrames(), member.runFrames(), back.runFrames());
            assertEquals(2, count(back.runFrames(), "complete"), "one terminal frame per turn: " + back.describe());
            back.close();
            classmate.close();
            other.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void withoutACacheTheReplayOfTheTurnAMemberDroppedInKeepsTheRoomsNumbering() throws Exception {
        try (var proxy = new DroppingProxy(uncachedServer.getPort())) {
            var member = connect(proxy.port(), UncachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(uncachedServer.getPort(), UncachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(300);

            classmate.send("hold:replayed");
            member.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the member must see the start of the answer");
            proxy.drop();
            Thread.sleep(300);
            RoomTurns.RELEASE.countDown();
            classmate.await(frames -> count(frames, "complete") == 1, "the classmate's turn must end");

            var back = connect(proxy.port(), UncachedRoomTestEndpoint.PATH, member.trackingId(), member.heldRunId());
            back.await(frames -> count(frames, "complete") == 1, "the replay must end the turn");
            Thread.sleep(500);

            var sent = keys(classmate.runFrames());
            var foreign = back.runFrames().stream().filter(f -> !sent.contains(key(f))).map(RoomReconnectTest::describe)
                    .toList();
            assertEquals(List.of(), foreign, "every replayed frame is a frame the room sent, by (sessionId, seq)");
            var held = keys(member.runFrames());
            held.addAll(keys(back.runFrames()));
            assertEquals(textAndTerminal(classmate.runFrames()), textAndTerminal(held, classmate.runFrames()),
                    "the member holds every text and terminal frame of the turn");
            back.close();
            classmate.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void anAskerDroppedMidAnswerReceivesTheRestOfItsOwnReplyInItsSessionOnce() throws Exception {
        try (var proxy = new DroppingProxy(uncachedServer.getPort())) {
            var asker = connect(proxy.port(), OwnReplyTestEndpoint.PATH, "0", null);
            asker.send("hold:mine");
            asker.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the asker must see the start of its answer");
            var runId = asker.heldRunId();
            assertTrue(runId != null, "the asker holds the run id of its turn");
            proxy.drop();
            Thread.sleep(300);
            RoomTurns.RELEASE.countDown();
            // The run ends on the server meanwhile, into its replay.
            Thread.sleep(700);

            var back = connect(proxy.port(), OwnReplyTestEndpoint.PATH, asker.trackingId(), runId);
            back.await(frames -> count(frames, "complete") == 1, "the reconnected asker must receive the end");
            Thread.sleep(500);

            assertOwnReplyResumed(asker.runFrames(), back.runFrames(), "mine", false);
            back.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void aMemberWhoseDropTheServerDidNotNoticeReceivesTheTurnItMissedOnceFromItsResumePoint()
            throws Exception {
        try (var proxy = new DroppingProxy(uncachedServer.getPort())) {
            var member = connect(proxy.port(), UncachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(uncachedServer.getPort(), UncachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(300);
            classmate.send("turn:seen");
            member.await(frames -> count(frames, "complete") == 1, "the member must see the first turn");

            // The member's connection is gone; the server writes on into it.
            proxy.blackhole();
            classmate.send("turn:unseen");
            classmate.await(frames -> count(frames, "complete") == 2, "the second turn must end");

            var back = connect(proxy.port(), UncachedRoomTestEndpoint.PATH, member.trackingId(),
                    member.heldRunId(), member.resumePoint());
            back.await(frames -> count(frames, "complete") == 1, "the reconnected member must receive the turn");
            Thread.sleep(500);

            assertRunFramesOnce(classmate.runFrames(), member.runFrames(), back.runFrames());
            back.close();
            classmate.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void aMemberDroppedUnnoticedMidAnswerReceivesTheRestOnceFromItsResumePoint() throws Exception {
        try (var proxy = new DroppingProxy(uncachedServer.getPort())) {
            var member = connect(proxy.port(), UncachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(uncachedServer.getPort(), UncachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(300);

            classmate.send("hold:unnoticed");
            member.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the member must see the start of the answer");
            proxy.blackhole();
            RoomTurns.RELEASE.countDown();
            classmate.await(frames -> count(frames, "complete") == 1, "the classmate's turn must end");
            var other = connect(uncachedServer.getPort(), UncachedRoomTestEndpoint.PATH, "0", null);
            other.send("turn:after");
            other.await(frames -> count(frames, "complete") == 1, "the next turn must end");

            var back = connect(proxy.port(), UncachedRoomTestEndpoint.PATH, member.trackingId(),
                    member.heldRunId(), member.resumePoint());
            back.await(frames -> count(frames, "complete") == 2, "the reconnected member must receive both ends");
            Thread.sleep(500);

            assertRunFramesOnce(classmate.runFrames(), member.runFrames(), back.runFrames());
            back.close();
            classmate.close();
            other.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void theCacheAndTheResumePointTogetherWriteEachMissedFrameOnce() throws Exception {
        try (var proxy = new DroppingProxy(cachedServer.getPort())) {
            var member = connect(proxy.port(), CachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(cachedServer.getPort(), CachedRoomTestEndpoint.PATH, "0", null);
            var other = connect(cachedServer.getPort(), CachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(SEATED_MS);

            classmate.send("hold:first");
            member.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the member must see the start of the first answer");
            proxy.drop();
            Thread.sleep(300);
            RoomTurns.RELEASE.countDown();
            classmate.await(frames -> count(frames, "complete") == 1, "the first turn must end");
            other.send("turn:second");
            other.await(frames -> count(frames, "complete") == 2, "the second turn must end");

            var back = connect(proxy.port(), CachedRoomTestEndpoint.PATH, member.trackingId(),
                    member.heldRunId(), member.resumePoint());
            back.await(frames -> count(frames, "complete") == 2, "the reconnected member must receive both turns");
            Thread.sleep(500);

            assertExactlyOnce(other.runFrames(), member.runFrames(), back.runFrames());
            assertEquals(2, count(back.runFrames(), "complete"), "one terminal frame per turn: " + back.describe());
            back.close();
            classmate.close();
            other.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void aMemberWhoseMissedFramesOverflowedTheCacheReceivesThemAllOnceFromItsResumePoint()
            throws Exception {
        try (var proxy = new DroppingProxy(overflowServer.getPort())) {
            var member = connect(proxy.port(), CachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(overflowServer.getPort(), CachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(SEATED_MS);
            classmate.send("turn:before");
            member.await(frames -> count(frames, "complete") == 1, "the member must see the first turn");

            proxy.drop();
            Thread.sleep(300);
            classmate.send("turn:overflowing");
            classmate.await(frames -> count(frames, "complete") == 2, "the second turn must end");

            var back = connect(proxy.port(), CachedRoomTestEndpoint.PATH, member.trackingId(),
                    member.heldRunId(), member.resumePoint());
            back.await(frames -> count(frames, "complete") == 1, "the reconnected member must receive the turn");
            Thread.sleep(500);

            assertRunFramesOnce(classmate.runFrames(), member.runFrames(), back.runFrames());
            back.close();
            classmate.close();
        }
    }

    /**
     * The prompt, text and terminal frames of the room's turns ({@code sent})
     * reached the member once each over the two connections, those after the
     * reconnect in the order the room sent them; and nothing written after the
     * reconnect repeats a frame it had, nor itself.
     */
    private static void assertRunFramesOnce(List<JsonNode> sent, List<JsonNode> before, List<JsonNode> after) {
        var expected = sent.stream().filter(RoomReconnectTest::runFrame).map(RoomReconnectTest::key)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        var counts = new LinkedHashMap<String, Integer>();
        for (var frame : before) {
            counts.merge(key(frame), 1, Integer::sum);
        }
        for (var frame : after) {
            counts.merge(key(frame), 1, Integer::sum);
        }
        var sentKeys = keys(sent);
        assertEquals(List.of(), expected.stream().filter(k -> !counts.containsKey(k)).toList(),
                "no prompt, text or terminal frame of the room's turns is missed");
        assertEquals(List.of(), counts.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey)
                .toList(), "no frame is written to the member twice");
        assertEquals(List.of(), counts.keySet().stream().filter(k -> !sentKeys.contains(k)).toList(),
                "every frame the member got is one the room sent, by (sessionId, seq)");
        var order = new ArrayList<String>(expected);
        var afterOrder = after.stream().filter(RoomReconnectTest::runFrame).map(RoomReconnectTest::key).toList();
        assertEquals(order.stream().filter(afterOrder::contains).toList(), afterOrder,
                "the frames after the reconnect come in the order the room sent them");
    }

    private static boolean runFrame(JsonNode frame) {
        var type = frame.path("type").asString();
        return "prompt".equals(type) || "streaming-text".equals(type) || "complete".equals(type);
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void anAskerDroppedUnnoticedMidAnswerReceivesTheRestOfItsOwnReplyOnceFromItsResumePoint()
            throws Exception {
        try (var proxy = new DroppingProxy(uncachedServer.getPort())) {
            var asker = connect(proxy.port(), OwnReplyTestEndpoint.PATH, "0", null);
            asker.send("hold:unnoticed");
            asker.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the asker must see the start of its answer");
            var runId = asker.heldRunId();
            assertTrue(runId != null, "the asker holds the run id of its turn");
            // Gone, but the server writes the rest of the answer into the void.
            proxy.blackhole();
            RoomTurns.RELEASE.countDown();
            Thread.sleep(700);

            var back = connect(proxy.port(), OwnReplyTestEndpoint.PATH, asker.trackingId(), runId,
                    asker.resumePoint());
            back.await(frames -> count(frames, "complete") == 1, "the reconnected asker must receive the end");
            Thread.sleep(500);

            assertOwnReplyResumed(asker.runFrames(), back.runFrames(), "unnoticed", true);
            back.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void anAskerBackFromAnUnnoticedDropWhileItsAnswerStreamsReceivesTheRestOnce() throws Exception {
        try (var proxy = new DroppingProxy(uncachedServer.getPort())) {
            var asker = connect(proxy.port(), OwnReplyTestEndpoint.PATH, "0", null);
            asker.send("hold:inflight");
            asker.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the asker must see the start of its answer");
            var runId = asker.heldRunId();
            proxy.blackhole();

            // Back while the answer is still being written: the rest streams live.
            var back = connect(proxy.port(), OwnReplyTestEndpoint.PATH, asker.trackingId(), runId,
                    asker.resumePoint());
            RoomTurns.RELEASE.countDown();
            back.await(frames -> count(frames, "complete") == 1, "the reconnected asker must receive the end");
            Thread.sleep(500);

            assertOwnReplyResumed(asker.runFrames(), back.runFrames(), "inflight", true);
            back.close();
        }
    }

    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Test
    public void aMemberBackFromAnUnnoticedDropWhileATurnStreamsReceivesTheRestOnce() throws Exception {
        try (var proxy = new DroppingProxy(uncachedServer.getPort())) {
            var member = connect(proxy.port(), UncachedRoomTestEndpoint.PATH, "0", null);
            var classmate = connect(uncachedServer.getPort(), UncachedRoomTestEndpoint.PATH, "0", null);
            Thread.sleep(300);
            classmate.send("hold:live");
            member.await(frames -> count(frames, "streaming-text") == RoomTurns.HELD_AFTER,
                    "the member must see the start of the answer");
            proxy.blackhole();

            var back = connect(proxy.port(), UncachedRoomTestEndpoint.PATH, member.trackingId(),
                    member.heldRunId(), member.resumePoint());
            RoomTurns.RELEASE.countDown();
            classmate.await(frames -> count(frames, "complete") == 1, "the classmate's turn must end");
            back.await(frames -> count(frames, "complete") == 1, "the reconnected member must receive the end");
            Thread.sleep(500);

            assertRunFramesOnce(classmate.runFrames(), member.runFrames(), back.runFrames());
            back.close();
            classmate.close();
        }
    }

    /**
     * The frames {@code before} the drop and {@code after} the reconnect hold
     * every text of the turn {@code name}, in order, and one terminal frame,
     * once each by {@code (sessionId, seq)}: what was written after the
     * reconnect continues the session the client followed, under its id and
     * numbering, a frame it already had being the same {@code (sessionId, seq)}
     * with the same text, which a client drops. With {@code exactlyOnce}, no
     * frame it had is written again.
     */
    private static void assertOwnReplyResumed(List<JsonNode> before, List<JsonNode> after, String name,
                                              boolean exactlyOnce) {
        var session = before.stream().filter(f -> "streaming-text".equals(f.path("type").asString()))
                .map(f -> f.path("sessionId").asString()).findFirst().orElseThrow();
        var resumed = after.stream().filter(RoomReconnectTest::textOrTerminal).toList();
        assertEquals(List.of(), resumed.stream().filter(f -> !session.equals(f.path("sessionId").asString()))
                        .map(RoomReconnectTest::describe).toList(),
                "after the reconnect, the turn continues in session " + session);
        var held = new java.util.TreeMap<Long, JsonNode>();
        for (var frame : before) {
            if (session.equals(frame.path("sessionId").asString())) {
                held.put(frame.path("seq").asLong(), frame);
            }
        }
        var lastSeq = held.lastKey();
        for (var frame : resumed) {
            var had = held.putIfAbsent(frame.path("seq").asLong(), frame);
            if (had != null) {
                assertEquals(had, frame, "a frame written again is the frame the client had");
            }
        }
        if (exactlyOnce) {
            assertEquals(List.of(), resumed.stream().filter(f -> f.path("seq").asLong() <= lastSeq)
                    .map(RoomReconnectTest::describe).toList(), "no frame the client had is written again");
        }
        var texts = held.values().stream().filter(f -> "streaming-text".equals(f.path("type").asString()))
                .map(f -> f.path("data").asString()).toList();
        var expected = new ArrayList<String>();
        for (var i = 0; i < RoomTurns.CHUNKS; i++) {
            expected.add(name + "-" + i + " ");
        }
        assertEquals(expected, texts, "every text of the turn once, in order");
        assertEquals(1, held.values().stream().filter(f -> "complete".equals(f.path("type").asString())).count(),
                "one terminal frame");
    }

    /**
     * What the member received before it dropped and after it reconnected is
     * every frame {@code sent} to the room (but progress, which a cache does
     * not keep), each once on the wire: nothing written after the reconnect
     * repeats a frame it had, nor itself.
     */
    private static void assertExactlyOnce(List<JsonNode> sent, List<JsonNode> before, List<JsonNode> after) {
        var expected = sent.stream().filter(f -> !"progress".equals(f.path("type").asString()))
                .map(RoomReconnectTest::key).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        var counts = new LinkedHashMap<String, Integer>();
        for (var frame : before) {
            counts.merge(key(frame), 1, Integer::sum);
        }
        for (var frame : after) {
            counts.merge(key(frame), 1, Integer::sum);
        }
        var missing = expected.stream().filter(k -> !counts.containsKey(k)).toList();
        var repeated = counts.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        var foreign = counts.keySet().stream().filter(k -> !expected.contains(k)).toList();
        assertEquals(List.of(), missing, "no frame of the room's turns is missed");
        assertEquals(List.of(), repeated, "no frame is written to the member twice");
        assertEquals(List.of(), foreign, "every frame the member got is one the room sent, by (sessionId, seq)");
    }

    private static Set<String> keys(List<JsonNode> frames) {
        return frames.stream().map(RoomReconnectTest::key).collect(Collectors.toCollection(java.util.HashSet::new));
    }

    private static List<String> textAndTerminal(List<JsonNode> frames) {
        return frames.stream().filter(RoomReconnectTest::textOrTerminal).map(RoomReconnectTest::key).toList();
    }

    private static List<String> textAndTerminal(Set<String> held, List<JsonNode> sent) {
        return sent.stream().filter(RoomReconnectTest::textOrTerminal).map(RoomReconnectTest::key)
                .filter(held::contains).toList();
    }

    private static boolean textOrTerminal(JsonNode frame) {
        var type = frame.path("type").asString();
        return "streaming-text".equals(type) || "complete".equals(type);
    }

    private static String key(JsonNode frame) {
        return frame.path("sessionId").asString() + "#" + frame.path("seq").asLong();
    }

    private static String describe(JsonNode frame) {
        return frame.path("type").asString() + ":" + key(frame);
    }

    private static long count(List<JsonNode> frames, String type) {
        return frames.stream().filter(f -> type.equals(f.path("type").asString())).count();
    }

    private Member connect(int port, String path, String trackingId, String runId) throws Exception {
        return connect(port, path, trackingId, runId, null);
    }

    /** Connect as atmosphere.js reconnects: with the run id it holds and its resume point, when given. */
    private Member connect(int port, String path, String trackingId, String runId, String resume) throws Exception {
        var url = "ws://localhost:" + port + path
                + "?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0&X-atmo-protocol=true"
                + "&Content-Type=application/json&X-Atmosphere-tracking-id=" + trackingId
                + (runId != null ? "&X-Atmosphere-Run-Id=" + runId : "")
                + (resume != null ? "&X-Atmosphere-Resume="
                        + java.net.URLEncoder.encode(resume, java.nio.charset.StandardCharsets.UTF_8) : "");
        var member = new Member(trackingId);
        var socket = httpClient.newWebSocketBuilder().buildAsync(URI.create(url), member).get(10, TimeUnit.SECONDS);
        member.socket = socket;
        if ("0".equals(trackingId)) {
            member.assigned.get(10, TimeUnit.SECONDS);
        } else {
            // A reconnect under its tracking id gets no handshake.
            Thread.sleep(300);
        }
        return member;
    }

    /** One room member's WebSocket, recording every AI frame written to it. */
    private static final class Member implements WebSocket.Listener {
        private final CompletableFuture<String> assigned = new CompletableFuture<>();
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private volatile String trackingId;
        private volatile WebSocket socket;

        Member(String trackingId) {
            this.trackingId = trackingId;
            if (!"0".equals(trackingId)) {
                assigned.complete(trackingId);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                var message = partial.toString();
                partial.setLength(0);
                var handshake = HANDSHAKE.matcher(message);
                if (!assigned.isDone() && handshake.find()) {
                    trackingId = handshake.group(1);
                    assigned.complete(trackingId);
                } else {
                    for (var json : jsonObjects(message)) {
                        try {
                            var node = MAPPER.readTree(json);
                            if (node.hasNonNull("sessionId") && node.has("seq")) {
                                frames.add(node);
                            }
                        } catch (RuntimeException e) {
                            // Not a frame of the streaming protocol.
                        }
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        String trackingId() {
            return trackingId;
        }

        void send(String prompt) {
            socket.sendText(prompt, true).join();
        }

        List<JsonNode> runFrames() {
            return List.copyOf(frames);
        }

        /** The run id atmosphere.js would send: the last one named, unless a terminal frame followed. */
        String heldRunId() {
            String runId = null;
            for (var frame : frames) {
                var type = frame.path("type").asString();
                if ("metadata".equals(type) && RUN_ID_KEY.equals(frame.path("key").asString())) {
                    runId = frame.path("value").asString();
                } else if ("complete".equals(type) || "error".equals(type) || "cancelled".equals(type)) {
                    runId = null;
                }
            }
            return runId;
        }

        String describe() {
            return frames.stream().map(RoomReconnectTest::describe).collect(Collectors.joining(" "));
        }

        /**
         * The resume point atmosphere.js sends: the last {@code seq} received
         * of each session, the most recently received last, up to 16.
         */
        String resumePoint() {
            var last = new LinkedHashMap<String, Long>();
            for (var frame : frames) {
                var session = frame.path("sessionId").asString();
                last.remove(session);
                last.put(session, frame.path("seq").asLong());
            }
            return last.entrySet().stream().skip(Math.max(0, last.size() - 16))
                    .map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(","));
        }

        void await(Predicate<List<JsonNode>> condition, String message) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                if (condition.test(runFrames())) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError(message + ": " + describe());
        }

        void close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                socket.abort();
            }
        }
    }

    /** The JSON objects in {@code message}, skipping what is between them (length prefixes, padding). */
    static List<String> jsonObjects(String message) {
        var out = new ArrayList<String>();
        var depth = 0;
        var start = -1;
        var inString = false;
        for (var i = 0; i < message.length(); i++) {
            var c = message.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"' && depth > 0) {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) {
                    start = i;
                }
            } else if (c == '}' && depth > 0 && --depth == 0) {
                out.add(message.substring(start, i + 1));
            }
        }
        return out;
    }

    /**
     * A TCP proxy in front of the server whose {@link #drop} resets every
     * connection through it: the server sees the member's connection lost,
     * with no WebSocket close. Its {@link #blackhole} resets the client side
     * only and keeps the server side open, reading and discarding what the
     * server writes: a connection lost as a phone loses it, whose loss the
     * server does not notice, its writes succeeding into the void.
     */
    private static final class DroppingProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final int target;
        private final List<Link> links = new CopyOnWriteArrayList<>();

        /** One client connection and the server connection it is pumped to. */
        private static final class Link {
            private final Socket client;
            private final Socket upstream;
            private volatile boolean blackholed;

            Link(Socket client, Socket upstream) {
                this.client = client;
                this.upstream = upstream;
            }
        }

        DroppingProxy(int target) throws IOException {
            this.target = target;
            this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(this::accept);
        }

        int port() {
            return listener.getLocalPort();
        }

        private void accept() {
            while (!listener.isClosed()) {
                try {
                    var client = listener.accept();
                    var upstream = new Socket(InetAddress.getLoopbackAddress(), target);
                    var link = new Link(client, upstream);
                    links.add(link);
                    Thread.ofVirtual().start(() -> pumpUp(link));
                    Thread.ofVirtual().start(() -> pumpDown(link));
                } catch (IOException e) {
                    return;
                }
            }
        }

        /** Client to server; a blackholed link keeps the server side open. */
        private static void pumpUp(Link link) {
            try {
                var in = link.client.getInputStream();
                var out = link.upstream.getOutputStream();
                var buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1 && !link.blackholed) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
                // The connection was dropped.
            }
            if (!link.blackholed) {
                reset(link.upstream);
            }
        }

        /** Server to client; a blackholed link reads what the server writes and discards it. */
        private static void pumpDown(Link link) {
            try {
                var in = link.upstream.getInputStream();
                var buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (link.blackholed) {
                        continue;
                    }
                    try {
                        var out = link.client.getOutputStream();
                        out.write(buffer, 0, read);
                        out.flush();
                    } catch (IOException e) {
                        if (!link.blackholed) {
                            throw e;
                        }
                    }
                }
            } catch (IOException e) {
                // The connection was dropped.
            }
            reset(link.client);
        }

        private static void reset(Socket socket) {
            try {
                socket.setSoLinger(true, 0);
                socket.close();
            } catch (IOException e) {
                // Already closed.
            }
        }

        /** Reset every connection through the proxy. */
        void drop() {
            for (var link : links) {
                reset(link.client);
                reset(link.upstream);
            }
            links.clear();
        }

        /**
         * Reset the client side of every connection through the proxy, keeping
         * the server side open and drained: the server goes on writing to it.
         */
        void blackhole() {
            for (var link : links) {
                link.blackholed = true;
                reset(link.client);
            }
        }

        @Override
        public void close() throws IOException {
            drop();
            listener.close();
        }
    }
}
