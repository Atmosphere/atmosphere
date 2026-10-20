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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model call made with a session whose turn gets cancelled (an A2A
 * {@code CancelTask}, an operator's cancel) stops: the client stops reading
 * the stream and closes it, so the model stops generating — it does not run
 * to the end of the answer. An interrupt the cancel raises is an expected end,
 * not a failure.
 */
class OpenAiCompatibleClientTurnCancelTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger framesWritten = new AtomicInteger();
    private final CountDownLatch clientGone = new CountDownLatch(1);
    private volatile boolean silentAfterFirst;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var os = exchange.getResponseBody()) {
                // A slow model: one token every 50 ms for 10 s.
                for (int i = 0; i < 200; i++) {
                    os.write(("data: {\"choices\":[{\"delta\":{\"content\":\"t" + i + " \"}}]}\n\n")
                            .getBytes());
                    os.flush();
                    framesWritten.incrementAndGet();
                    Thread.sleep(silentAfterFirst ? 3_000 : 50);
                }
                os.write("data: [DONE]\n\n".getBytes());
            } catch (IOException | InterruptedException gone) {
                clientGone.countDown();
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiCompatibleClient client() {
        return OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey("test-key")
                .build();
    }

    private static ChatCompletionRequest request() {
        return ChatCompletionRequest.builder("gpt-4")
                .message(new ChatMessage("user", "write an essay"))
                .build();
    }

    @Test
    void aCancelledTurnStopsReadingAndClosesTheModelStream() throws Exception {
        var session = new TurnSession();
        var done = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            client().streamChatCompletion(request(), session);
            done.complete(null);
        });
        assertTrue(session.tokens.await(5, TimeUnit.SECONDS), "the model call streams");

        session.cancelled.set(true);
        done.get(2, TimeUnit.SECONDS);

        assertTrue(clientGone.await(5, TimeUnit.SECONDS),
                "the model stream was not closed: the model kept generating");
        assertTrue(framesWritten.get() < 100, "the model ran to the end: " + framesWritten.get());
        assertEquals(null, session.error, "a cancel is not a failure");
    }

    @Test
    void anInterruptFromTheTurnsCancelEndsTheCallWithoutAFailure() throws Exception {
        silentAfterFirst = true;
        var session = new TurnSession();
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var done = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            worker.set(Thread.currentThread());
            client().streamChatCompletion(request(), session);
            done.complete(null);
        });
        assertTrue(session.firstToken.await(5, TimeUnit.SECONDS), "the model call streams");
        Thread.sleep(200);

        // The model is silent: the read is blocked. The cancel interrupts it.
        session.cancelled.set(true);
        worker.get().interrupt();
        done.get(2, TimeUnit.SECONDS);

        assertEquals(null, session.error, "a cancel is not a failure: " + session.error);
    }

    private static final class TurnSession implements StreamingSession {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch tokens = new CountDownLatch(3);
        final CountDownLatch firstToken = new CountDownLatch(1);
        volatile Throwable error;
        private volatile boolean closed;

        @Override public String sessionId() { return "turn-cancel-test"; }
        @Override public void send(String text) {
            firstToken.countDown();
            tokens.countDown();
        }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { error = t; closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean isTurnCancelled() { return cancelled.get(); }
    }
}
