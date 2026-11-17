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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider that fails in the middle of a stream, through the real LangChain4j
 * OpenAI streaming model and its HTTP client rather than a mock calling
 * {@code onError()} ({@code LangChain4jStreamingAdapterTest}). A local socket
 * speaks OpenAI's SSE chat-completions stream, sends a few chunks, then stalls
 * or drops the connection. Either way the turn must end with one error within a
 * bound, its handle must be done and the connection released (Correctness
 * Invariant #2).
 *
 * <p>A stall needs {@value AiConfig#STREAM_IDLE_TIMEOUT_PROPERTY}: LangChain4j's
 * JDK HTTP client bounds a call only until the response headers arrive, so a
 * stream that stops after its first chunks was never ended. The bound counts
 * only while a model call is in flight: a slow tool between rounds, or a slow
 * but live stream, is not ended.</p>
 *
 * <p>A raw {@link ServerSocket}, not the JDK {@code HttpServer}: closing an
 * {@code HttpServer} response writes the final chunk, a clean end of stream, so
 * it cannot drop a connection mid-stream.</p>
 */
class LangChain4jMidStreamFailureTest {

    private ServerSocket server;
    private final List<AiEnvironment> environments = new ArrayList<>();
    /** One entry per request, completed when the client closed that connection. */
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> released = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void bind() throws IOException {
        server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    void close() throws IOException {
        server.close();
        environments.forEach(AiEnvironment::close);
    }

    @Test
    void aStallAfterSomeChunksEndsTheTurnOnceWithATimeoutAndReleasesTheConnection() throws Exception {
        serve(List.of(Script.chunksThenStall(3)));
        var session = new CountingSession();

        var handle = runtime(Duration.ofSeconds(2)).executeWithHandle(context(List.of()), session);

        var failure = assertEndsWithError(handle, session);
        assertInstanceOf(TimeoutException.class, failure, "the idle bound ended the turn: " + failure);
        assertTrue(session.text().contains("tok-0"), "the chunks before the stall reached the session");
        released.peek().get(5, TimeUnit.SECONDS);
        // Aborting the stream makes LangChain4j report an error of its own, late:
        // the turn ended once, with the timeout.
        Thread.sleep(500);
        assertEquals(1, session.terminals.get(), "one terminal event: " + session.errors);
    }

    @Test
    void aDroppedConnectionAfterSomeChunksEndsTheTurnWithAnError() throws Exception {
        serve(List.of(Script.chunksThenDrop(3)));
        var session = new CountingSession();

        var handle = runtime(Duration.ofMinutes(5)).executeWithHandle(context(List.of()), session);

        var start = System.nanoTime();
        assertEndsWithError(handle, session);
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 10_000, "the drop ended the turn, not a timeout: " + elapsedMs + "ms");
        assertTrue(session.text().contains("tok-0"), "the chunks before the drop reached the session");
        assertEquals(1, session.terminals.get(), "one terminal event: " + session.errors);
    }

    @Test
    void cancellingAStalledTurnReleasesItsConnection() throws Exception {
        serve(List.of(Script.chunksThenStall(3)));
        var session = new CountingSession();
        // The idle bound is off: only the cancel ends this turn.
        var handle = runtime(Duration.ZERO).executeWithHandle(context(List.of()), session);
        assertTrue(session.firstChunk.await(10, TimeUnit.SECONDS), "the stream started");

        handle.cancel();

        assertTrue(handle.isDone(), "a cancelled handle is done");
        released.peek().get(5, TimeUnit.SECONDS);
        // Aborting the stream makes LangChain4j report an error: the cancel ended
        // the turn, so it must not reach the session.
        Thread.sleep(500);
        assertNull(session.errors.peek(), "no error after a cancel: " + session.errors);
    }

    @Test
    void aStallInAFollowUpRoundEndsTheTurnAndReleasesThatRoundsConnection() throws Exception {
        serve(List.of(Script.toolCall("lookup"), Script.chunksThenStall(3)));
        var session = new CountingSession();

        var handle = runtime(Duration.ofSeconds(2)).executeWithHandle(context(List.of(fastTool())), session);

        var failure = assertEndsWithError(handle, session);
        assertInstanceOf(TimeoutException.class, failure, "the idle bound covers round 2: " + failure);
        released.stream().skip(1).findFirst().orElseThrow().get(5, TimeUnit.SECONDS);
        Thread.sleep(500);
        assertEquals(1, session.terminals.get(), "one terminal event: " + session.errors);
    }

    @Test
    void cancellingAFollowUpRoundReleasesItsConnectionAndSendsNoError() throws Exception {
        serve(List.of(Script.toolCall("lookup"), Script.chunksThenStall(3)));
        var session = new CountingSession();
        var handle = runtime(Duration.ZERO).executeWithHandle(context(List.of(fastTool())), session);
        assertTrue(session.firstChunk.await(10, TimeUnit.SECONDS), "round 2 started streaming");

        handle.cancel();

        released.stream().skip(1).findFirst().orElseThrow().get(5, TimeUnit.SECONDS);
        Thread.sleep(500);
        assertNull(session.errors.peek(), "no error after a cancel: " + session.errors);
    }

    @Test
    void aModelCallThatThrowsAtOnceLeavesNoTimeoutBehind() throws Exception {
        var throwing = new dev.langchain4j.model.chat.StreamingChatModel() {
            @Override
            public void chat(dev.langchain4j.model.chat.request.ChatRequest request,
                             dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                throw new IllegalArgumentException("invalid request");
            }
        };
        var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(throwing);
        var environment = AiEnvironment.create(
                Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "300ms")::get);
        environments.add(environment);
        runtime.attach(environment);
        var session = new CountingSession();
        var modelErrors = new ConcurrentLinkedQueue<Throwable>();
        var listener = new org.atmosphere.ai.AgentLifecycleListener() {
            @Override
            public void onModelError(String model, Throwable error) {
                modelErrors.add(error);
            }
        };

        assertThrows(RuntimeException.class, () -> runtime.executeWithHandle(
                context(List.of()).withListeners(List.of(listener)), session));

        // The watchdog ended with the turn: no timeout is reported for a call
        // that failed at once, to the session or to the lifecycle listeners.
        Thread.sleep(1500);
        assertTrue(modelErrors.stream().noneMatch(TimeoutException.class::isInstance),
                "no timeout reported for a call that failed at once: " + modelErrors);
        assertTrue(session.errors.stream().noneMatch(TimeoutException.class::isInstance),
                "no timeout after a call that failed at once: " + session.errors);
    }

    @Test
    void aSlowButLiveStreamLongerThanTheBoundIsNotEnded() throws Exception {
        // A chunk every 200 ms for about 5 s, against a 3 s idle bound.
        serve(List.of(Script.slowAnswer(25, 200)));
        var session = new CountingSession();

        var handle = runtime(Duration.ofSeconds(3)).executeWithHandle(context(List.of()), session);

        handle.whenDone().get(30, TimeUnit.SECONDS);
        assertNull(session.errors.peek(), "a live stream is not ended: " + session.errors);
        assertTrue(session.text().contains("tok-24"), "the whole answer arrived: " + session.text());
    }

    @Test
    void aToolSlowerThanTheBoundIsNotEnded() throws Exception {
        // Round 1 asks for the tool; the tool runs 5 s against a 2 s idle bound;
        // round 2 answers.
        serve(List.of(Script.toolCall("slow_lookup"), Script.slowAnswer(2, 10)));
        var tool = ToolDefinition.builder("slow_lookup", "A slow lookup")
                .parameter("city", "the city", "string")
                .executor(args -> {
                    Thread.sleep(5000);
                    return "sunny";
                })
                .build();
        var session = new CountingSession();

        var handle = runtime(Duration.ofSeconds(2)).executeWithHandle(context(List.of(tool)), session);

        handle.whenDone().get(30, TimeUnit.SECONDS);
        assertNull(session.errors.peek(), "a slow tool is not a stalled stream: " + session.errors);
        assertTrue(session.text().contains("tok-1"), "round 2 answered: " + session.text());
    }

    @Test
    void theBoundIsReadFromTheRuntimesOwnEnvironment() {
        var runtime = runtime(Duration.ofMillis(1234));
        assertEquals(Duration.ofMillis(1234), runtime.streamIdleTimeout());
        var unset = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model());
        var environment = AiEnvironment.create(key -> null);
        environments.add(environment);
        unset.attach(environment);
        assertEquals(AiConfig.DEFAULT_STREAM_IDLE_TIMEOUT, unset.streamIdleTimeout(),
                "with no knob set the default applies");
    }

    private static ToolDefinition fastTool() {
        return ToolDefinition.builder("lookup", "A lookup")
                .parameter("city", "the city", "string")
                .executor(args -> "sunny")
                .build();
    }

    private Throwable assertEndsWithError(ExecutionHandle handle, CountingSession session) {
        var failed = assertThrows(ExecutionException.class, () -> handle.whenDone().get(20, TimeUnit.SECONDS),
                "the handle must complete exceptionally within the bound");
        assertTrue(session.isClosed(), "the session must be closed");
        var error = session.errors.peek();
        assertTrue(error != null, "the session must end with an error, not complete: " + session.text());
        assertFalse(session.completed.get(), "the session did not complete");
        return failed.getCause() != null ? failed.getCause() : error;
    }

    private LangChain4jRuntimeContractTest.TestableLangChain4jRuntime runtime(Duration idleTimeout) {
        var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model());
        var knobs = Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, idleTimeout.toMillis() + "ms");
        var environment = AiEnvironment.create(knobs::get);
        environments.add(environment);
        runtime.attach(environment);
        return runtime;
    }

    private OpenAiStreamingChatModel model() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl("http://127.0.0.1:" + server.getLocalPort() + "/v1")
                .apiKey("test-key")
                .modelName("gpt-4o-mini")
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private static AgentExecutionContext context(List<ToolDefinition> tools) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", "conv-1",
                tools, null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /** What the stub answers to one request. */
    private record Script(List<String> events, long gapMs, boolean dropAfter, boolean stallAfter) {

        static Script chunksThenStall(int chunks) {
            return new Script(tokens(chunks), 0, false, true);
        }

        static Script chunksThenDrop(int chunks) {
            return new Script(tokens(chunks), 0, true, false);
        }

        static Script slowAnswer(int chunks, long gapMs) {
            var events = new ArrayList<>(tokens(chunks));
            events.add(chunk("{},\"finish_reason\":\"stop\""));
            events.add("[DONE]");
            return new Script(events, gapMs, false, false);
        }

        static Script toolCall(String tool) {
            return new Script(List.of(
                    chunk("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                            + "\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\","
                            + "\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]}"),
                    chunk("{},\"finish_reason\":\"tool_calls\""),
                    "[DONE]"), 0, false, false);
        }

        private static List<String> tokens(int count) {
            var events = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                events.add(chunk("{\"content\":\"tok-" + i + " \"}"));
            }
            return events;
        }

        /** One chunk whose choice has the given delta (and, inline, any choice fields after it). */
        private static String chunk(String delta) {
            return "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                    + "\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":" + delta + "}]}";
        }
    }

    /** Answers one connection per script, in order. */
    private void serve(List<Script> scripts) {
        Thread.ofVirtual().start(() -> {
            for (var script : scripts) {
                var connectionReleased = new CompletableFuture<Void>();
                released.add(connectionReleased);
                try (Socket socket = server.accept()) {
                    answer(socket, script, connectionReleased);
                } catch (IOException e) {
                    // A reset from the client also releases the connection.
                    connectionReleased.complete(null);
                }
            }
        });
    }

    private static void answer(Socket socket, Script script, CompletableFuture<Void> connectionReleased)
            throws IOException {
        var in = socket.getInputStream();
        readRequest(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        for (var event : script.events()) {
            writeChunk(out, "data: " + event + "\n\n");
            out.flush();
            if (script.gapMs() > 0) {
                try {
                    Thread.sleep(script.gapMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        out.flush();
        if (script.dropAfter()) {
            // Let the client read the chunks, then reset: no final chunk, no [DONE].
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            socket.setSoLinger(true, 0);
            return;
        }
        if (script.stallAfter()) {
            // Send nothing more. Ending the turn must close the client's end,
            // which this read then sees as end of stream.
            if (in.read() == -1) {
                connectionReleased.complete(null);
            }
            return;
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        connectionReleased.complete(null);
    }

    private static void readRequest(BufferedReader reader) throws IOException {
        int contentLength = 0;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        var body = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = reader.read(body, read, contentLength - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
    }

    private static void writeChunk(OutputStream out, String data) throws IOException {
        var bytes = data.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    /** Records the text and counts the terminal events a turn sends. */
    private static final class CountingSession implements StreamingSession {
        private final StringBuffer text = new StringBuffer();
        private final AtomicInteger terminals = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        private final CountDownLatch firstChunk = new CountDownLatch(1);
        private volatile boolean closed;

        String text() {
            return text.toString();
        }

        @Override public String sessionId() { return "mid-stream"; }
        @Override public void send(String chunk) {
            text.append(chunk);
            firstChunk.countDown();
        }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() {
            terminals.incrementAndGet();
            completed.set(true);
            closed = true;
        }
        @Override public void complete(String summary) {
            complete();
        }
        @Override public void error(Throwable t) {
            terminals.incrementAndGet();
            errors.add(t);
            closed = true;
        }
        @Override public boolean isClosed() { return closed; }
    }
}
