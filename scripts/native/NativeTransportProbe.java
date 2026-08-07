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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Drives real client traffic against a running Atmosphere sample and asserts
 * transport behaviour. Used by the Native Image CI lanes, where booting is not
 * the feature: a native binary can answer a liveness URL while annotation
 * discovery, codec reflection, broadcaster fan-out or the room protocol are
 * silently broken. Every check here fails loudly instead.
 *
 * Zero dependencies by design — runs in source-file mode on any JDK 21+:
 *
 *   java scripts/native/NativeTransportProbe.java http://localhost:8080/atmosphere/chat ws-fanout sse rooms
 *
 * Checks:
 *   ws-fanout  two WebSocket clients; a chat message sent by one must reach
 *              the other, proving the WS transport, the @Message
 *              decoder/encoder round-trip and broadcaster fan-out.
 *   sse        an SSE subscriber must receive a chat message sent over a
 *              separate WebSocket, proving SSE delivery and cross-transport
 *              broadcast.
 *   rooms      join/join_ack, presence fan-out and room broadcast through
 *              RoomProtocolInterceptor (requires a sample with rooms enabled).
 */
public final class NativeTransportProbe {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String ATMO_PARAMS =
            "X-Atmosphere-tracking-id=0&X-Atmosphere-Framework=2.3&X-Cache-Date=0";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String endpoint;

    private NativeTransportProbe(String endpoint) {
        this.endpoint = endpoint;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: NativeTransportProbe <endpoint-url> <check...>");
            System.err.println("checks: ws-fanout sse rooms");
            System.exit(2);
        }
        var probe = new NativeTransportProbe(args[0]);
        boolean ok = true;
        for (int i = 1; i < args.length; i++) {
            var check = args[i];
            var start = System.nanoTime();
            try {
                switch (check) {
                    case "ws-fanout" -> probe.wsFanout();
                    case "sse" -> probe.sse();
                    case "rooms" -> probe.rooms();
                    default -> throw new IllegalArgumentException("unknown check: " + check);
                }
                System.out.printf("PASS %s (%d ms)%n", check,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            } catch (Exception e) {
                ok = false;
                System.out.printf("FAIL %s: %s%n", check, e.getMessage());
            }
        }
        System.exit(ok ? 0 : 1);
    }

    /** Two clients: a message sent by B must arrive at A via the broadcaster. */
    private void wsFanout() throws Exception {
        var token = "ws-fanout-" + UUID.randomUUID();
        try (var a = WsClient.connect(http, wsUri()); var b = WsClient.connect(http, wsUri())) {
            b.send(chatJson("probe-b", token));
            a.await("fan-out to the second client", f -> f.contains(token));
        }
    }

    /** An SSE subscriber must see a message sent over a WebSocket. */
    private void sse() throws Exception {
        var token = "sse-" + UUID.randomUUID();
        var lines = new CopyOnWriteArrayList<String>();
        var request = HttpRequest.newBuilder(URI.create(endpoint
                        + "?" + ATMO_PARAMS + "&X-Atmosphere-Transport=sse"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .build();
        var response = http.sendAsync(request,
                HttpResponse.BodyHandlers.fromLineSubscriber(new LineCollector(lines)));
        // Give the server a moment to suspend the SSE connection before sending.
        Thread.sleep(2000);
        try (var sender = WsClient.connect(http, wsUri())) {
            sender.send(chatJson("probe-sse", token));
            awaitList(lines, "SSE frame containing the token", l -> l.contains(token));
        } finally {
            response.cancel(true);
        }
    }

    /** join/join_ack, presence fan-out, and a room broadcast through the protocol. */
    private void rooms() throws Exception {
        var token = "room-" + UUID.randomUUID();
        try (var d = WsClient.connect(http, wsUri()); var e = WsClient.connect(http, wsUri())) {
            d.send("{\"type\":\"join\",\"room\":\"lobby\",\"memberId\":\"probe-d\"}");
            d.await("join_ack for probe-d", f -> f.contains("join_ack"));
            e.send("{\"type\":\"join\",\"room\":\"lobby\",\"memberId\":\"probe-e\"}");
            e.await("join_ack for probe-e", f -> f.contains("join_ack"));
            d.await("presence fan-out announcing probe-e",
                    f -> f.contains("presence") && f.contains("probe-e"));
            d.send("{\"type\":\"broadcast\",\"room\":\"lobby\",\"data\":\"" + token + "\"}");
            e.await("room broadcast to the other member", f -> f.contains(token));
        }
    }

    private URI wsUri() {
        var ws = endpoint.replaceFirst("^http", "ws");
        return URI.create(ws + "?" + ATMO_PARAMS + "&X-Atmosphere-Transport=websocket");
    }

    private static String chatJson(String author, String message) {
        return "{\"author\":\"" + author + "\",\"message\":\"" + message + "\"}";
    }

    private static void awaitList(List<String> seen, String what, Predicate<String> match)
            throws InterruptedException, TimeoutException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (seen.stream().anyMatch(match)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new TimeoutException("timed out waiting for " + what
                + "; received so far: " + truncate(seen));
    }

    private static String truncate(List<String> frames) {
        var joined = String.join(" | ", frames);
        return joined.length() > 2000 ? joined.substring(0, 2000) + "…" : joined;
    }

    /** Minimal WebSocket client collecting complete text frames. */
    private static final class WsClient implements WebSocket.Listener, AutoCloseable {

        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        static WsClient connect(HttpClient http, URI uri) throws Exception {
            var client = new WsClient();
            client.socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, client)
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return client;
        }

        void send(String text) throws Exception {
            socket.sendText(text, true).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        void await(String what, Predicate<String> match)
                throws InterruptedException, TimeoutException {
            awaitList(frames, what, match);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(partial.toString());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
            partial.append(StandardCharsets.UTF_8.decode(data));
            if (last) {
                frames.add(partial.toString());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                socket.abort();
            }
        }
    }

    /** Streams response body lines into a shared list as they arrive. */
    private static final class LineCollector implements Flow.Subscriber<String> {

        private final List<String> sink;

        LineCollector(List<String> sink) {
            this.sink = sink;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            sink.add(line);
        }

        @Override
        public void onError(Throwable throwable) {
            sink.add("[stream error] " + throwable);
        }

        @Override
        public void onComplete() {
            sink.add("[stream complete]");
        }
    }
}
