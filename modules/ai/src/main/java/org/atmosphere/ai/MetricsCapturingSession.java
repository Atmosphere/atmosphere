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
import java.time.Instant;

/**
 * A {@link StreamingSession} decorator that captures timing and streaming text usage
 * metadata and reports them to an {@link AiMetrics} implementation.
 *
 * <p>Follows the same wrapping pattern as {@link MemoryCapturingSession}.</p>
 */
class MetricsCapturingSession extends DelegatingStreamingSession {

    private final AiMetrics metrics;
    private final String model;
    private final String providerName;
    private final Instant startTime;
    private volatile Instant firstStreamingTextTime;
    private int promptStreamingTexts;
    private int completionStreamingTexts;

    MetricsCapturingSession(StreamingSession delegate, AiMetrics metrics, String model) {
        this(delegate, metrics, model, null);
    }

    MetricsCapturingSession(StreamingSession delegate, AiMetrics metrics, String model, String providerName) {
        super(delegate);
        this.metrics = metrics;
        this.model = model != null ? model : "unknown";
        this.providerName = providerName;
        this.startTime = Instant.now();
    }

    @Override
    public void send(String text) {
        if (firstStreamingTextTime == null) {
            firstStreamingTextTime = Instant.now();
        }
        delegate.send(text);
    }

    @Override
    public void sendContent(Content content) {
        // Any content — text or binary — is a signal that the first token
        // has arrived. Stamp the TTFT and forward to the delegate so the
        // binary frame reaches the leaf writer.
        if (firstStreamingTextTime == null) {
            firstStreamingTextTime = Instant.now();
        }
        delegate.sendContent(content);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        if ("usage.promptStreamingTexts".equals(key) && value instanceof Number n) {
            promptStreamingTexts = n.intValue();
        } else if ("usage.completionStreamingTexts".equals(key) && value instanceof Number n) {
            completionStreamingTexts = n.intValue();
        }
        delegate.sendMetadata(key, value);
    }

    @Override
    public void usage(TokenUsage usage) {
        // Tap the authoritative provider token counts here rather than from the
        // re-emitted ai.tokens.* metadata: DelegatingStreamingSession forwards
        // usage() straight to the delegate, so the metadata fan-out never
        // re-enters this decorator's sendMetadata. The typed signal is the only
        // reliable capture point for gen_ai.client.token.usage.
        if (usage != null && usage.hasCounts()) {
            if (providerName != null) {
                // Provider-aware path: thread the resolved runtime name into the
                // GenAI convention's gen_ai.provider.name (Runtime Truth) and the
                // provider-reported response model into gen_ai.response.model.
                metrics.recordTokenUsage(providerName, model, usage.model(),
                        usage.input(), usage.output(), usage.total());
            } else {
                // Legacy construction (no resolved provider): keep the exact
                // 4-arg call so behaviour stays byte-identical.
                metrics.recordTokenUsage(model, usage.input(), usage.output(), usage.total());
            }
            // Additive: tag the live OTel span (the AtmosphereTracing SERVER
            // span) with the gen_ai.* attributes (OpenTelemetry's still-experimental
            // GenAI semconv). Self-guards on hasCounts() + a valid current span;
            // no-op when OTel is absent.
            GenAiTracer.record(usage, model, providerName);
        }
        delegate.usage(usage);
    }

    @Override
    public void complete() {
        recordMetrics();
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        recordMetrics();
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        metrics.recordError(model, classifyError(t));
        delegate.error(t);
    }

    @Override
    public void emit(AiEvent event) {
        if (event instanceof AiEvent.TextDelta) {
            if (firstStreamingTextTime == null) {
                firstStreamingTextTime = Instant.now();
            }
        }
        delegate.emit(event);
    }

    private void recordMetrics() {
        var now = Instant.now();
        var ttft = firstStreamingTextTime != null
                ? Duration.between(startTime, firstStreamingTextTime)
                : Duration.between(startTime, now);
        var total = Duration.between(startTime, now);
        metrics.recordLatency(model, ttft, total);

        if (promptStreamingTexts > 0 || completionStreamingTexts > 0) {
            metrics.recordStreamingTextUsage(model, promptStreamingTexts, completionStreamingTexts);
        }
    }

    private static String classifyError(Throwable t) {
        var msg = t.getMessage();
        if (msg == null) {
            return "unknown";
        }
        var lower = msg.toLowerCase();
        if (lower.contains("timeout")) {
            return "timeout";
        }
        if (lower.contains("429") || lower.contains("rate")) {
            return "rate_limit";
        }
        if (lower.contains("500") || lower.contains("502") || lower.contains("503")) {
            return "server_error";
        }
        return "unknown";
    }
}
