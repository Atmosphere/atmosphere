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
import java.util.concurrent.CancellationException;

/**
 * The single classifier that turns raw provider failures into the typed
 * {@link AiProviderException} taxonomy. Two entry points:
 *
 * <ul>
 *   <li>{@link #fromHttpStatus} — authoritative status-code mapping used by
 *       the direct-HTTP clients (Built-in {@code OpenAiCompatibleClient},
 *       Anthropic/Cohere via {@code AbstractSseLlmClient}). The status table
 *       is the same one the HTTP retry loops consult through
 *       {@link #isRetryableStatus}, so what gets retried and what gets
 *       surfaced always agree.</li>
 *   <li>{@link #wrap} / {@link #tryClassify} — conservative message
 *       heuristics used by the framework adapters (Spring AI, LangChain4j,
 *       ADK, Koog, Semantic Kernel, AgentScope, Alibaba, Embabel, CrewAI) at
 *       their {@code session.error} seams, where the native SDK surfaced an
 *       opaque exception instead of an HTTP status. When no signal is found
 *       the raw exception passes through unchanged — unclassified failures
 *       keep their historical behavior everywhere.</li>
 * </ul>
 *
 * <p>{@link #errorType(Throwable)} projects any throwable onto the metric
 * vocabulary reported to {@link AiMetrics#recordError}, so the metrics
 * pipeline and the retry/routing layers classify identically.</p>
 */
public final class ProviderErrorClassifier {

    /** Metric label for failures the classifier finds no signal in. */
    public static final String TYPE_UNKNOWN = "unknown";

    /** Max cause-chain depth walked by the heuristics (cycle guard). */
    private static final int MAX_CAUSE_DEPTH = 8;

    private ProviderErrorClassifier() {
    }

    /**
     * Classify an HTTP status into a typed exception. The transient rows
     * (429, 408, 500, 502, 503) match {@link #isRetryableStatus}'s table
     * exactly; 401/403 map to {@link AiProviderException.AuthenticationFailed};
     * every other status is terminal, refined to
     * {@link AiProviderException.ContextLengthExceeded} or
     * {@link AiProviderException.ContentFiltered} when the provider's error
     * message carries the corresponding signal.
     *
     * @param statusCode the HTTP status
     * @param message    the composed error message (kept verbatim on the
     *                   returned exception so wire frames are unchanged)
     * @param retryAfter parsed {@code Retry-After} hint for 429 responses,
     *                   or {@code null}
     */
    public static AiProviderException fromHttpStatus(int statusCode, String message,
                                                     Duration retryAfter) {
        return switch (statusCode) {
            case 429 -> new AiProviderException.RateLimited(message, retryAfter, null);
            case 401, 403 -> new AiProviderException.AuthenticationFailed(message, statusCode, null);
            case 408 -> new AiProviderException.TransientProviderError(
                    AiProviderException.TYPE_TIMEOUT, message, statusCode, null);
            case 500 -> new AiProviderException.TransientProviderError(
                    AiProviderException.TYPE_SERVER_ERROR, message, statusCode, null);
            case 502, 503 -> new AiProviderException.TransientProviderError(
                    AiProviderException.TYPE_UNAVAILABLE, message, statusCode, null);
            default -> refineTerminal(statusCode, message);
        };
    }

    /**
     * Whether the HTTP retry loops should retry this status under the given
     * policy. This is the pre-taxonomy classification table shared by
     * {@code OpenAiCompatibleClient.sendWithRetry} and
     * {@code AbstractSseLlmClient.runRound}, folded into one place: a status
     * outside the table (including 504 and other unlisted 5xx) is never
     * retried, exactly as before the taxonomy landed.
     */
    public static boolean isRetryableStatus(int statusCode, RetryPolicy policy) {
        var errorType = transientStatusErrorType(statusCode);
        return errorType != null && policy.retryableErrors().contains(errorType);
    }

    /**
     * Parse an HTTP {@code Retry-After} header value as integer seconds.
     * Non-numeric forms (the HTTP-date variant) and negative values resolve
     * to empty — matching the defensive posture of the HTTP retry loops.
     */
    public static Optional<Duration> parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            var seconds = Integer.parseInt(value.trim());
            return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Wrap a raw failure for a {@code session.error} seam. Already-typed
     * domain exceptions ({@link AiException} and subclasses), cancellation,
     * and interrupts pass through untouched; otherwise the message
     * heuristics run and, when a signal is found, the raw exception is
     * returned wrapped in the matching {@link AiProviderException} (raw as
     * cause, message preserved). No signal — the raw exception is returned
     * unchanged, so unclassifiable failures behave exactly as before.
     */
    public static Throwable wrap(Throwable raw) {
        if (raw == null
                || raw instanceof AiException
                || raw instanceof CancellationException
                || raw instanceof InterruptedException) {
            return raw;
        }
        return tryClassify(raw).<Throwable>map(p -> p).orElse(raw);
    }

    /**
     * Heuristically classify a raw exception. An {@link AiProviderException}
     * anywhere in the cause chain wins; otherwise each message in the chain
     * (outermost first) is matched against the signal table. Empty when no
     * signal is found — callers must treat that as "unclassified", never as
     * "terminal".
     */
    public static Optional<AiProviderException> tryClassify(Throwable raw) {
        var depth = 0;
        for (var t = raw; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof AiProviderException typed) {
                return Optional.of(typed);
            }
        }
        depth = 0;
        for (var t = raw; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            var classified = classifyMessage(t.getMessage(), raw);
            if (classified.isPresent()) {
                return classified;
            }
        }
        return Optional.empty();
    }

    /**
     * Project any throwable onto the {@link AiMetrics#recordError} label
     * vocabulary: a typed or heuristically-classified failure reports its
     * {@link AiProviderException#errorType()}; everything else reports
     * {@link #TYPE_UNKNOWN}.
     */
    public static String errorType(Throwable t) {
        if (t == null) {
            return TYPE_UNKNOWN;
        }
        return tryClassify(t).map(AiProviderException::errorType).orElse(TYPE_UNKNOWN);
    }

    private static String transientStatusErrorType(int statusCode) {
        return switch (statusCode) {
            case 429 -> AiProviderException.TYPE_RATE_LIMIT;
            case 500 -> AiProviderException.TYPE_SERVER_ERROR;
            case 502, 503 -> AiProviderException.TYPE_UNAVAILABLE;
            case 408 -> AiProviderException.TYPE_TIMEOUT;
            default -> null;
        };
    }

    private static AiProviderException refineTerminal(int statusCode, String message) {
        var lower = message != null ? message.toLowerCase(java.util.Locale.ROOT) : "";
        if (hasContextLengthSignal(lower)) {
            return new AiProviderException.ContextLengthExceeded(message, statusCode, null);
        }
        if (hasContentFilterSignal(lower)) {
            return new AiProviderException.ContentFiltered(message, statusCode, null);
        }
        return new AiProviderException.TerminalProviderError(message, statusCode, null);
    }

    /**
     * The message signal table, checked in fixed precedence. The timeout /
     * rate-limit / server-error rows extend the pre-taxonomy heuristics of
     * {@code MetricsCapturingSession.classifyError}; the auth /
     * context-length / content-filter rows are the taxonomy's additions.
     * Bare digits ("429", "503") are matched because provider SDK messages
     * routinely embed the status code and nothing else machine-readable.
     */
    private static Optional<AiProviderException> classifyMessage(String message, Throwable raw) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        var lower = message.toLowerCase(java.util.Locale.ROOT);
        if (containsAny(lower, "timeout", "timed out", "408")) {
            return Optional.of(new AiProviderException.TransientProviderError(
                    AiProviderException.TYPE_TIMEOUT, message, raw));
        }
        if (containsAny(lower, "429", "rate limit", "rate_limit", "ratelimit",
                "too many requests", "quota")) {
            return Optional.of(new AiProviderException.RateLimited(message, raw));
        }
        if (containsAny(lower, "401", "403", "unauthorized", "forbidden",
                "invalid api key", "invalid x-api-key", "authentication", "permission denied")) {
            return Optional.of(new AiProviderException.AuthenticationFailed(message, raw));
        }
        if (hasContextLengthSignal(lower)) {
            return Optional.of(new AiProviderException.ContextLengthExceeded(message, raw));
        }
        if (hasContentFilterSignal(lower)) {
            return Optional.of(new AiProviderException.ContentFiltered(message, raw));
        }
        if (containsAny(lower, "502", "503", "bad gateway", "service unavailable", "overloaded")) {
            return Optional.of(new AiProviderException.TransientProviderError(
                    AiProviderException.TYPE_UNAVAILABLE, message, raw));
        }
        if (containsAny(lower, "500", "internal server error", "server error")) {
            return Optional.of(new AiProviderException.TransientProviderError(
                    AiProviderException.TYPE_SERVER_ERROR, message, raw));
        }
        return Optional.empty();
    }

    private static boolean hasContextLengthSignal(String lower) {
        return containsAny(lower, "context length", "context_length", "maximum context",
                "context window", "too many tokens", "token limit",
                "prompt is too long", "input is too long");
    }

    private static boolean hasContentFilterSignal(String lower) {
        return containsAny(lower, "content filter", "content_filter", "content policy",
                "content management policy", "safety system", "blocked due to safety");
    }

    private static boolean containsAny(String lower, String... signals) {
        for (var signal : signals) {
            if (lower.contains(signal)) {
                return true;
            }
        }
        return false;
    }
}
