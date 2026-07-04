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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
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

    @Test
    public void testInputAssemblyEmitsTokensAndCharsTaggedByStage() {
        metrics.recordInputAssembly("gpt-4", InputAssemblyTelemetry.STAGE_SYSTEM, 25, 100);
        metrics.recordInputAssembly("gpt-4", InputAssemblyTelemetry.STAGE_TOOL_SCHEMA, 200, 800);
        // Two consecutive turns should accumulate on the same stage tag
        metrics.recordInputAssembly("gpt-4", InputAssemblyTelemetry.STAGE_SYSTEM, 25, 100);

        var systemTokens = registry.find("atmosphere.ai.input.tokens")
                .tag("model", "gpt-4")
                .tag("stage", InputAssemblyTelemetry.STAGE_SYSTEM)
                .tag("provider", "spring-ai")
                .counter();
        assertNotNull(systemTokens, "tokens counter for system stage missing");
        assertEquals(50.0, systemTokens.count(),
                "two turns of 25 tokens should accumulate to 50");

        var systemChars = registry.find("atmosphere.ai.input.chars")
                .tag("model", "gpt-4")
                .tag("stage", InputAssemblyTelemetry.STAGE_SYSTEM)
                .counter();
        assertNotNull(systemChars);
        assertEquals(200.0, systemChars.count());

        var toolTokens = registry.find("atmosphere.ai.input.tokens")
                .tag("stage", InputAssemblyTelemetry.STAGE_TOOL_SCHEMA)
                .counter();
        assertNotNull(toolTokens);
        assertEquals(200.0, toolTokens.count());
    }

    @Test
    public void testInputAssemblyTagsUnknownStageWhenNullProvided() {
        metrics.recordInputAssembly("gpt-4", null, 5, 20);

        var counter = registry.find("atmosphere.ai.input.tokens")
                .tag("stage", "unknown")
                .counter();
        assertNotNull(counter);
        assertEquals(5.0, counter.count());
    }

    @Test
    public void testTokenUsageEmitsAtmosphereCounterByType() {
        metrics.recordTokenUsage("gpt-4", 120, 80, 200);
        metrics.recordTokenUsage("gpt-4", 30, 20, 50);

        var input = registry.find("atmosphere.ai.tokens")
                .tag("model", "gpt-4")
                .tag("provider", "spring-ai")
                .tag("type", "input")
                .counter();
        assertNotNull(input, "input token counter missing");
        assertEquals(150.0, input.count(), "two turns of input tokens should accumulate");

        var output = registry.find("atmosphere.ai.tokens")
                .tag("type", "output")
                .counter();
        assertNotNull(output);
        assertEquals(100.0, output.count());
    }

    @Test
    public void testTokenUsageDualEmitsGenAiConventionInstrument() {
        metrics.recordTokenUsage("gpt-4", 120, 80, 200);

        var input = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.provider.name", "spring-ai")
                .tag("gen_ai.request.model", "gpt-4")
                .tag("gen_ai.token.type", "input")
                .summary();
        assertNotNull(input, "gen_ai.client.token.usage input series missing");
        assertEquals(1, input.count());
        assertEquals(120.0, input.totalAmount(), 0.001);

        var output = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.token.type", "output")
                .summary();
        assertNotNull(output);
        assertEquals(80.0, output.totalAmount(), 0.001);
    }

    @Test
    public void testTokenUsageSkipsZeroCounts() {
        metrics.recordTokenUsage("gpt-4", 0, 0, 0);

        assertNull(registry.find("atmosphere.ai.tokens").counter());
        assertNull(registry.find("gen_ai.client.token.usage").summary());
    }

    @Test
    public void tokenUsageEmitsRealProviderNotHardcodedAtmosphere() {
        // The no-arg ctor hardcodes provider="atmosphere"; the provider-aware
        // recordTokenUsage overload must tag gen_ai.provider.name with the
        // RESOLVED runtime name instead. Pin the Runtime-Truth fix: build a
        // metrics instance whose instance provider is the bogus "atmosphere"
        // and assert the GenAI series carries the real runtime name.
        var atmosphereDefault = new MicrometerAiMetrics(registry, "atmosphere");
        atmosphereDefault.recordTokenUsage("google-adk", "gemini-2.0", "gemini-2.0-flash", 120, 80, 200);

        // GenAI provider tag is the resolved runtime, NOT "atmosphere".
        var realProvider = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "google-adk")
                .tag("gen_ai.request.model", "gemini-2.0")
                .tag("gen_ai.token.type", "input")
                .summary();
        assertNotNull(realProvider, "gen_ai series must carry the resolved runtime provider");
        assertEquals(120.0, realProvider.totalAmount(), 0.001);

        // There must be NO gen_ai token series tagged with the hardcoded
        // "atmosphere" provider — that is exactly the bug being fixed.
        var hardcoded = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "atmosphere")
                .summary();
        assertNull(hardcoded, "gen_ai.provider.name must not be the hardcoded 'atmosphere'");
    }

    @Test
    public void tokenUsageEmitsResponseModelTag() {
        metrics.recordTokenUsage("google-adk", "gemini-2.0", "gemini-2.0-flash-001", 120, 80, 200);

        var withResponseModel = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.response.model", "gemini-2.0-flash-001")
                .tag("gen_ai.token.type", "input")
                .summary();
        assertNotNull(withResponseModel, "gen_ai.response.model tag must be emitted when reported");
        assertEquals(120.0, withResponseModel.totalAmount(), 0.001);
    }

    @Test
    public void tokenUsageOmitsResponseModelTagWhenNull() {
        // Runtime did not report a model — no gen_ai.response.model tag.
        metrics.recordTokenUsage("google-adk", "gemini-2.0", null, 120, 80, 200);

        var input = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "google-adk")
                .tag("gen_ai.token.type", "input")
                .summary();
        assertNotNull(input);
        // No series should carry a response-model tag.
        var anyResponseModel = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.response.model", "gemini-2.0")
                .summary();
        assertNull(anyResponseModel, "response model tag must be absent when the runtime reported none");
    }

    @Test
    public void tokenUsageFourArgKeepsInstanceProviderForGenAiSeries() {
        // Source-compat: the legacy 4-arg form defaults gen_ai.provider.name to
        // the instance provider ("spring-ai" here) — byte-identical behaviour.
        metrics.recordTokenUsage("gpt-4", 120, 80, 200);

        var input = registry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "spring-ai")
                .tag("gen_ai.request.model", "gpt-4")
                .tag("gen_ai.token.type", "input")
                .summary();
        assertNotNull(input, "4-arg form must default gen_ai.provider.name to the instance provider");
        assertEquals(120.0, input.totalAmount(), 0.001);
    }

    @Test
    public void testLatencyDualEmitsGenAiOperationDuration() {
        metrics.recordLatency("gpt-4", Duration.ofMillis(200), Duration.ofMillis(1000));

        var timer = registry.find("gen_ai.client.operation.duration")
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.provider.name", "spring-ai")
                .tag("gen_ai.request.model", "gpt-4")
                .timer();
        assertNotNull(timer, "gen_ai.client.operation.duration missing");
        assertEquals(1, timer.count());
        assertEquals(1000.0, timer.totalTime(TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    public void testGenAiDualEmitConflictDoesNotBreakRecording() {
        // Simulate a co-resident instrumentation (e.g. quarkus-langchain4j) that
        // already owns the OTel metric name with a different tag-key set, so the
        // registry rejects Atmosphere's registration of that name. Prometheus
        // does exactly this on a tag-key mismatch — the paid-nightly Gemini
        // disconnect-recovery leg relayed it as an in-stream error frame.
        // Observability must never break the request path (Correctness
        // Invariant #2): recordLatency must swallow it and keep the
        // atmosphere.ai.* series (private namespace, cannot collide).
        var strictRegistry = new SimpleMeterRegistry() {
            @Override
            protected Timer newTimer(Meter.Id id, DistributionStatisticConfig config,
                                     PauseDetector pauseDetector) {
                if ("gen_ai.client.operation.duration".equals(id.getName())) {
                    throw new IllegalArgumentException("simulated meter conflict: "
                            + "gen_ai.client.operation.duration already registered with different tag keys");
                }
                return super.newTimer(id, config, pauseDetector);
            }
        };
        var guardedMetrics = new MicrometerAiMetrics(strictRegistry, "spring-ai");

        assertDoesNotThrow(
                () -> guardedMetrics.recordLatency("gpt-4", Duration.ofMillis(200), Duration.ofMillis(1000)),
                "a co-resident OTel meter conflict must not propagate to the request path");

        var responseTimer = strictRegistry.find("atmosphere.ai.response.duration")
                .tag("model", "gpt-4").tag("provider", "spring-ai").timer();
        assertNotNull(responseTimer,
                "atmosphere.ai.response.duration must still record after an OTel conflict");
        assertEquals(1, responseTimer.count());

        // Second call stays silent too — the guard latches after the first conflict.
        assertDoesNotThrow(
                () -> guardedMetrics.recordLatency("gpt-4", Duration.ofMillis(150), Duration.ofMillis(800)));
        assertEquals(2, strictRegistry.find("atmosphere.ai.response.duration")
                .tag("model", "gpt-4").tag("provider", "spring-ai").timer().count());
    }
}
