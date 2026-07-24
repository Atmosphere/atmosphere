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

import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct tests for the shared SSE plumbing in {@link AbstractSseLlmClient}.
 * These cover the helpers that the Anthropic/Cohere clients now inherit, so a
 * regression in the base is caught here in addition to the two black-box
 * client suites. A tiny concrete {@link TestClient} subclass exposes the
 * {@code protected} helpers to the assertions.
 */
class AbstractSseLlmClientTest {

    /**
     * Minimal concrete subclass that exposes the protected helpers. It carries
     * no provider-specific behaviour — just enough to construct the base and
     * forward the helper calls under test. The 2-arg constructor pins
     * {@link org.atmosphere.ai.RetryPolicy#NONE} so the pre-retry helper tests
     * keep their original single-attempt semantics; retry tests pass an
     * explicit fast policy.
     */
    private static final class TestClient extends AbstractSseLlmClient {
        private TestClient(HttpClient httpClient, Map<String, String> customHeaders) {
            this(httpClient, customHeaders, org.atmosphere.ai.RetryPolicy.NONE);
        }

        private TestClient(HttpClient httpClient, Map<String, String> customHeaders,
                           org.atmosphere.ai.RetryPolicy retryPolicy) {
            super(new SseClientConfig("https://example.test/", "test-key",
                    httpClient, Duration.ofSeconds(5), 256, customHeaders, retryPolicy));
        }

        @Override
        protected String providerName() {
            return "TestProvider";
        }

        // Thin pass-throughs so the test can reach the protected surface.
        boolean callRunRound(HttpRequest req, org.atmosphere.ai.StreamingSession session,
                             AtomicBoolean cancelled, java.util.function.Consumer<JsonNode> onEvent) {
            return runRound(req, session, cancelled, onEvent);
        }

        void callApplyHeaders(HttpRequest.Builder b, Set<String> reserved) {
            applyReservedFilteredHeaders(b, reserved);
        }

        String callReadSnippet(java.io.InputStream body) {
            return readSnippet(body);
        }

        tools.jackson.databind.node.ObjectNode callToolSchema(ToolDefinition def) {
            return toolSchemaObjectNode(def, MAPPER);
        }
    }

    private static TestClient client() {
        return new TestClient(mock(HttpClient.class), Map.of());
    }

    private static TestClient client(Map<String, String> customHeaders) {
        return new TestClient(mock(HttpClient.class), customHeaders);
    }

    private static HttpRequest dummyRequest() {
        return HttpRequest.newBuilder()
                .uri(URI.create("https://example.test/v1/messages"))
                .GET()
                .build();
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockResponse(int statusCode, String body) {
        try {
            var httpClient = mock(HttpClient.class);
            var response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(statusCode);
            when(response.body()).thenReturn(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response);
            return httpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // (a) readSnippet -------------------------------------------------------

    @Test
    void readSnippetReturnsNoBodyFallbackForNullStream() {
        assertEquals("<no body>", client().callReadSnippet(null));
    }

    @Test
    void readSnippetReturnsEmptyStringForEmptyBody() {
        var snippet = client().callReadSnippet(
                new ByteArrayInputStream(new byte[0]));
        assertEquals("", snippet);
    }

    @Test
    void readSnippetTruncatesAt500CharsAndAppendsEllipsis() {
        var longBody = "x".repeat(600);
        var snippet = client().callReadSnippet(
                new ByteArrayInputStream(longBody.getBytes(StandardCharsets.UTF_8)));
        assertEquals(503, snippet.length(), "500 chars + the 3-char ellipsis");
        assertTrue(snippet.endsWith("..."), snippet);
        assertEquals("x".repeat(500) + "...", snippet);
    }

    @Test
    void readSnippetDoesNotTruncateAtExactly500Chars() {
        var body = "y".repeat(500);
        var snippet = client().callReadSnippet(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(body, snippet, "500 chars must pass through verbatim, no ellipsis");
    }

    // (b) applyReservedFilteredHeaders -------------------------------------

    @Test
    void applyHeadersSkipsReservedCaseInsensitivelyAndKeepsOthers() {
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put("X-Tenant-Id", "tenant-3");
        headers.put("Helicone-Auth", "sk-h-xyz");
        // Reserved (mixed case) — must be dropped.
        headers.put("AUTHORIZATION", "Bearer attacker");
        headers.put("Content-Type", "text/plain");
        // Null/blank guards — must be dropped without throwing.
        headers.put("   ", "ignored-blank-name");
        var c = client(headers);

        var builder = HttpRequest.newBuilder()
                .uri(URI.create("https://example.test/v1/chat"))
                .GET();
        c.callApplyHeaders(builder, Set.of("authorization", "content-type", "accept"));
        var req = builder.build();

        assertEquals(List.of("tenant-3"), req.headers().allValues("X-Tenant-Id"));
        assertEquals(List.of("sk-h-xyz"), req.headers().allValues("Helicone-Auth"));
        assertTrue(req.headers().allValues("authorization").isEmpty(),
                "reserved authorization (any case) must be filtered out");
        assertTrue(req.headers().allValues("content-type").isEmpty(),
                "reserved content-type (any case) must be filtered out");
    }

    // (c) the SSE parse loop (runRound) ------------------------------------

    @Test
    void runRoundSkipsNonDataLinesTrimsDropsEmptyAndDebugSkipsBadJson() {
        // Interleave: a comment/non-data line, a "data:" with a leading space
        // that must be trimmed, an empty "data:" payload (dropped), an
        // unparseable JSON "data:" (debug-skipped), and two good events.
        var sse = String.join("\n",
                ": this is an SSE comment, not a data line",
                "event: ping",
                "data: {\"type\":\"one\",\"n\":1}",
                "data:    ",
                "data: {not valid json",
                "data: {\"type\":\"two\",\"n\":2}",
                "");
        var httpClient = mockResponse(200, sse);
        var c = new TestClient(httpClient, Map.of());
        var seen = new ArrayList<JsonNode>();
        var session = new CollectingSession();

        var completed = c.callRunRound(dummyRequest(), session,
                new AtomicBoolean(false), seen::add);

        assertTrue(completed, "stream read to end must report completed");
        assertEquals(2, seen.size(), "only the two parseable, non-empty data events dispatch");
        assertEquals("one", seen.get(0).path("type").asString(""));
        assertEquals(1, seen.get(0).path("n").asInt(-1));
        assertEquals("two", seen.get(1).path("type").asString(""));
    }

    @Test
    void runRoundHonorsCancelledFlagMidStream() {
        var sse = String.join("\n",
                "data: {\"type\":\"first\"}",
                "data: {\"type\":\"second\"}",
                "data: {\"type\":\"third\"}",
                "");
        var httpClient = mockResponse(200, sse);
        var c = new TestClient(httpClient, Map.of());
        var seen = new ArrayList<JsonNode>();
        var session = new CollectingSession();
        // Cancel is checked at the top of each line iteration; trip it after
        // the first event is dispatched so the loop exits before the rest.
        var cancelled = new AtomicBoolean(false);

        var completed = c.callRunRound(dummyRequest(), session, cancelled, event -> {
            seen.add(event);
            cancelled.set(true);
        });

        assertFalse(completed, "a cancelled mid-stream round must not report completed");
        assertEquals(1, seen.size(),
                "the loop must stop dispatching once the cancelled flag is set");
        assertFalse(session.failed(),
                "the cancel path leaves the session untouched (no error)");
    }

    @Test
    void runRoundReportsProviderErrorStringOnNon2xx() {
        var httpClient = mockResponse(503, "{\"error\":\"upstream boom\"}");
        var c = new TestClient(httpClient, Map.of());
        var session = new CollectingSession();

        var completed = c.callRunRound(dummyRequest(), session,
                new AtomicBoolean(false), event -> { });

        assertFalse(completed, "non-2xx must not report completed");
        assertTrue(session.failed(), "non-2xx must surface as session.error()");
        var msg = session.failure().getMessage();
        assertEquals("TestProvider API returned 503: {\"error\":\"upstream boom\"}", msg);
    }

    // (d) toolSchemaObjectNode ---------------------------------------------

    @Test
    void toolSchemaEmitsRequiredArrayOnlyWhenARequiredParamExists() {
        var withRequired = ToolDefinition.builder("calc", "Evaluate")
                .parameter("expression", "math expression", "string", true)
                .parameter("precision", "decimal places", "integer", false)
                .executor(args -> "ok")
                .build();
        var schema = client().callToolSchema(withRequired);

        assertEquals("object", schema.path("type").asString(""));
        assertEquals("string",
                schema.path("properties").path("expression").path("type").asString(""));
        assertEquals("integer",
                schema.path("properties").path("precision").path("type").asString(""));
        assertTrue(schema.has("required"), "required[] present when a param is required");
        assertEquals(1, schema.path("required").size());
        assertEquals("expression", schema.path("required").get(0).asString(""));
    }

    @Test
    void toolSchemaOmitsRequiredArrayWhenNoParamIsRequired() {
        var allOptional = ToolDefinition.builder("noop", "No-op tool")
                .parameter("hint", "an optional hint", "string", false)
                .executor(args -> "ok")
                .build();
        var schema = client().callToolSchema(allOptional);

        assertFalse(schema.has("required"),
                "required[] omitted entirely when no param is required");
    }

    @Test
    void toolSchemaOmitsDescriptionWhenBlankAndDefaultsTypeToString() {
        var blankDesc = ToolDefinition.builder("t", "tool")
                // blank description + null type → description omitted, type defaults to "string"
                .parameter("p", "   ", null, true)
                .executor(args -> "ok")
                .build();
        var schema = client().callToolSchema(blankDesc);

        var prop = schema.path("properties").path("p");
        assertEquals("string", prop.path("type").asString(""),
                "null param type must default to \"string\"");
        assertFalse(prop.has("description"),
                "a blank description must be omitted from the schema property");
    }

    @Test
    void toolSchemaKeepsDescriptionWhenNonBlank() {
        var def = ToolDefinition.builder("t", "tool")
                .parameter("p", "a real description", "string", true)
                .executor(args -> "ok")
                .build();
        var schema = client().callToolSchema(def);

        assertEquals("a real description",
                schema.path("properties").path("p").path("description").asString(""));
    }

    // (e) HTTP-layer retry (the PER_REQUEST_RETRY P1) -----------------------

    /** Counts error() invocations — CollectingSession is write-once/final. */
    private static final class CountingSession implements org.atmosphere.ai.StreamingSession {
        final java.util.concurrent.atomic.AtomicInteger errors =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile Throwable lastFailure;
        @Override public String sessionId() { return "counting"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { errors.incrementAndGet(); lastFailure = t; }
        @Override public boolean isClosed() { return false; }
        @Override public boolean hasErrored() { return errors.get() > 0; }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<java.io.InputStream> responseOf(int status, String body,
                                                                Map<String, List<String>> headers) {
        var response = (HttpResponse<java.io.InputStream>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        // Fresh stream per read so a retried attempt never sees a drained body.
        when(response.body()).thenAnswer(inv ->
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(
                headers, (a, b) -> true));
        return response;
    }

    private static org.atmosphere.ai.RetryPolicy fastRetry(int maxRetries) {
        return new org.atmosphere.ai.RetryPolicy(maxRetries, Duration.ofMillis(1),
                Duration.ofMillis(5), 2.0,
                org.atmosphere.ai.RetryPolicy.DEFAULT.retryableErrors());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runRoundRetries429ThenStreamsTheSecondAttempt() throws Exception {
        // Regression for the Tier-1 P1: a 429 used to surface a bare
        // session.error the outer PER_REQUEST_RETRY wrapper could never retry —
        // zero retries against the dominant transient failure. The base now
        // retries at the HTTP layer and the stream completes on attempt two.
        var httpClient = mock(HttpClient.class);
        var r429 = responseOf(429, "{\"error\":\"rate limited\"}",
                Map.of("Retry-After", List.of("0")));
        var r200 = responseOf(200, "data: {\"type\":\"ok\"}\n\n", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r429, r200);
        var c = new TestClient(httpClient, Map.of(), fastRetry(2));
        var session = new CountingSession();
        var seen = new ArrayList<JsonNode>();

        var completed = c.callRunRound(dummyRequest(), session,
                new AtomicBoolean(false), seen::add);

        assertTrue(completed, "the retried round must stream to completion");
        assertEquals(0, session.errors.get(), "no error may surface on a recovered round");
        assertEquals(1, seen.size());
        assertEquals("ok", seen.get(0).path("type").asString(""));
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(2))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runRoundExhausts429BudgetWithExactlyOneError() throws Exception {
        var httpClient = mock(HttpClient.class);
        var r429 = responseOf(429, "{\"error\":\"rate limited\"}", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r429);
        var c = new TestClient(httpClient, Map.of(), fastRetry(2));
        var session = new CountingSession();

        var completed = c.callRunRound(dummyRequest(), session,
                new AtomicBoolean(false), event -> { });

        assertFalse(completed);
        assertEquals(1, session.errors.get(),
                "exhausted budget must emit session.error exactly once (Inv #2)");
        assertTrue(session.lastFailure.getMessage().contains("429"),
                "the final error must carry the status, got: " + session.lastFailure.getMessage());
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(3))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runRoundDoesNotRetryNonRetryable400() throws Exception {
        var httpClient = mock(HttpClient.class);
        var r400 = responseOf(400, "{\"error\":\"bad request\"}", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r400);
        var c = new TestClient(httpClient, Map.of(), fastRetry(3));
        var session = new CountingSession();

        var completed = c.callRunRound(dummyRequest(), session,
                new AtomicBoolean(false), event -> { });

        assertFalse(completed);
        assertEquals(1, session.errors.get());
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(1))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runRoundHonorsRetryAfterOverPolicyBackoff() throws Exception {
        // Policy backoff is a deliberately huge 5s; Retry-After: 0 must win, so
        // a fast completion proves the header was honored (previously ignored).
        var httpClient = mock(HttpClient.class);
        var r429 = responseOf(429, "{\"error\":\"rate limited\"}",
                Map.of("Retry-After", List.of("0")));
        var r200 = responseOf(200, "data: {\"type\":\"ok\"}\n\n", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r429, r200);
        var slowPolicy = new org.atmosphere.ai.RetryPolicy(1, Duration.ofSeconds(5),
                Duration.ofSeconds(5), 1.0,
                org.atmosphere.ai.RetryPolicy.DEFAULT.retryableErrors());
        var c = new TestClient(httpClient, Map.of(), slowPolicy);
        var session = new CountingSession();

        var startNanos = System.nanoTime();
        var completed = c.callRunRound(dummyRequest(), session,
                new AtomicBoolean(false), event -> { });
        var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(completed);
        assertTrue(elapsedMs < 2500,
                "Retry-After: 0 must override the 5s policy backoff, took " + elapsedMs + "ms");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runRoundRetriesTransportExceptionThenSucceeds() throws Exception {
        var httpClient = mock(HttpClient.class);
        var r200 = responseOf(200, "data: {\"type\":\"ok\"}\n\n", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection reset"))
                .thenReturn(r200);
        var c = new TestClient(httpClient, Map.of(), fastRetry(1));
        var session = new CountingSession();
        var seen = new ArrayList<JsonNode>();

        var completed = c.callRunRound(dummyRequest(), session,
                new AtomicBoolean(false), seen::add);

        assertTrue(completed, "a transient transport failure must be retried");
        assertEquals(0, session.errors.get());
        assertEquals(1, seen.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runRoundDispatchesEventsBeforeHeaderFilteringIsIrrelevant() throws Exception {
        // Sanity: the request actually passed to httpClient.send is the one we
        // built — verifies runRound forwards the request verbatim (no rewrite).
        var httpClient = mockResponse(200, "data: {\"type\":\"x\"}\n\n");
        var c = new TestClient(httpClient, Map.of());
        var session = new CollectingSession();
        var req = dummyRequest();

        c.callRunRound(req, session, new AtomicBoolean(false), event -> { });

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient)
                .send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(req.uri(), captor.getValue().uri());
    }
}
