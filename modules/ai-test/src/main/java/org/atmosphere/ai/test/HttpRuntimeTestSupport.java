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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

/**
 * Shared {@link HttpClient}-mocking support for the hand-rolled HTTP runtime
 * contract tests (Anthropic, Cohere, and any future provider that talks to a
 * direct HTTP endpoint). Centralises the per-invocation request-body sentinel
 * inspection so the happy path and the forced-error path are exercised
 * identically across those adapters, rather than copy-pasting the mock wiring
 * into each {@code *RuntimeContractTest}.
 */
public final class HttpRuntimeTestSupport {

    private HttpRuntimeTestSupport() {
    }

    /**
     * Build a mocked {@link HttpClient} whose {@code send(...)} inspects the
     * outgoing request body: when it contains {@code errorSentinel} it returns
     * a 500 with an error payload (driving the runtime's {@code session.error}
     * path); otherwise it returns {@code statusCode} with {@code body}.
     *
     * @param statusCode   the happy-path HTTP status to return
     * @param body         the happy-path response body (e.g. a canned SSE stream)
     * @param errorSentinel a marker that, when present in the request body,
     *                      forces a 500 error response
     * @return a Mockito-mocked {@link HttpClient}
     */
    @SuppressWarnings("unchecked")
    public static HttpClient mockHttpClient(int statusCode, String body, String errorSentinel) {
        try {
            var httpClient = mock(HttpClient.class);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenAnswer(inv -> {
                        HttpRequest req = inv.getArgument(0);
                        var requestBody = readBody(req);
                        var response = mock(HttpResponse.class);
                        if (requestBody.contains(errorSentinel)) {
                            when(response.statusCode()).thenReturn(500);
                            when(response.body()).thenReturn(new ByteArrayInputStream(
                                    "{\"error\":\"forced contract error\"}"
                                            .getBytes(StandardCharsets.UTF_8)));
                        } else {
                            when(response.statusCode()).thenReturn(statusCode);
                            when(response.body()).thenReturn(new ByteArrayInputStream(
                                    body.getBytes(StandardCharsets.UTF_8)));
                        }
                        return response;
                    });
            return httpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build a mocked {@link HttpClient} whose response body opens with
     * {@code preamble} and then <em>stays open</em>, trickling SSE comment
     * lines so the reader keeps returning from {@code readLine()} without the
     * stream ever ending. That is what the direct-HTTP adapters (Anthropic,
     * Cohere) need for a cancellation fixture: their
     * {@code AbstractSseLlmClient} read loop polls the caller's
     * {@code cancelled} flag once per line, so a stream that neither ends nor
     * blocks indefinitely leaves the execution genuinely in flight and lets a
     * cancel land at the next line boundary.
     *
     * <p>The body is closed by the client's try-with-resources on every exit
     * path; {@code streamCloses} counts that close, giving the contract test
     * an observable native-release probe (Correctness Invariant #1 —
     * Ownership). The trickle is bounded at 30 seconds, after which the stream
     * reports EOF, so a cancellation regression fails the assertion instead of
     * stranding a worker thread.</p>
     *
     * @param preamble     SSE frames served immediately (e.g. a message-start
     *                     plus one content delta, so text has already reached
     *                     the session before the cancel)
     * @param streamOpens  incremented once per request the client actually
     *                     issues — the in-flight probe a cancellation fixture
     *                     waits on before cancelling
     * @param streamCloses incremented once per stream instance that gets
     *                     released. Counted on the first {@code close()} only:
     *                     {@code close()} is idempotent by the
     *                     {@link java.io.Closeable} contract and the client's
     *                     try-with-resources closes the body twice (once via
     *                     the wrapping reader, once directly), so raw call
     *                     counting would measure the JDK idiom rather than the
     *                     runtime's release behaviour
     * @return a Mockito-mocked {@link HttpClient} returning 200 + that body
     */
    @SuppressWarnings("unchecked")
    public static HttpClient mockOpenEndedStreamHttpClient(
            String preamble,
            java.util.concurrent.atomic.AtomicInteger streamOpens,
            java.util.concurrent.atomic.AtomicInteger streamCloses) {
        try {
            var httpClient = mock(HttpClient.class);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenAnswer(inv -> {
                        var response = mock(HttpResponse.class);
                        when(response.statusCode()).thenReturn(200);
                        when(response.body()).thenReturn(
                                new OpenEndedSseStream(preamble, streamOpens, streamCloses));
                        return response;
                    });
            return httpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Serves {@code preamble} once, then one SSE comment line per read call
     * with a short pause between them. Comment lines are skipped by the SSE
     * parser (they do not start with {@code data: }), so they advance the read
     * loop — and therefore the cancel poll — without injecting synthetic
     * events into the session.
     */
    private static final class OpenEndedSseStream extends java.io.InputStream {

        private static final byte[] KEEPALIVE = ": keepalive\n".getBytes(StandardCharsets.UTF_8);
        private static final long TRICKLE_MILLIS = 20;

        private final byte[] preamble;
        private final java.util.concurrent.atomic.AtomicInteger closes;
        private final long deadlineNanos = System.nanoTime()
                + java.time.Duration.ofSeconds(30).toNanos();
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        private int preamblePos;

        OpenEndedSseStream(String preamble,
                           java.util.concurrent.atomic.AtomicInteger opens,
                           java.util.concurrent.atomic.AtomicInteger closes) {
            this.preamble = preamble.getBytes(StandardCharsets.UTF_8);
            this.closes = closes;
            opens.incrementAndGet();
        }

        @Override
        public int read() {
            var one = new byte[1];
            var n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (preamblePos < preamble.length) {
                var n = Math.min(len, preamble.length - preamblePos);
                System.arraycopy(preamble, preamblePos, b, off, n);
                preamblePos += n;
                return n;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return -1;
            }
            try {
                Thread.sleep(TRICKLE_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return -1;
            }
            var n = Math.min(len, KEEPALIVE.length);
            System.arraycopy(KEEPALIVE, 0, b, off, n);
            return n;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closes.incrementAndGet();
            }
        }
    }

    /**
     * Drain a captured {@link HttpRequest}'s body into a UTF-8 string so
     * wire-shape assertions can inspect exactly what the adapter serialized.
     * Shared by the direct-HTTP adapters' wire tests, which otherwise each
     * re-implement the {@link Flow.Subscriber} plumbing.
     *
     * @param req a request captured from a mocked {@link HttpClient}
     * @return the serialized request body, or {@code ""} when there is none
     */
    public static String requestBodyOf(HttpRequest req) {
        return readBody(req);
    }

    /**
     * Subscribe to the request's body publisher and accumulate the bytes into a
     * UTF-8 string. Mirrors the wire-level boundary inspection the runtime's
     * real {@link HttpClient} does, so sentinel detection lives at the same
     * layer as the production error-routing logic.
     */
    private static String readBody(HttpRequest req) {
        var publisher = req.bodyPublisher().orElse(null);
        if (publisher == null) {
            return "";
        }
        var collector = new BodyCollector();
        publisher.subscribe(collector);
        return collector.toString();
    }

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            var copy = new byte[item.remaining()];
            item.get(copy);
            out.write(copy, 0, copy.length);
        }

        @Override
        public void onError(Throwable throwable) {
            // Body capture is best-effort for contract testing; partial capture
            // falls through to the happy path rather than crashing the test.
        }

        @Override
        public void onComplete() {
            // No-op — toString() reads whatever has been buffered.
        }

        @Override
        public String toString() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
