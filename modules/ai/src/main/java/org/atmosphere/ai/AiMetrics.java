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

import java.math.BigDecimal;
import java.time.Duration;

/**
 * SPI for AI observability and cost metering. Implementations emit metrics
 * to monitoring systems (OpenTelemetry, Micrometer, etc.).
 *
 * <p>Called automatically by the framework when wired into the interceptor
 * chain. Aligns with
 * <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/">OpenTelemetry
 * GenAI semantic conventions</a>.</p>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class OtelAiMetrics implements AiMetrics {
 *     private final Meter meter = GlobalOpenTelemetry.getMeter("atmosphere-ai");
 *
 *     @Override
 *     public void recordTokenUsage(String model, int promptTokens, int completionTokens) {
 *         meter.counterBuilder("gen_ai.client.token.usage")
 *              .build().add(promptTokens, Attributes.of(stringKey("gen_ai.response.model"), model));
 *     }
 * }
 * }</pre>
 */
public interface AiMetrics {

    /**
     * Record token usage for a request/response pair.
     *
     * @param model            the model name
     * @param promptTokens     number of tokens in the prompt
     * @param completionTokens number of tokens in the response
     */
    void recordTokenUsage(String model, int promptTokens, int completionTokens);

    /**
     * Record latency metrics.
     *
     * @param model         the model name
     * @param timeToFirstToken time from request to first token
     * @param totalDuration  total request duration
     */
    void recordLatency(String model, Duration timeToFirstToken, Duration totalDuration);

    /**
     * Record estimated cost.
     *
     * @param model the model name
     * @param cost  estimated cost in USD
     */
    void recordCost(String model, BigDecimal cost);

    /**
     * Record a tool call.
     *
     * @param model    the model name
     * @param toolName the tool that was called
     * @param duration time to execute the tool
     * @param success  whether the tool succeeded
     */
    void recordToolCall(String model, String toolName, Duration duration, boolean success);

    /**
     * Record a request error.
     *
     * @param model     the model name
     * @param errorType error classification (e.g., "rate_limit", "timeout", "server_error")
     */
    void recordError(String model, String errorType);

    /** No-op implementation for when metrics are disabled. */
    AiMetrics NOOP = new AiMetrics() {
        @Override
        public void recordTokenUsage(String model, int promptTokens, int completionTokens) { }

        @Override
        public void recordLatency(String model, Duration timeToFirstToken, Duration totalDuration) { }

        @Override
        public void recordCost(String model, BigDecimal cost) { }

        @Override
        public void recordToolCall(String model, String toolName, Duration duration, boolean success) { }

        @Override
        public void recordError(String model, String errorType) { }
    };
}
