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
 * A {@link StreamingSession} decorator that captures timing information and
 * streaming text counts, then reports them to an {@link AiMetrics} implementation
 * when the session completes or errors.
 *
 * <p>This decorator tracks:</p>
 * <ul>
 *   <li>Start time (set when the session is created)</li>
 *   <li>Time of first streaming text (set on the first {@link #send(String)} call)</li>
 *   <li>Streaming text count (number of {@link #send(String)} calls)</li>
 *   <li>End time (set on {@link #complete()} or {@link #error(Throwable)})</li>
 *   <li>Active session lifecycle (via {@link AiMetrics#sessionStarted} / {@link AiMetrics#sessionEnded})</li>
 * </ul>
 *
 * <p>Follows the same wrapping pattern as {@link MemoryCapturingSession} and
 * {@link MetricsCapturingSession}.</p>
 */
public class TracingCapturingSession implements StreamingSession {

    private final StreamingSession delegate;
    private final AiMetrics metrics;
    private final String model;
    private final Instant startTime;
    private volatile Instant firstStreamingTextTime;
    private int streamingTextCount;

    public TracingCapturingSession(StreamingSession delegate, AiMetrics metrics, String model) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.model = model != null ? model : "unknown";
        this.startTime = Instant.now();
        metrics.sessionStarted(this.model);
    }

    @Override
    public java.util.Map<Class<?>, Object> injectables() {
        return delegate.injectables();
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String text) {
        if (firstStreamingTextTime == null) {
            firstStreamingTextTime = Instant.now();
        }
        streamingTextCount++;
        delegate.send(text);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
    }

    @Override
    public void complete() {
        reportMetrics();
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        reportMetrics();
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        metrics.recordError(model, classifyError(t));
        metrics.sessionEnded(model);
        delegate.error(t);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void sendContent(Content content) {
        delegate.sendContent(content);
    }

    @Override
    public void emit(AiEvent event) {
        if (event instanceof AiEvent.TextDelta) {
            if (firstStreamingTextTime == null) {
                firstStreamingTextTime = Instant.now();
            }
            streamingTextCount++;
        }
        delegate.emit(event);
    }

    @Override
    public void stream(String message) {
        delegate.stream(message);
    }

    /**
     * Return the number of streaming text chunks sent so far.
     *
     * @return streaming text count
     */
    int streamingTextCount() {
        return streamingTextCount;
    }

    /**
     * Return the time of the first streaming text, or {@code null} if no text has been sent.
     *
     * @return first streaming text time, or null
     */
    Instant firstStreamingTextTime() {
        return firstStreamingTextTime;
    }

    /**
     * Return the start time of this session.
     *
     * @return session start time
     */
    Instant startTime() {
        return startTime;
    }

    private void reportMetrics() {
        var now = Instant.now();
        var ttft = firstStreamingTextTime != null
                ? Duration.between(startTime, firstStreamingTextTime)
                : Duration.between(startTime, now);
        var total = Duration.between(startTime, now);
        metrics.recordLatency(model, ttft, total);

        if (streamingTextCount > 0) {
            metrics.recordStreamingTextUsage(model, 0, streamingTextCount);
        }
        metrics.sessionEnded(model);
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
