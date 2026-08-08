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
package org.atmosphere.ai.batch;

/**
 * A boundary error on the batch endpoint, carrying the HTTP status plus the
 * fields of the error envelope
 * ({@code {"error":{"message","type","param","code"}}} — the same envelope
 * shape as the OpenAI-compatible serving surface, so clients handle both
 * uniformly). Thrown by the request parser / router and converted to a JSON
 * response by {@link BatchHandler} — malformed input surfaces as a 4xx
 * envelope, never a raw 500 (Correctness Invariant #4, Boundary Safety), and
 * over-capacity submissions surface as a 429 (Invariant #3, Backpressure).
 */
public final class BatchError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Error {@code type} for caller mistakes. */
    public static final String TYPE_INVALID_REQUEST = "invalid_request_error";
    /** Error {@code type} for server-side failures. */
    public static final String TYPE_SERVER_ERROR = "server_error";
    /** Error {@code type} for throttling / capacity rejections. */
    public static final String TYPE_RATE_LIMIT = "rate_limit_error";

    private final int status;
    private final String type;
    private final String code;
    private final String param;

    private BatchError(int status, String type, String code, String param, String message) {
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

    /** Error envelope {@code type} field. */
    public String type() {
        return type;
    }

    /** Error envelope {@code code} field; may be {@code null}. */
    public String code() {
        return code;
    }

    /** Error envelope {@code param} field; may be {@code null}. */
    public String param() {
        return param;
    }

    /** 400 — malformed or invalid request body. */
    public static BatchError invalidRequest(String message) {
        return new BatchError(400, TYPE_INVALID_REQUEST, null, null, message);
    }

    /** 400 — a specific parameter is invalid. */
    public static BatchError invalidRequest(String message, String param) {
        return new BatchError(400, TYPE_INVALID_REQUEST, null, param, message);
    }

    /** 401 — endpoint-level api-key configured and the bearer did not match. */
    public static BatchError unauthorized() {
        return new BatchError(401, TYPE_INVALID_REQUEST, "invalid_api_key", null,
                "Incorrect or missing API key. Send 'Authorization: Bearer <key>'.");
    }

    /** 404 — the submitted agent name is not registered with the batch surface. */
    public static BatchError agentNotFound(String agent) {
        return new BatchError(404, TYPE_INVALID_REQUEST, "agent_not_found", "agent",
                "The agent '" + agent + "' does not exist or is not exposed by this server.");
    }

    /** 404 — no batch job with the given id. */
    public static BatchError jobNotFound(String id) {
        return new BatchError(404, TYPE_INVALID_REQUEST, "job_not_found", null,
                "No batch job with id '" + id + "'.");
    }

    /** 405 — the path exists but not for this method. */
    public static BatchError methodNotAllowed() {
        return new BatchError(405, TYPE_INVALID_REQUEST, "method_not_allowed", null,
                "Method not allowed.");
    }

    /** 409 — the requested transition conflicts with the job's terminal state. */
    public static BatchError conflict(String message) {
        return new BatchError(409, TYPE_INVALID_REQUEST, "conflict", null, message);
    }

    /** 413 — request body exceeded the configured bound. */
    public static BatchError payloadTooLarge(int maxChars) {
        return new BatchError(413, TYPE_INVALID_REQUEST, "payload_too_large", null,
                "Request body exceeds " + maxChars + " characters.");
    }

    /** 415 — request body must be JSON. */
    public static BatchError unsupportedMediaType() {
        return new BatchError(415, TYPE_INVALID_REQUEST, "unsupported_media_type", null,
                "Content-Type must be application/json.");
    }

    /** 429 — a configured job / item bound would be exceeded (Invariant #3). */
    public static BatchError overCapacity(String message) {
        return new BatchError(429, TYPE_RATE_LIMIT, "over_capacity", null, message);
    }

    /** 500 — unexpected server-side failure (details stay in the log). */
    public static BatchError serverError(String message) {
        return new BatchError(500, TYPE_SERVER_ERROR, null, null, message);
    }
}
