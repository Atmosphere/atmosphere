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
package org.atmosphere.ai.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A model provider on a loopback socket that stops sending, for the stream
 * idle contract of {@link AbstractAgentRuntimeContractTest}. It answers every
 * model call (a {@code POST}) with the {@link Mode} it was built with, in a
 * provider's streaming {@link Dialect}, and tells whether the client let the connection go:
 * {@link #clientClosed(Duration)} is {@code true} once a read on the server
 * side of a connection sees the end of the stream or fails.
 *
 * <p>Public for runtime authors outside this repository: a third-party runtime
 * points its model at {@link #baseUrl()} in
 * {@link AbstractAgentRuntimeContractTest#createStalledStreamFixture(StallingHttpServer)}.</p>
 */
public final class StallingHttpServer implements AutoCloseable {

    /** A provider's streaming wire format. */
    public enum Dialect {
        /** OpenAI Chat Completions SSE ({@code data:} chunks, {@code [DONE]}). */
        OPENAI,
        /** Anthropic Messages SSE ({@code event:} + {@code data:}, {@code ping} keep-alives). */
        ANTHROPIC,
        /** Cohere v2 chat SSE ({@code content-delta}, {@code message-end}). */
        COHERE,
        /** The CrewAI sidecar's SSE ({@code token}, {@code done}). */
        CREWAI,
        /** Gemini {@code streamGenerateContent?alt=sse} chunks. */
        GEMINI
    }

    /** What the server does after it read a request. */
    public enum Kind {
        /** Reads the request and never answers. */
        STALL_BEFORE_HEADERS,
        /** Sends the response headers, then nothing. */
        STALL_AFTER_HEADERS,
        /** Sends the headers and {@code chunks} text chunks, then nothing. */
        STALL_AFTER_CHUNKS,
        /** Sends only keep-alives every {@code interval} for {@code total}, then a text answer. */
        KEEPALIVE_EVERY,
        /** Sends one text chunk every {@code interval} for {@code total}, then ends the answer. */
        SLOW_BUT_LIVE,
        /** Answers the first request with a call of {@code toolName}, every later one with text. */
        TOOL_CALL_THEN_TEXT,
        /** Sends a complete text answer at once. */
        ANSWER
    }

    /**
     * How the server answers.
     *
     * @param kind     the behaviour
     * @param dialect  the wire format of what it sends
     * @param chunks   text chunks before a stall ({@link Kind#STALL_AFTER_CHUNKS})
     * @param interval time between keep-alives or chunks
     * @param total    how long keep-alives or slow chunks go on
     * @param toolName the tool the first answer calls ({@link Kind#TOOL_CALL_THEN_TEXT})
     */
    public record Mode(Kind kind, Dialect dialect, int chunks, Duration interval, Duration total,
                       String toolName) {

        public Mode {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(dialect, "dialect");
            interval = interval == null ? Duration.ZERO : interval;
            total = total == null ? Duration.ZERO : total;
        }

        public static Mode stallBeforeHeaders(Dialect dialect) {
            return new Mode(Kind.STALL_BEFORE_HEADERS, dialect, 0, null, null, null);
        }

        public static Mode stallAfterHeaders(Dialect dialect) {
            return new Mode(Kind.STALL_AFTER_HEADERS, dialect, 0, null, null, null);
        }

        public static Mode stallAfterChunks(int chunks, Dialect dialect) {
            return new Mode(Kind.STALL_AFTER_CHUNKS, dialect, chunks, null, null, null);
        }

        public static Mode keepaliveEvery(Duration interval, Duration total, Dialect dialect) {
            return new Mode(Kind.KEEPALIVE_EVERY, dialect, 0, interval, total, null);
        }

        public static Mode slowButLive(Duration interval, Duration total, Dialect dialect) {
            return new Mode(Kind.SLOW_BUT_LIVE, dialect, 0, interval, total, null);
        }

        public static Mode toolCallThenText(String toolName, Dialect dialect) {
            return new Mode(Kind.TOOL_CALL_THEN_TEXT, dialect, 0, null, null, toolName);
        }

        public static Mode answer(Dialect dialect) {
            return new Mode(Kind.ANSWER, dialect, 0, null, null, null);
        }
    }

    /** The text every answer carries, one chunk at a time. */
    public static final String CHUNK_TEXT = "tick";

    private final Mode mode;
    private final ServerSocket server;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> controlRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final CountDownLatch requested = new CountDownLatch(1);
    private final CountDownLatch closedByClient = new CountDownLatch(1);
    private volatile boolean closed;

    /**
     * Starts a server on a free loopback port.
     *
     * @param mode how it answers every model call
     * @throws IOException when no loopback port can be bound
     */
    public StallingHttpServer(Mode mode) throws IOException {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread.ofVirtual().name("stalling-http-server-accept").start(this::acceptLoop);
    }

    /** @return the mode the server answers with */
    public Mode mode() {
        return mode;
    }

    /** @return {@code http://127.0.0.1:<port>}, no trailing slash */
    public String baseUrl() {
        return "http://" + server.getInetAddress().getHostAddress() + ":" + server.getLocalPort();
    }

    /** @return the server's port */
    public int port() {
        return server.getLocalPort();
    }

    /** @return how many model calls (POST requests) the server read so far */
    public int requestCount() {
        return requestCount.get();
    }

    /** @return the request lines and bodies read so far, in order */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** @return the requests that were not a model call (a DELETE, a GET), in order */
    public List<String> controlRequests() {
        return List.copyOf(controlRequests);
    }

    /**
     * Waits for the first request.
     *
     * @param timeout how long to wait
     * @return whether a request arrived in time
     */
    public boolean awaitRequest(Duration timeout) throws InterruptedException {
        return requested.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Waits for the client to let a connection go.
     *
     * @param timeout how long to wait
     * @return {@code true} once a server-side read of a connection saw the end
     *         of the stream or an I/O failure, or a write to it failed
     */
    public boolean clientClosed(Duration timeout) throws InterruptedException {
        return closedByClient.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // Closing anyway.
        }
        for (var socket : sockets) {
            try {
                socket.close();
            } catch (IOException e) {
                // Closing anyway.
            }
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                var socket = server.accept();
                sockets.add(socket);
                Thread.ofVirtual().name("stalling-http-server-conn").start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            var in = socket.getInputStream();
            var out = socket.getOutputStream();
            // One request per connection: the answer ends with the connection.
            var request = readRequest(in);
            if (request == null) {
                clientGone();
                return;
            }
            if (!request.startsWith("POST")) {
                // A control request (a cancel's DELETE, a health or model-list
                // GET) is answered at once and never counted: only the model
                // call's own connection may report a client close.
                controlRequests.add(request);
                out.write(("HTTP/1.1 " + (request.startsWith("DELETE") ? "204 No Content" : "200 OK") + "\r\n"
                        + "Content-Type: application/json\r\nContent-Length: "
                        + (request.startsWith("DELETE") ? "0" : "2") + "\r\nConnection: close\r\n\r\n"
                        + (request.startsWith("DELETE") ? "" : "{}")).getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            requests.add(request);
            var index = requestCount.incrementAndGet();
            requested.countDown();
            if (mode.dialect() == Dialect.GEMINI && isUnaryGemini(request)) {
                answerUnaryGemini(index, in, out);
            } else {
                answer(index, in, out);
            }
        } catch (IOException e) {
            if (!closed) {
                clientGone();
            }
        }
    }

    private void answer(int index, InputStream in, OutputStream out) throws IOException {
        var d = mode.dialect();
        switch (mode.kind()) {
            case STALL_BEFORE_HEADERS -> waitForClientClose(in);
            case STALL_AFTER_HEADERS -> {
                headers(out);
                waitForClientClose(in);
            }
            case STALL_AFTER_CHUNKS -> {
                headers(out);
                chunk(out, Frames.start(d));
                for (int i = 0; i < mode.chunks(); i++) {
                    chunk(out, Frames.text(d, CHUNK_TEXT));
                }
                waitForClientClose(in);
            }
            case KEEPALIVE_EVERY -> {
                headers(out);
                chunk(out, Frames.start(d));
                var end = System.nanoTime() + mode.total().toNanos();
                while (System.nanoTime() < end) {
                    chunk(out, Frames.keepalive(d));
                    sleep(mode.interval());
                }
                finish(out, d);
            }
            case SLOW_BUT_LIVE -> {
                headers(out);
                chunk(out, Frames.start(d));
                var end = System.nanoTime() + mode.total().toNanos();
                while (System.nanoTime() < end) {
                    chunk(out, Frames.text(d, CHUNK_TEXT));
                    sleep(mode.interval());
                }
                finish(out, d);
            }
            case TOOL_CALL_THEN_TEXT -> {
                headers(out);
                chunk(out, Frames.start(d));
                if (index == 1) {
                    chunk(out, Frames.toolCall(d, mode.toolName()));
                    endChunks(out);
                } else {
                    finish(out, d);
                }
            }
            case ANSWER -> {
                headers(out);
                chunk(out, Frames.start(d));
                finish(out, d);
            }
        }
    }

    /**
     * A non-streamed Gemini call ({@code :generateContent}, what ADK sends
     * under its default {@code StreamingMode.NONE}) gets one JSON answer, not
     * SSE frames: its "chunks" are the bytes of that answer, its keep-alives
     * whitespace before it (valid JSON, and invisible to anything but a byte
     * count), and a stall after chunks sends the start of the answer and stops.
     */
    private static boolean isUnaryGemini(String request) {
        var line = request.lines().findFirst().orElse("");
        return line.contains(":generateContent");
    }

    private void answerUnaryGemini(int index, InputStream in, OutputStream out) throws IOException {
        switch (mode.kind()) {
            case STALL_BEFORE_HEADERS -> waitForClientClose(in);
            case STALL_AFTER_HEADERS -> {
                jsonHeaders(out);
                waitForClientClose(in);
            }
            case STALL_AFTER_CHUNKS -> {
                jsonHeaders(out);
                chunk(out, "{\"candidates\": [");
                waitForClientClose(in);
            }
            case KEEPALIVE_EVERY, SLOW_BUT_LIVE -> {
                jsonHeaders(out);
                var end = System.nanoTime() + mode.total().toNanos();
                while (System.nanoTime() < end) {
                    chunk(out, " \n");
                    sleep(mode.interval());
                }
                chunk(out, unaryGeminiText());
                endChunks(out);
            }
            case TOOL_CALL_THEN_TEXT -> {
                jsonHeaders(out);
                chunk(out, index == 1
                        ? "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":"
                                + "{\"name\":\"" + mode.toolName() + "\",\"args\":{}}}]},\"finishReason\":\"STOP\","
                                + "\"index\":0}]}"
                        : unaryGeminiText());
                endChunks(out);
            }
            case ANSWER -> {
                jsonHeaders(out);
                chunk(out, unaryGeminiText());
                endChunks(out);
            }
        }
    }

    private static String unaryGeminiText() {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + CHUNK_TEXT
                + "\"}]},\"finishReason\":\"STOP\",\"index\":0}]}";
    }

    private static void jsonHeaders(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void finish(OutputStream out, Dialect d) throws IOException {
        chunk(out, Frames.text(d, CHUNK_TEXT));
        chunk(out, Frames.end(d));
        endChunks(out);
    }

    private void waitForClientClose(InputStream in) throws IOException {
        // The client sends nothing more on this connection: a read returns
        // only when it lets the connection go.
        while (!closed) {
            if (in.read() < 0) {
                clientGone();
                return;
            }
        }
    }

    private void clientGone() {
        closedByClient.countDown();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void headers(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void chunk(OutputStream out, String data) throws IOException {
        if (data == null || data.isEmpty()) {
            return;
        }
        var bytes = data.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void endChunks(OutputStream out) throws IOException {
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads the request line, the headers and a {@code Content-Length} body. */
    private static String readRequest(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        // The last four bytes read, to find the blank line that ends the headers.
        int last = 0;
        while (last != 0x0D0A0D0A) {
            var b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            last = (last << 8) | b;
        }
        var headers = head.toString(StandardCharsets.ISO_8859_1);
        var length = 0;
        var chunked = false;
        for (var line : headers.split("\r\n")) {
            var lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        var body = chunked ? readChunkedBody(in) : new String(in.readNBytes(length), StandardCharsets.UTF_8);
        return headers.lines().findFirst().orElse("") + "\n" + body;
    }

    private static String readChunkedBody(InputStream in) throws IOException {
        var body = new ByteArrayOutputStream();
        while (true) {
            var sizeLine = readLine(in);
            if (sizeLine == null) {
                return body.toString(StandardCharsets.UTF_8);
            }
            var semicolon = sizeLine.indexOf(';');
            var size = Integer.parseInt((semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim(), 16);
            if (size == 0) {
                readLine(in);
                return body.toString(StandardCharsets.UTF_8);
            }
            body.write(in.readNBytes(size));
            readLine(in);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                var s = line.toString(StandardCharsets.ISO_8859_1);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            line.write(b);
        }
        return null;
    }

    /** The frames of each dialect. */
    static final class Frames {

        private Frames() {
        }

        static String start(Dialect d) {
            return switch (d) {
                case ANTHROPIC -> event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
                        + "\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"stall\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}")
                        + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
                case COHERE -> event("message-start", "{\"type\":\"message-start\",\"id\":\"m1\","
                        + "\"delta\":{\"message\":{\"role\":\"assistant\"}}}");
                default -> "";
            };
        }

        static String text(Dialect d, String text) {
            return switch (d) {
                case OPENAI -> data("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stall\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}]}");
                case ANTHROPIC -> event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}");
                case COHERE -> event("content-delta", "{\"type\":\"content-delta\",\"index\":0,"
                        + "\"delta\":{\"message\":{\"content\":{\"text\":\"" + text + "\"}}}}");
                case CREWAI -> event("token", "{\"text\":\"" + text + "\"}");
                case GEMINI -> data("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\""
                        + text + "\"}]},\"index\":0}]}");
            };
        }

        static String keepalive(Dialect d) {
            return switch (d) {
                case ANTHROPIC -> event("ping", "{\"type\":\"ping\"}");
                default -> ": keep-alive\n\n";
            };
        }

        static String end(Dialect d) {
            return switch (d) {
                case OPENAI -> data("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stall\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")
                        + data("[DONE]");
                case ANTHROPIC -> event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                        + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":"
                        + "\"end_turn\"},\"usage\":{\"output_tokens\":1}}")
                        + event("message_stop", "{\"type\":\"message_stop\"}");
                case COHERE -> event("message-end", "{\"type\":\"message-end\","
                        + "\"delta\":{\"finish_reason\":\"COMPLETE\"}}");
                case CREWAI -> event("done", "{}");
                case GEMINI -> data("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"\"}]},"
                        + "\"finishReason\":\"STOP\",\"index\":0}]}");
            };
        }

        static String toolCall(Dialect d, String toolName) {
            return switch (d) {
                case OPENAI -> data("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stall\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"" + toolName
                        + "\",\"arguments\":\"{}\"}}]}}]}")
                        + data("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stall\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
                        + data("[DONE]");
                case ANTHROPIC -> event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                        + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"" + toolName
                        + "\",\"input\":{}}}")
                        + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}")
                        + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
                        + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":"
                        + "\"tool_use\"},\"usage\":{\"output_tokens\":1}}")
                        + event("message_stop", "{\"type\":\"message_stop\"}");
                case COHERE -> event("tool-call-start", "{\"type\":\"tool-call-start\",\"index\":0,"
                        + "\"delta\":{\"message\":{\"tool_calls\":{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"" + toolName + "\",\"arguments\":\"\"}}}}}")
                        + event("tool-call-delta", "{\"type\":\"tool-call-delta\",\"index\":0,"
                        + "\"delta\":{\"message\":{\"tool_calls\":{\"function\":{\"arguments\":\"{}\"}}}}}")
                        + event("tool-call-end", "{\"type\":\"tool-call-end\",\"index\":0}")
                        + event("message-end", "{\"type\":\"message-end\","
                        + "\"delta\":{\"finish_reason\":\"TOOL_CALL\"}}");
                case GEMINI -> data("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":"
                        + "{\"name\":\"" + toolName + "\",\"args\":{}}}]},\"finishReason\":\"STOP\",\"index\":0}]}");
                // The sidecar runs its tools itself: no tool call crosses its wire.
                case CREWAI -> event("done", "{}");
            };
        }

        private static String data(String json) {
            return "data: " + json + "\n\n";
        }

        private static String event(String name, String json) {
            return "event: " + name + "\ndata: " + json + "\n\n";
        }
    }
}
