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
package org.atmosphere.ai.llm;

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-6 Built-in hard-cancel regression: proves that closing the in-flight
 * SSE {@link java.io.InputStream} from another thread interrupts the blocked
 * {@code BufferedReader.readLine()} in the SSE read loop immediately, rather
 * than waiting for an HTTP timeout or the next SSE frame.
 *
 * <p>The test wires a tiny local HTTP server that speaks SSE but sits silent
 * between frames. Without the fix (polled flag only), a cancel during the
 * silent gap would block until the HTTP timeout. With the fix (InputStream
 * close), the cancel unwinds the read within ~100ms.</p>
 */
class OpenAiCompatibleClientCancelTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            // Respond with one SSE frame, then hang the connection so the
            // client's readLine() blocks waiting for the next line.
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var os = exchange.getResponseBody()) {
                os.write(("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n").getBytes());
                os.flush();
                // Sit here until the client closes the connection.
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cancelClosesInFlightStreamAndInterruptsBlockedRead() throws Exception {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey("test-key")
                .build();

        var request = ChatCompletionRequest.builder("gpt-4")
                .message(new ChatMessage("user", "hello"))
                .build();

        var session = new RecordingSession();
        var cancelled = new AtomicBoolean();
        var inFlight = new AtomicReference<java.io.Closeable>();
        var streamCaptured = new CountDownLatch(1);

        var done = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                client.streamChatCompletion(request, session, cancelled, stream -> {
                    if (stream != null) {
                        inFlight.set(stream);
                        streamCaptured.countDown();
                    } else {
                        inFlight.set(null);
                    }
                });
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });

        // Wait for the client to capture the stream (i.e. it's reading the response body).
        assertTrue(streamCaptured.await(5, TimeUnit.SECONDS),
                "Client should have captured the in-flight InputStream");
        assertNotNull(inFlight.get());

        // Let the first SSE frame land so the client is blocked on readLine().
        Thread.sleep(200);

        // Now cancel: flip the flag AND close the stream from this thread.
        var startNanos = System.nanoTime();
        cancelled.set(true);
        inFlight.get().close();

        // The blocked readLine() should unwind within 1 second — hard, not soft.
        done.get(1, TimeUnit.SECONDS);
        var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(elapsedMs < 1000,
                "cancel() must unwind the blocked read within 1s, took " + elapsedMs + "ms");
    }

    private static final class RecordingSession implements StreamingSession {
        private final StringBuilder buffer = new StringBuilder();
        private volatile boolean closed;
        @Override public String sessionId() { return "cancel-test"; }
        @Override public synchronized void send(String text) { buffer.append(text); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
}
