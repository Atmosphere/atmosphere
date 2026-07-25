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
package org.atmosphere.ai.openai;

/**
 * A boundary error on the OpenAI-compatible serving endpoint, carrying the
 * HTTP status plus the fields of the OpenAI error envelope
 * ({@code {"error":{"message","type","param","code"}}}). Thrown by the
 * request parser / model resolver and converted to a JSON response (or an
 * in-stream SSE error frame when the response is already committed) by
 * {@link OpenAiChatHandler} — malformed input surfaces as a 4xx envelope,
 * never a raw 500 (Correctness Invariant #4, Boundary Safety).
 */
public final class OpenAiError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** OpenAI error {@code type} for caller mistakes. */
    public static final String TYPE_INVALID_REQUEST = "invalid_request_error";
    /** OpenAI error {@code type} for server-side failures. */
    public static final String TYPE_SERVER_ERROR = "server_error";
    /** OpenAI error {@code type} for throttling / budget trips. */
    public static final String TYPE_RATE_LIMIT = "rate_limit_error";

    private final int status;
    private final String type;
    private final String code;
    private final String param;

    private OpenAiError(int status, String type, String code, String param, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.param = param;
    }

    /** HTTP status to respond with. */
    public int status() {
        return status;
    }

    /** OpenAI error envelope {@code type} field. */
    public String type() {
        return type;
    }

    /** OpenAI error envelope {@code code} field; may be {@code null}. */
    public String code() {
        return code;
    }

    /** OpenAI error envelope {@code param} field; may be {@code null}. */
    public String param() {
        return param;
    }

    /** 400 — malformed or invalid request body. */
    public static OpenAiError invalidRequest(String message) {
        return new OpenAiError(400, TYPE_INVALID_REQUEST, null, null, message);
    }

    /** 400 — a specific parameter is invalid. */
    public static OpenAiError invalidRequest(String message, String param) {
        return new OpenAiError(400, TYPE_INVALID_REQUEST, null, param, message);
    }

    /**
     * 400 — a parameter this endpoint deliberately does not support
     * (tools / functions passthrough is out of scope; rejecting is honest,
     * half-supporting is not).
     */
    public static OpenAiError unsupportedParameter(String param, String message) {
        return new OpenAiError(400, TYPE_INVALID_REQUEST, "unsupported_parameter", param, message);
    }

    /** 401 — endpoint-level api-key configured and the bearer did not match. */
    public static OpenAiError unauthorized() {
        return new OpenAiError(401, TYPE_INVALID_REQUEST, "invalid_api_key", null,
                "Incorrect or missing API key. Send 'Authorization: Bearer <key>'.");
    }

    /** 403 — the request was admitted to the pipeline but denied by governance. */
    public static OpenAiError requestBlocked(String message) {
        return new OpenAiError(403, TYPE_INVALID_REQUEST, "request_blocked", null, message);
    }

    /** 404 — the requested model does not map to a registered agent. */
    public static OpenAiError modelNotFound(String model) {
        return new OpenAiError(404, TYPE_INVALID_REQUEST, "model_not_found", "model",
                "The model '" + model + "' does not exist or is not exposed by this server.");
    }

    /** 405 — only POST (chat/completions) and GET (models) are served. */
    public static OpenAiError methodNotAllowed() {
        return new OpenAiError(405, TYPE_INVALID_REQUEST, "method_not_allowed", null,
                "Method not allowed.");
    }

    /** 413 — request body exceeded the configured bound. */
    public static OpenAiError payloadTooLarge(int maxChars) {
        return new OpenAiError(413, TYPE_INVALID_REQUEST, "payload_too_large", null,
                "Request body exceeds " + maxChars + " characters.");
    }

    /** 415 — request body must be JSON. */
    public static OpenAiError unsupportedMediaType() {
        return new OpenAiError(415, TYPE_INVALID_REQUEST, "unsupported_media_type", null,
                "Content-Type must be application/json.");
    }

    /** 429 — an {@code AiBudget} limit tripped mid-completion. */
    public static OpenAiError budgetExceeded(String message) {
        return new OpenAiError(429, TYPE_RATE_LIMIT, "budget_exceeded", null, message);
    }

    /** 500 — unexpected server-side failure (details stay in the log). */
    public static OpenAiError serverError(String message) {
        return new OpenAiError(500, TYPE_SERVER_ERROR, null, null, message);
    }

    /** 503 — the endpoint or the mapped agent cannot serve right now. */
    public static OpenAiError unavailable(String message) {
        return new OpenAiError(503, TYPE_SERVER_ERROR, "service_unavailable", null, message);
    }
}
