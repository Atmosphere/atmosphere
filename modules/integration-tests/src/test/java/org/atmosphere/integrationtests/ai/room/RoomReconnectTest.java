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
import java.io.InputStream;
import java.io.OutputStream;
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

    private EmbeddedAtmosphereServer cachedServer;
    private EmbeddedAtmosphereServer uncachedServer;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        cachedServer = server("org.atmosphere.integrationtests.ai.room.cached");
        uncachedServer = server("org.atmosphere.integrationtests.ai.room.uncached");
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (var server : List.of(cachedServer, uncachedServer)) {
            // The framework initialises on the first request.
            httpClient.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        }
    }

    private static EmbeddedAtmosphereServer server(String annotationPackage) throws Exception {
        var server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage(annotationPackage)
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.ai.processor")
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
        var url = "ws://localhost:" + port + path
                + "?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0&X-atmo-protocol=true"
                + "&Content-Type=application/json&X-Atmosphere-tracking-id=" + trackingId
                + (runId != null ? "&X-Atmosphere-Run-Id=" + runId : "");
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
     * with no WebSocket close.
     */
    private static final class DroppingProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final int target;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

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
                    sockets.add(client);
                    sockets.add(upstream);
                    Thread.ofVirtual().start(() -> pump(client, upstream));
                    Thread.ofVirtual().start(() -> pump(upstream, client));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private static void pump(Socket from, Socket to) {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                var buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
                // The connection was dropped.
            }
        }

        /** Reset every connection through the proxy. */
        void drop() {
            for (var socket : sockets) {
                try {
                    socket.setSoLinger(true, 0);
                    socket.close();
                } catch (IOException e) {
                    // Already closed.
                }
            }
            sockets.clear();
        }

        @Override
        public void close() throws IOException {
            drop();
            listener.close();
        }
    }
}
