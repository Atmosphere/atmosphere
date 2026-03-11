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
 * A {@link StreamingSession} decorator that captures timing and token usage
 * metadata and reports them to an {@link AiMetrics} implementation.
 *
 * <p>Follows the same wrapping pattern as {@link MemoryCapturingSession}.</p>
 */
class MetricsCapturingSession implements StreamingSession {

    private final StreamingSession delegate;
    private final AiMetrics metrics;
    private final String model;
    private final Instant startTime;
    private volatile Instant firstTokenTime;
    private int promptTokens;
    private int completionTokens;

    MetricsCapturingSession(StreamingSession delegate, AiMetrics metrics, String model) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.model = model != null ? model : "unknown";
        this.startTime = Instant.now();
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String token) {
        if (firstTokenTime == null) {
            firstTokenTime = Instant.now();
        }
        delegate.send(token);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        if ("usage.promptStreamingTexts".equals(key) && value instanceof Number n) {
            promptTokens = n.intValue();
        } else if ("usage.completionStreamingTexts".equals(key) && value instanceof Number n) {
            completionTokens = n.intValue();
        }
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
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
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void stream(String message) {
        delegate.stream(message);
    }

    private void recordMetrics() {
        var now = Instant.now();
        var ttft = firstTokenTime != null
                ? Duration.between(startTime, firstTokenTime)
                : Duration.between(startTime, now);
        var total = Duration.between(startTime, now);
        metrics.recordLatency(model, ttft, total);

        if (promptTokens > 0 || completionTokens > 0) {
            metrics.recordTokenUsage(model, promptTokens, completionTokens);
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
