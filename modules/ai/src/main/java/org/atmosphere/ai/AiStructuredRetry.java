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

import java.util.Map;

/**
 * Configuration for the self-healing structured-output reprompt loop. When a
 * structured-output request is in play and {@code maxRetries > 0}, a schema /
 * parse failure does not fail the run — instead the runtime is re-invoked with
 * the validation error appended as feedback, up to {@code maxRetries} extra
 * attempts. The loop <strong>fails closed</strong> at the limit: an unparseable
 * final attempt surfaces a {@link StructuredOutputParser.StructuredOutputException}
 * on the session rather than silently emitting nothing.
 *
 * <p>This is distinct from {@link RetryPolicy}, which is exponential backoff for
 * transient <em>transport</em> errors (HTTP 429/5xx). {@code AiStructuredRetry}
 * is a <em>semantic</em> reprompt: the model produced a well-formed HTTP
 * response whose content failed schema validation, and we ask it to correct
 * itself. The two compose — transport retries happen inside each attempt, schema
 * retries wrap the attempts.</p>
 *
 * <p>Threaded uniformly across the {@code @AiEndpoint} websocket path and the
 * resource-free {@link AiPipeline} channel-bridge path via the
 * {@link #METADATA_KEY} request-metadata key, so the self-healing behavior is
 * identical regardless of entry point (Mode Parity).</p>
 *
 * @param maxRetries number of <em>additional</em> reprompt attempts after the
 *                   first; clamped to {@code >= 0}. {@code 0} disables the loop.
 */
public record AiStructuredRetry(int maxRetries) {

    /** Request-metadata key carrying a per-request {@link AiStructuredRetry}. */
    public static final String METADATA_KEY = "ai.structured.retry";

    public AiStructuredRetry {
        if (maxRetries < 0) {
            maxRetries = 0;
        }
    }

    /** @return {@code true} when at least one reprompt attempt is allowed. */
    public boolean enabled() {
        return maxRetries > 0;
    }

    /** Convenience factory. */
    public static AiStructuredRetry of(int maxRetries) {
        return new AiStructuredRetry(maxRetries);
    }

    /**
     * Read an {@link AiStructuredRetry} from request metadata.
     *
     * @return the configured retry, or {@code null} when absent
     */
    public static AiStructuredRetry from(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.get(METADATA_KEY) instanceof AiStructuredRetry r ? r : null;
    }

    /** Read an {@link AiStructuredRetry} from an execution context's metadata. */
    public static AiStructuredRetry from(AgentExecutionContext context) {
        return context == null ? null : from(context.metadata());
    }
}
