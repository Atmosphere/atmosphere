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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AiMetrics} implementation backed by <a href="https://micrometer.io">Micrometer</a>.
 *
 * <p>Records the following metrics:</p>
 * <ul>
 *   <li>{@code atmosphere.ai.prompts.total} &mdash; counter of prompt requests</li>
 *   <li>{@code atmosphere.ai.streaming_texts.total} &mdash; counter of streaming text chunks</li>
 *   <li>{@code atmosphere.ai.errors.total} &mdash; counter of errors</li>
 *   <li>{@code atmosphere.ai.prompt.duration} &mdash; time from prompt to first streaming text</li>
 *   <li>{@code atmosphere.ai.response.duration} &mdash; full response wall-clock time</li>
 *   <li>{@code atmosphere.ai.active_sessions} &mdash; gauge of currently active streaming sessions</li>
 * </ul>
 *
 * <p>All metrics are tagged with {@code model} and {@code provider}. Because
 * Micrometer is an optional dependency, this class is only usable when
 * {@code micrometer-core} is on the classpath.</p>
 */
public final class MicrometerAiMetrics implements AiMetrics {

    private final MeterRegistry registry;
    private final String provider;
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    /**
     * Create a new instance bound to the given registry and provider name.
     *
     * @param registry the Micrometer meter registry
     * @param provider the AI provider tag value (e.g., "spring-ai", "langchain4j", "builtin", "adk")
     */
    public MicrometerAiMetrics(MeterRegistry registry, String provider) {
        this.registry = registry;
        this.provider = provider != null ? provider : "unknown";
        registry.gauge("atmosphere.ai.active_sessions", Tags.of("provider", this.provider),
                activeSessions, AtomicInteger::doubleValue);
    }

    @Override
    public void recordStreamingTextUsage(String model, int promptStreamingTexts, int completionStreamingTexts) {
        var tags = tags(model);
        counter("atmosphere.ai.streaming_texts.total", tags)
                .increment(promptStreamingTexts + completionStreamingTexts);
    }

    @Override
    public void recordLatency(String model, Duration timeToFirstStreamingText, Duration totalDuration) {
        var tags = tags(model);
        counter("atmosphere.ai.prompts.total", tags).increment();
        timer("atmosphere.ai.prompt.duration", tags).record(timeToFirstStreamingText);
        timer("atmosphere.ai.response.duration", tags).record(totalDuration);
    }

    @Override
    public void recordCost(String model, BigDecimal cost) {
        var tags = tags(model);
        registry.summary("atmosphere.ai.cost", tags).record(cost.doubleValue());
    }

    @Override
    public void recordToolCall(String model, String toolName, Duration duration, boolean success) {
        var tags = tags(model)
                .and("tool", toolName)
                .and("success", String.valueOf(success));
        timer("atmosphere.ai.tool.duration", tags).record(duration);
    }

    @Override
    public void recordError(String model, String errorType) {
        var tags = tags(model).and("error_type", errorType);
        counter("atmosphere.ai.errors.total", tags).increment();
    }

    @Override
    public void sessionStarted(String model) {
        activeSessions.incrementAndGet();
    }

    @Override
    public void sessionEnded(String model) {
        activeSessions.decrementAndGet();
    }

    /**
     * Return the current number of active sessions. Useful for testing.
     *
     * @return active session count
     */
    int activeSessionCount() {
        return activeSessions.get();
    }

    private Tags tags(String model) {
        return Tags.of("model", model != null ? model : "unknown", "provider", provider);
    }

    private Counter counter(String name, Tags tags) {
        return registry.counter(name, tags);
    }

    private Timer timer(String name, Tags tags) {
        return registry.timer(name, tags);
    }
}
