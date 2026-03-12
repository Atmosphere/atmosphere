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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MicrometerAiMetrics}.
 */
public class MicrometerAiMetricsTest {

    private SimpleMeterRegistry registry;
    private MicrometerAiMetrics metrics;

    @BeforeEach
    public void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerAiMetrics(registry, "spring-ai");
    }

    @Test
    public void testPromptCounterIncrements() {
        metrics.recordLatency("gpt-4", Duration.ofMillis(200), Duration.ofMillis(1000));
        metrics.recordLatency("gpt-4", Duration.ofMillis(150), Duration.ofMillis(800));

        var counter = registry.find("atmosphere.ai.prompts.total")
                .tag("model", "gpt-4")
                .tag("provider", "spring-ai")
                .counter();
        assertNotNull(counter);
        assertEquals(2.0, counter.count());
    }

    @Test
    public void testStreamingTextCounterIncrements() {
        metrics.recordStreamingTextUsage("gpt-4", 10, 50);
        metrics.recordStreamingTextUsage("gpt-4", 5, 25);

        var counter = registry.find("atmosphere.ai.streaming_texts.total")
                .tag("model", "gpt-4")
                .tag("provider", "spring-ai")
                .counter();
        assertNotNull(counter);
        assertEquals(90.0, counter.count());
    }

    @Test
    public void testErrorCounterIncrements() {
        metrics.recordError("gpt-4", "rate_limit");
        metrics.recordError("gpt-4", "timeout");
        metrics.recordError("gpt-4", "rate_limit");

        var rateLimitCounter = registry.find("atmosphere.ai.errors.total")
                .tag("model", "gpt-4")
                .tag("error_type", "rate_limit")
                .counter();
        assertNotNull(rateLimitCounter);
        assertEquals(2.0, rateLimitCounter.count());

        var timeoutCounter = registry.find("atmosphere.ai.errors.total")
                .tag("model", "gpt-4")
                .tag("error_type", "timeout")
                .counter();
        assertNotNull(timeoutCounter);
        assertEquals(1.0, timeoutCounter.count());
    }

    @Test
    public void testPromptDurationTimerRecording() {
        metrics.recordLatency("gpt-4", Duration.ofMillis(200), Duration.ofMillis(1000));

        var timer = registry.find("atmosphere.ai.prompt.duration")
                .tag("model", "gpt-4")
                .tag("provider", "spring-ai")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(200.0, timer.totalTime(TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    public void testResponseDurationTimerRecording() {
        metrics.recordLatency("gpt-4", Duration.ofMillis(200), Duration.ofMillis(1000));

        var timer = registry.find("atmosphere.ai.response.duration")
                .tag("model", "gpt-4")
                .tag("provider", "spring-ai")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(1000.0, timer.totalTime(TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    public void testActiveSessionGauge() {
        assertEquals(0, metrics.activeSessionCount());

        metrics.sessionStarted("gpt-4");
        assertEquals(1, metrics.activeSessionCount());

        metrics.sessionStarted("gpt-4");
        assertEquals(2, metrics.activeSessionCount());

        metrics.sessionEnded("gpt-4");
        assertEquals(1, metrics.activeSessionCount());

        metrics.sessionEnded("gpt-4");
        assertEquals(0, metrics.activeSessionCount());
    }

    @Test
    public void testActiveSessionGaugeRegisteredInRegistry() {
        metrics.sessionStarted("gpt-4");
        metrics.sessionStarted("gpt-4");

        var gauge = registry.find("atmosphere.ai.active_sessions")
                .tag("provider", "spring-ai")
                .gauge();
        assertNotNull(gauge);
        assertEquals(2.0, gauge.value());
    }

    @Test
    public void testMetricTagsContainModelAndProvider() {
        metrics.recordLatency("claude-3", Duration.ofMillis(100), Duration.ofMillis(500));

        var counter = registry.find("atmosphere.ai.prompts.total")
                .tag("model", "claude-3")
                .tag("provider", "spring-ai")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());

        // Should not exist with wrong model tag
        var wrongModel = registry.find("atmosphere.ai.prompts.total")
                .tag("model", "gpt-4")
                .counter();
        assertNull(wrongModel);
    }

    @Test
    public void testNullModelDefaultsToUnknown() {
        metrics.recordLatency(null, Duration.ofMillis(100), Duration.ofMillis(500));

        var counter = registry.find("atmosphere.ai.prompts.total")
                .tag("model", "unknown")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    public void testNullProviderDefaultsToUnknown() {
        var metricsWithNull = new MicrometerAiMetrics(registry, null);
        metricsWithNull.recordError("gpt-4", "timeout");

        var counter = registry.find("atmosphere.ai.errors.total")
                .tag("provider", "unknown")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    public void testToolCallRecording() {
        metrics.recordToolCall("gpt-4", "search", Duration.ofMillis(350), true);
        metrics.recordToolCall("gpt-4", "search", Duration.ofMillis(150), false);

        var successTimer = registry.find("atmosphere.ai.tool.duration")
                .tag("model", "gpt-4")
                .tag("tool", "search")
                .tag("success", "true")
                .timer();
        assertNotNull(successTimer);
        assertEquals(1, successTimer.count());

        var failTimer = registry.find("atmosphere.ai.tool.duration")
                .tag("success", "false")
                .timer();
        assertNotNull(failTimer);
        assertEquals(1, failTimer.count());
    }

    @Test
    public void testCostRecording() {
        metrics.recordCost("gpt-4", BigDecimal.valueOf(0.05));

        var summary = registry.find("atmosphere.ai.cost")
                .tag("model", "gpt-4")
                .summary();
        assertNotNull(summary);
        assertEquals(1, summary.count());
        assertEquals(0.05, summary.totalAmount(), 0.001);
    }
}
