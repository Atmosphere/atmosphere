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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.RetryPolicy;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generative coverage for the shared SSE line parser in
 * {@link AbstractSseLlmClient#runRound}.
 *
 * <p>{@code AbstractSseLlmClientTest} pins specific hand-written malformed
 * frames. These properties instead <em>generate</em> streams: a random
 * interleaving of well-formed {@code data:} events with SSE comments, bare
 * {@code event:} lines, blank lines, empty payloads, and truncated JSON, then
 * assert the two invariants the parser must hold for any such input —</p>
 *
 * <ol>
 *   <li>it never propagates an exception to the caller (a hostile or truncated
 *       provider stream must not blow up the streaming worker), and</li>
 *   <li>it reconstructs the well-formed events <em>exactly</em>: same payloads,
 *       same order, nothing dropped, nothing invented.</li>
 * </ol>
 *
 * <p>The transport is stubbed with a hand-written {@link HttpClient} rather
 * than a mock so a generated stream costs no mocking overhead across the
 * thousand-plus samples jqwik drives.</p>
 */
class SseFramingPropertyTest {

    /** Concrete subclass exposing the protected {@code runRound} seam. */
    private static final class TestClient extends AbstractSseLlmClient {
        private TestClient(HttpClient httpClient) {
            super(new SseClientConfig("https://example.test", "test-key", httpClient,
                    Duration.ofSeconds(5), 256, Map.of(), RetryPolicy.NONE));
        }

        @Override
        protected String providerName() {
            return "TestProvider";
        }

        boolean callRunRound(HttpRequest request, org.atmosphere.ai.StreamingSession session,
                             AtomicBoolean cancelled, Consumer<JsonNode> onEvent) {
            return runRound(request, session, cancelled, onEvent);
        }
    }

    /**
     * One generated SSE stream: the raw wire bytes plus the payloads a correct
     * parser must recover from them, in order.
     */
    record GeneratedStream(String wire, List<String> expectedPayloads) {
    }

    /** A single generated line, tagged with the payload it should yield (if any). */
    private record Line(String text, String payloadOrNull) {
    }

    // ── Generators ────────────────────────────────────────────────────────

    /** JSON object payloads a provider would legitimately send. */
    private static Arbitrary<String> wellFormedJson() {
        var key = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6);
        var text = Arbitraries.strings().alpha().numeric().ofMaxLength(12);
        var number = Arbitraries.integers().between(-1000, 1000);
        return Combinators.combine(key, text, number).as(
                (k, t, n) -> "{\"" + k + "\":\"" + t + "\",\"n\":" + n + "}");
    }

    /**
     * Lines that must produce no event: SSE comments, bare field lines, blank
     * lines, whitespace-only {@code data:} payloads, and truncated JSON (which
     * the parser debug-skips rather than throwing).
     */
    private static Arbitrary<Line> noiseLine() {
        Arbitrary<Line> comments = Arbitraries.strings().ascii().ofMaxLength(20)
                .map(s -> new Line(": " + s.replace('\n', ' ').replace('\r', ' '), null));
        Arbitrary<Line> fields = Arbitraries.of("event: ping", "event: message",
                        "id: 42", "retry: 1000", "", "   ")
                .map(s -> new Line(s, null));
        Arbitrary<Line> emptyData = Arbitraries.of("data:", "data: ", "data:    ")
                .map(s -> new Line(s, null));
        Arbitrary<Line> truncated = wellFormedJson()
                .map(json -> new Line("data: " + json.substring(0, json.length() / 2), null));
        return Arbitraries.oneOf(comments, fields, emptyData, truncated);
    }

    /** A well-formed data line, with random leading/trailing padding to be trimmed. */
    private static Arbitrary<Line> eventLine() {
        return Combinators.combine(
                        wellFormedJson(),
                        Arbitraries.strings().withChars(' ', '\t').ofMaxLength(3))
                .as((json, pad) -> new Line("data: " + pad + json + pad, json));
    }

    @Provide
    Arbitrary<GeneratedStream> sseStreams() {
        return Arbitraries.oneOf(eventLine(), noiseLine())
                .list().ofMinSize(0).ofMaxSize(40)
                .map(lines -> new GeneratedStream(
                        lines.stream().map(Line::text).collect(Collectors.joining("\n")),
                        lines.stream().map(Line::payloadOrNull)
                                .filter(java.util.Objects::nonNull).toList()));
    }

    // ── Properties ────────────────────────────────────────────────────────

    /**
     * For any interleaving of well-formed events and noise, the parser recovers
     * exactly the well-formed payloads, in order, and reports the round as
     * completed. Nothing is dropped, nothing is invented, nothing throws.
     */
    @Property(tries = 500)
    void recoversExactlyTheWellFormedEventsInOrder(
            @ForAll("sseStreams") GeneratedStream stream) {
        var seen = new ArrayList<String>();
        var session = new CollectingSession();

        var completed = new TestClient(stubbing(200, stream.wire()))
                .callRunRound(request(), session, new AtomicBoolean(false),
                        node -> seen.add(node.toString()));

        assertTrue(completed, "reading a 2xx stream to end-of-input must report completion");
        assertFalse(session.failed(), "a well-framed 2xx stream must not surface an error");

        // Compare on parsed shape, not raw text: the parser hands back a
        // JsonNode, whose toString() normalises key order and spacing.
        var expected = stream.expectedPayloads().stream()
                .map(SseFramingPropertyTest::normalise).toList();
        assertEquals(expected, seen);
    }

    /**
     * Chunk boundaries are a transport concern the line parser must be immune
     * to: the same bytes split at arbitrary points — including mid-JSON and
     * mid-line — must yield the identical event sequence, because the parser
     * reads lines, not chunks.
     */
    @Property(tries = 300)
    void isInsensitiveToChunkBoundaries(@ForAll("sseStreams") GeneratedStream stream,
                                        @ForAll int splitSeed) {
        var bytes = stream.wire().getBytes(StandardCharsets.UTF_8);
        // A ByteArrayInputStream already hands the reader arbitrary slices via
        // its read(byte[],int,int); force a pathological one-byte-at-a-time
        // drip for odd seeds so the BufferedReader must re-assemble every line.
        InputStream body = (splitSeed % 2 == 0)
                ? new ByteArrayInputStream(bytes)
                : new DrippingInputStream(bytes);

        var seen = new ArrayList<String>();
        var session = new CollectingSession();
        var completed = new TestClient(stubbing(200, body))
                .callRunRound(request(), session, new AtomicBoolean(false),
                        node -> seen.add(node.toString()));

        assertTrue(completed);
        assertFalse(session.failed());
        assertEquals(stream.expectedPayloads().stream()
                .map(SseFramingPropertyTest::normalise).toList(), seen);
    }

    /**
     * A stream truncated at an arbitrary byte offset — the shape a provider
     * connection reset produces — must still not throw, and must emit only a
     * prefix of the events the untruncated stream would have produced. A parser
     * that emitted a partial or invented event here would corrupt the reply.
     */
    @Property(tries = 300)
    void truncationYieldsAPrefixAndNeverThrows(@ForAll("sseStreams") GeneratedStream stream,
                                               @ForAll("cutRatio") int cutPercent) {
        var full = stream.wire();
        var cut = full.substring(0, full.length() * cutPercent / 100);

        var seen = new ArrayList<String>();
        var session = new CollectingSession();
        // No exception may escape, whatever the cut lands mid-way through.
        new TestClient(stubbing(200, cut)).callRunRound(
                request(), session, new AtomicBoolean(false),
                node -> seen.add(node.toString()));

        var expected = stream.expectedPayloads().stream()
                .map(SseFramingPropertyTest::normalise).toList();
        assertTrue(seen.size() <= expected.size(),
                "truncation cannot produce MORE events than the full stream");
        assertEquals(expected.subList(0, seen.size()), seen,
                "the events seen must be an exact prefix of the full stream's events");
    }

    @Provide
    Arbitrary<Integer> cutRatio() {
        return Arbitraries.integers().between(0, 100);
    }

    /**
     * A non-2xx response must surface exactly one error and dispatch zero
     * events, whatever the body looks like — including a body that is itself a
     * valid SSE stream, which a parser that read the body before checking the
     * status would happily emit.
     */
    @Property(tries = 200)
    void nonSuccessStatusEmitsOneErrorAndNoEvents(
            @ForAll("sseStreams") GeneratedStream stream,
            @ForAll("errorStatus") int status) {
        var seen = new ArrayList<String>();
        var session = new CollectingSession();

        var completed = new TestClient(stubbing(status, stream.wire()))
                .callRunRound(request(), session, new AtomicBoolean(false),
                        node -> seen.add(node.toString()));

        assertFalse(completed, "a non-2xx round must not report completion");
        // NOTE: CollectingSession records the failure but does not override
        // StreamingSession.hasErrored(), which therefore still reports false.
        // failed()/failure() are this class's truthful predicates, so assert
        // on those rather than on the un-overridden interface default.
        assertTrue(session.failed(), "a non-2xx round must surface an error");
        assertNotNull(session.failure(), "the surfaced error must carry a cause");
        assertTrue(seen.isEmpty(),
                "no event may be dispatched from a non-2xx body, even a well-framed one");
    }

    @Provide
    Arbitrary<Integer> errorStatus() {
        // Non-retryable so RetryPolicy.NONE cannot mask the assertion.
        return Arbitraries.of(400, 401, 403, 404, 409, 422);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static final tools.jackson.databind.ObjectMapper NORMALISER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** Re-render through Jackson so key order / spacing match the parser's output. */
    private static String normalise(String json) {
        return NORMALISER.readTree(json).toString();
    }

    private static HttpRequest request() {
        return HttpRequest.newBuilder()
                .uri(URI.create("https://example.test/v1/messages")).GET().build();
    }

    private static HttpClient stubbing(int status, String body) {
        return stubbing(status, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Hand-written {@link HttpClient} returning one canned response. Avoids
     * per-sample Mockito stubbing, which dominates runtime at 500 tries.
     */
    private static HttpClient stubbing(int status, InputStream body) {
        return new StubHttpClient(status, body);
    }

    /** Feeds the reader one byte per read() so every line must be re-assembled. */
    private static final class DrippingInputStream extends InputStream {
        private final byte[] data;
        private int pos;

        private DrippingInputStream(byte[] data) {
            this.data = data.clone();
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            b[off] = data[pos++];
            return 1;
        }
    }

    /** Minimal {@link HttpClient} that answers every send with one response. */
    private static final class StubHttpClient extends HttpClient {
        private final int status;
        private final InputStream body;

        private StubHttpClient(int status, InputStream body) {
            this.status = status;
            this.body = body;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> handler) {
            return (HttpResponse<T>) new StubResponse(status, body, request);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return java.util.concurrent.CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushHandler) {
            return sendAsync(request, handler);
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            try {
                return javax.net.ssl.SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }
    }

    /** Minimal {@link HttpResponse} carrying the canned status and body. */
    private record StubResponse(int status, InputStream body, HttpRequest request)
            implements HttpResponse<InputStream> {

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
