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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapping-table pin for the shared error taxonomy: every status-code row and
 * message-heuristic row that retry, metrics, and routing rely on.
 */
class ProviderErrorClassifierTest {

    // -- fromHttpStatus: status-code rows --

    @Test
    void status429MapsToRateLimitedWithRetryAfter() {
        var e = ProviderErrorClassifier.fromHttpStatus(
                429, "API returned 429: rate limited", Duration.ofSeconds(7));
        var rateLimited = assertInstanceOf(AiProviderException.RateLimited.class, e);
        assertEquals("rate_limit", e.errorType());
        assertEquals(429, e.statusCode().orElseThrow());
        assertEquals(Duration.ofSeconds(7), rateLimited.retryAfter().orElseThrow());
        assertEquals("API returned 429: rate limited", e.getMessage());
    }

    @Test
    void status429WithoutHeaderHasEmptyRetryAfter() {
        var e = ProviderErrorClassifier.fromHttpStatus(429, "API returned 429: rate limited", null);
        var rateLimited = assertInstanceOf(AiProviderException.RateLimited.class, e);
        assertTrue(rateLimited.retryAfter().isEmpty());
    }

    @Test
    void status401And403MapToAuthenticationFailed() {
        for (var status : new int[]{401, 403}) {
            var e = ProviderErrorClassifier.fromHttpStatus(status, "API returned " + status, null);
            assertInstanceOf(AiProviderException.AuthenticationFailed.class, e);
            assertEquals("auth", e.errorType());
            assertEquals(status, e.statusCode().orElseThrow());
        }
    }

    @Test
    void status400WithContextMessageMapsToContextLengthExceeded() {
        var e = ProviderErrorClassifier.fromHttpStatus(400,
                "API returned 400: This model's maximum context length is 8192 tokens", null);
        assertInstanceOf(AiProviderException.ContextLengthExceeded.class, e);
        assertEquals("context_length", e.errorType());
        assertEquals(400, e.statusCode().orElseThrow());
    }

    @Test
    void status400WithContentFilterMessageMapsToContentFiltered() {
        var e = ProviderErrorClassifier.fromHttpStatus(400,
                "API returned 400: The response was blocked by the content filter", null);
        assertInstanceOf(AiProviderException.ContentFiltered.class, e);
        assertEquals("content_filter", e.errorType());
    }

    @Test
    void plainStatus400MapsToTerminalProviderError() {
        var e = ProviderErrorClassifier.fromHttpStatus(400, "API returned 400: bad request", null);
        assertInstanceOf(AiProviderException.TerminalProviderError.class, e);
        assertEquals("invalid_request", e.errorType());
    }

    @Test
    void transientStatusesMapToTransientProviderError() {
        assertEquals("timeout",
                ProviderErrorClassifier.fromHttpStatus(408, "408", null).errorType());
        assertEquals("server_error",
                ProviderErrorClassifier.fromHttpStatus(500, "500", null).errorType());
        assertEquals("unavailable",
                ProviderErrorClassifier.fromHttpStatus(502, "502", null).errorType());
        assertEquals("unavailable",
                ProviderErrorClassifier.fromHttpStatus(503, "503", null).errorType());
    }

    @Test
    void status504StaysTerminalMatchingTheRetryTable() {
        // 504 was never in the HTTP retry loops' retryable table; the typed
        // classification preserves that (terminal), so retry and taxonomy
        // cannot disagree on unlisted 5xx statuses.
        var e = ProviderErrorClassifier.fromHttpStatus(504, "API returned 504", null);
        assertInstanceOf(AiProviderException.TerminalProviderError.class, e);
        assertFalse(ProviderErrorClassifier.isRetryableStatus(504, RetryPolicy.DEFAULT));
    }

    // -- isRetryableStatus: the folded HTTP retry gate --

    @Test
    void isRetryableStatusHonorsPolicyVocabulary() {
        assertTrue(ProviderErrorClassifier.isRetryableStatus(429, RetryPolicy.DEFAULT));
        assertTrue(ProviderErrorClassifier.isRetryableStatus(500, RetryPolicy.DEFAULT));
        assertTrue(ProviderErrorClassifier.isRetryableStatus(503, RetryPolicy.DEFAULT));
        assertTrue(ProviderErrorClassifier.isRetryableStatus(408, RetryPolicy.DEFAULT));
        assertFalse(ProviderErrorClassifier.isRetryableStatus(400, RetryPolicy.DEFAULT));
        assertFalse(ProviderErrorClassifier.isRetryableStatus(401, RetryPolicy.DEFAULT));
        // An empty retryable set disables the whole table.
        assertFalse(ProviderErrorClassifier.isRetryableStatus(429, RetryPolicy.NONE));
    }

    // -- parseRetryAfter --

    @Test
    void parseRetryAfterHandlesSecondsAndGarbage() {
        assertEquals(Duration.ofSeconds(7),
                ProviderErrorClassifier.parseRetryAfter("7").orElseThrow());
        assertEquals(Duration.ofSeconds(0),
                ProviderErrorClassifier.parseRetryAfter(" 0 ").orElseThrow());
        assertTrue(ProviderErrorClassifier.parseRetryAfter("-3").isEmpty());
        assertTrue(ProviderErrorClassifier.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT").isEmpty());
        assertTrue(ProviderErrorClassifier.parseRetryAfter("").isEmpty());
        assertTrue(ProviderErrorClassifier.parseRetryAfter(null).isEmpty());
    }

    // -- tryClassify: message heuristics on raw exceptions --

    @Test
    void heuristicsClassifyRateLimitMessages() {
        var raw = new RuntimeException("429 Too Many Requests");
        var typed = ProviderErrorClassifier.tryClassify(raw).orElseThrow();
        assertInstanceOf(AiProviderException.RateLimited.class, typed);
        assertSame(raw, typed.getCause());
        assertTrue(typed.statusCode().isEmpty(), "heuristic path carries no HTTP status");
    }

    @Test
    void heuristicsClassifyTimeoutAuthContextAndFilterMessages() {
        assertEquals("timeout", ProviderErrorClassifier
                .tryClassify(new RuntimeException("Read timed out")).orElseThrow().errorType());
        assertEquals("auth", ProviderErrorClassifier
                .tryClassify(new RuntimeException("Invalid API key provided")).orElseThrow().errorType());
        assertEquals("context_length", ProviderErrorClassifier
                .tryClassify(new RuntimeException(
                        "This model's maximum context length is 8192 tokens")).orElseThrow().errorType());
        assertEquals("content_filter", ProviderErrorClassifier
                .tryClassify(new RuntimeException(
                        "Request blocked due to safety settings")).orElseThrow().errorType());
        assertEquals("unavailable", ProviderErrorClassifier
                .tryClassify(new RuntimeException("503 Service Unavailable")).orElseThrow().errorType());
        assertEquals("server_error", ProviderErrorClassifier
                .tryClassify(new RuntimeException("Internal server error")).orElseThrow().errorType());
    }

    @Test
    void heuristicsWalkTheCauseChain() {
        var raw = new RuntimeException("dispatch failed",
                new IllegalStateException("HTTP 429 rate limit exceeded"));
        var typed = ProviderErrorClassifier.tryClassify(raw).orElseThrow();
        assertInstanceOf(AiProviderException.RateLimited.class, typed);
    }

    @Test
    void noSignalMeansEmptyNeverTerminal() {
        assertTrue(ProviderErrorClassifier.tryClassify(new RuntimeException("boom")).isEmpty());
        assertTrue(ProviderErrorClassifier.tryClassify(new RuntimeException()).isEmpty());
    }

    // -- wrap: session.error seam behavior --

    @Test
    void wrapPassesThroughTypedDomainAndCancellationExceptions() {
        var typed = new AiProviderException.RateLimited("429", null, null);
        assertSame(typed, ProviderErrorClassifier.wrap(typed));
        var domain = new AiException("budget exceeded maybe");
        assertSame(domain, ProviderErrorClassifier.wrap(domain));
        var cancel = new CancellationException("cancelled");
        assertSame(cancel, ProviderErrorClassifier.wrap(cancel));
        var interrupt = new InterruptedException("interrupted");
        assertSame(interrupt, ProviderErrorClassifier.wrap(interrupt));
    }

    @Test
    void wrapClassifiesSignalsAndPassesThroughNoise() {
        var raw = new RuntimeException("HTTP 429 rate limit exceeded");
        var wrapped = ProviderErrorClassifier.wrap(raw);
        var typed = assertInstanceOf(AiProviderException.RateLimited.class, wrapped);
        assertSame(raw, typed.getCause());
        assertEquals(raw.getMessage(), typed.getMessage());

        var noise = new RuntimeException("boom");
        assertSame(noise, ProviderErrorClassifier.wrap(noise),
                "no-signal exceptions must pass through unchanged");
    }

    // -- errorType: the metrics projection --

    @Test
    void errorTypeProjectsTypedHeuristicAndUnknown() {
        assertEquals("content_filter", ProviderErrorClassifier.errorType(
                new AiProviderException.ContentFiltered("blocked", 400, null)));
        assertEquals("rate_limit", ProviderErrorClassifier.errorType(
                new RuntimeException("429 rate limit exceeded")));
        assertEquals("unknown", ProviderErrorClassifier.errorType(new RuntimeException("boom")));
        assertEquals("unknown", ProviderErrorClassifier.errorType(null));
    }

    // -- typed RetryPolicy.shouldRetry --

    @Test
    void typedShouldRetryDistinguishesTransientFromTerminal() {
        var policy = RetryPolicy.DEFAULT;
        assertTrue(policy.shouldRetry(
                new AiProviderException.RateLimited("429", null, null), 0));
        assertTrue(policy.shouldRetry(new AiProviderException.TransientProviderError(
                "unavailable", "503", 503, null), 0));
        assertFalse(policy.shouldRetry(
                new AiProviderException.TerminalProviderError("400", 400, null), 0));
        assertFalse(policy.shouldRetry(
                new AiProviderException.AuthenticationFailed("401", 401, null), 0));
        assertFalse(policy.shouldRetry(
                new AiProviderException.ContextLengthExceeded("too long", 400, null), 0));
        // Budget exhaustion always wins.
        assertFalse(policy.shouldRetry(
                new AiProviderException.RateLimited("429", null, null), policy.maxRetries()));
        // Unclassified AiExceptions keep the historical retry-any behavior.
        assertTrue(policy.shouldRetry(new AiException("opaque"), 0));
    }

    @Test
    void typedShouldRetryHonorsCustomVocabulary() {
        var noRateLimit = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5),
                1.0, Set.of("timeout"));
        assertFalse(noRateLimit.shouldRetry(
                new AiProviderException.RateLimited("429", null, null), 0));
        assertTrue(noRateLimit.shouldRetry(new AiProviderException.TransientProviderError(
                "timeout", "timed out", null), 0));
    }

    // -- taxonomy invariants --

    @Test
    void transientProviderErrorRejectsTerminalTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiProviderException.TransientProviderError("auth", "nope", 401, null));
    }

    @Test
    void taxonomyExtendsAiExceptionForExistingCatches() {
        AiException e = ProviderErrorClassifier.fromHttpStatus(429, "429", null);
        assertNotNull(e, "typed failures remain catchable as AiException");
    }
}
