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

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A provider-classified failure surfaced by an LLM backend — the typed error
 * taxonomy shared by every runtime adapter. Each subclass maps to one
 * {@link #errorType()} from the {@link RetryPolicy#retryableErrors()}
 * vocabulary so retry decisions ({@link RetryPolicy#shouldRetry(AiException, int)}),
 * metrics classification ({@code MetricsCapturingSession}), and fallback
 * routing all agree on what a given failure means.
 *
 * <p>Instances are normally constructed by {@link ProviderErrorClassifier} —
 * from an HTTP status at the Built-in / Anthropic / Cohere client layer, or
 * heuristically from a raw framework exception at each adapter's
 * {@code session.error} seam. The hierarchy is sealed within this file so a
 * {@code switch} over the subtypes is exhaustive; the parent
 * {@link AiException} stays open for the pre-existing subtypes (budget,
 * structured output, sandbox).</p>
 */
public abstract sealed class AiProviderException extends AiException {

    /** HTTP 429 / provider quota exhaustion — retryable under {@link RetryPolicy#DEFAULT}. */
    public static final String TYPE_RATE_LIMIT = "rate_limit";
    /** Request or read timeout (HTTP 408, transport timeouts) — retryable under {@link RetryPolicy#DEFAULT}. */
    public static final String TYPE_TIMEOUT = "timeout";
    /** HTTP 500-class provider fault — retryable under {@link RetryPolicy#DEFAULT}. */
    public static final String TYPE_SERVER_ERROR = "server_error";
    /** HTTP 502/503 / provider overload — retryable under {@link RetryPolicy#DEFAULT}. */
    public static final String TYPE_UNAVAILABLE = "unavailable";
    /** HTTP 401/403 / bad credentials — terminal, never retried. */
    public static final String TYPE_AUTH = "auth";
    /** Prompt exceeds the model's context window — terminal, never retried. */
    public static final String TYPE_CONTEXT_LENGTH = "context_length";
    /** Provider content/safety filter rejected the request — terminal, never retried. */
    public static final String TYPE_CONTENT_FILTER = "content_filter";
    /** Any other terminal provider rejection (malformed request, unsupported field). */
    public static final String TYPE_INVALID_REQUEST = "invalid_request";

    private static final Set<String> TRANSIENT_TYPES =
            Set.of(TYPE_TIMEOUT, TYPE_SERVER_ERROR, TYPE_UNAVAILABLE);

    /** Sentinel for {@link #statusCode} when no HTTP status is known. */
    private static final int NO_STATUS = -1;

    private final String errorType;
    private final int statusCode;

    private AiProviderException(String errorType, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }

    /**
     * The canonical classification of this failure — one of the
     * {@code TYPE_*} constants on this class, matching the vocabulary
     * consumed by {@link RetryPolicy#retryableErrors()} and reported to
     * {@link AiMetrics#recordError}.
     */
    public String errorType() {
        return errorType;
    }

    /**
     * The originating HTTP status code, when the failure was classified from
     * an HTTP response; empty when the classification came from message
     * heuristics on a raw framework exception.
     */
    public OptionalInt statusCode() {
        return statusCode >= 0 ? OptionalInt.of(statusCode) : OptionalInt.empty();
    }

    /** HTTP 429 or a provider rate/quota rejection. */
    public static final class RateLimited extends AiProviderException {

        private final Duration retryAfter;

        /**
         * HTTP-status-path constructor (the response was an observed 429).
         *
         * @param message    human-readable failure description
         * @param retryAfter the provider's {@code Retry-After} hint, or
         *                   {@code null} when the response carried none
         * @param cause      the raw provider exception, or {@code null}
         */
        public RateLimited(String message, Duration retryAfter, Throwable cause) {
            super(TYPE_RATE_LIMIT, 429, message, cause);
            this.retryAfter = retryAfter;
        }

        /**
         * Heuristic-path constructor — a rate-limit signal was found in a
         * raw exception's message, so no HTTP status (and no
         * {@code Retry-After} hint) was actually observed.
         */
        public RateLimited(String message, Throwable cause) {
            super(TYPE_RATE_LIMIT, NO_STATUS, message, cause);
            this.retryAfter = null;
        }

        /** The provider's {@code Retry-After} hint, when one was supplied. */
        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }
    }

    /** HTTP 401/403 or an invalid-credentials rejection. Terminal. */
    public static final class AuthenticationFailed extends AiProviderException {

        public AuthenticationFailed(String message, int statusCode, Throwable cause) {
            super(TYPE_AUTH, statusCode, message, cause);
        }

        /** Heuristic-path constructor (no HTTP status known). */
        public AuthenticationFailed(String message, Throwable cause) {
            this(message, NO_STATUS, cause);
        }
    }

    /** The prompt exceeded the model's context window. Terminal. */
    public static final class ContextLengthExceeded extends AiProviderException {

        public ContextLengthExceeded(String message, int statusCode, Throwable cause) {
            super(TYPE_CONTEXT_LENGTH, statusCode, message, cause);
        }

        /** Heuristic-path constructor (no HTTP status known). */
        public ContextLengthExceeded(String message, Throwable cause) {
            this(message, NO_STATUS, cause);
        }
    }

    /** The provider's content/safety filter rejected the request. Terminal. */
    public static final class ContentFiltered extends AiProviderException {

        public ContentFiltered(String message, int statusCode, Throwable cause) {
            super(TYPE_CONTENT_FILTER, statusCode, message, cause);
        }

        /** Heuristic-path constructor (no HTTP status known). */
        public ContentFiltered(String message, Throwable cause) {
            this(message, NO_STATUS, cause);
        }
    }

    /**
     * A transient provider fault ({@link #TYPE_TIMEOUT},
     * {@link #TYPE_SERVER_ERROR}, or {@link #TYPE_UNAVAILABLE}) — the
     * retryable family under {@link RetryPolicy#DEFAULT}.
     */
    public static final class TransientProviderError extends AiProviderException {

        public TransientProviderError(String errorType, String message, int statusCode,
                                      Throwable cause) {
            super(requireTransient(errorType), statusCode, message, cause);
        }

        /** Heuristic-path constructor (no HTTP status known). */
        public TransientProviderError(String errorType, String message, Throwable cause) {
            this(errorType, message, NO_STATUS, cause);
        }

        private static String requireTransient(String errorType) {
            if (!TRANSIENT_TYPES.contains(errorType)) {
                throw new IllegalArgumentException(
                        "Not a transient error type: " + errorType);
            }
            return errorType;
        }
    }

    /**
     * A terminal provider rejection that is none of the more specific
     * subtypes — malformed request, unsupported field, or an HTTP status
     * outside the retryable table. Never retried.
     */
    public static final class TerminalProviderError extends AiProviderException {

        public TerminalProviderError(String message, int statusCode, Throwable cause) {
            super(TYPE_INVALID_REQUEST, statusCode, message, cause);
        }
    }
}
