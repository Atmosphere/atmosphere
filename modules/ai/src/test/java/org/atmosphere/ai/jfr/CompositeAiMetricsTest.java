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
package org.atmosphere.ai.jfr;

import org.atmosphere.ai.AiMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for {@link CompositeAiMetrics} fan-out of the
 * provider-aware and cache-aware {@code recordTokenUsage} overloads.
 *
 * <p>The pipeline always routes metrics through this composite
 * ({@link CompositeAiMetrics#withJfr}), so a missing override here silently
 * downgrades every delegate to the 4-arg form — dropping the resolved
 * provider, the response model, and the cached-input count on the production
 * path while all unit tests against the bare delegate keep passing.</p>
 */
class CompositeAiMetricsTest {

    /** Records which overload arrived and with what arguments. */
    private static final class RecordingMetrics implements AiMetrics {
        final List<String> calls = new ArrayList<>();

        @Override
        public void recordStreamingTextUsage(String model, int promptStreamingTexts, int completionStreamingTexts) { }

        @Override
        public void recordLatency(String model, Duration ttft, Duration total) { }

        @Override
        public void recordCost(String model, BigDecimal cost) { }

        @Override
        public void recordToolCall(String model, String toolName, Duration duration, boolean success) { }

        @Override
        public void recordError(String model, String errorType) { }

        @Override
        public void recordTokenUsage(String model, long input, long output, long total) {
            calls.add("4-arg:" + model + ":" + input + ":" + output + ":" + total);
        }

        @Override
        public void recordTokenUsage(String provider, String requestModel, String responseModel,
                                     long input, long output, long total) {
            calls.add("6-arg:" + provider + ":" + requestModel + ":" + responseModel
                    + ":" + input + ":" + output + ":" + total);
        }

        @Override
        public void recordTokenUsage(String provider, String requestModel, String responseModel,
                                     long input, long output, long cachedInput, long total) {
            calls.add("7-arg:" + provider + ":" + requestModel + ":" + responseModel
                    + ":" + input + ":" + output + ":" + cachedInput + ":" + total);
        }
    }

    @Test
    void providerAwareOverloadFansOutVerbatim() {
        var delegate = new RecordingMetrics();
        AiMetrics composite = new CompositeAiMetrics(delegate, new JfrAiMetrics());

        composite.recordTokenUsage("google-adk", "gemini-2.0", "gemini-2.0-flash", 120L, 80L, 200L);

        assertEquals(List.of("6-arg:google-adk:gemini-2.0:gemini-2.0-flash:120:80:200"),
                delegate.calls,
                "the composite must forward the provider-aware overload verbatim, "
                        + "not downgrade it to the 4-arg form before fan-out");
    }

    @Test
    void cacheAwareOverloadFansOutVerbatim() {
        var delegate = new RecordingMetrics();
        AiMetrics composite = CompositeAiMetrics.withJfr(delegate);

        composite.recordTokenUsage("built-in", "gpt-4o", "gpt-4o-2024", 120L, 80L, 90L, 200L);

        assertEquals(List.of("7-arg:built-in:gpt-4o:gpt-4o-2024:120:80:90:200"),
                delegate.calls,
                "the composite must forward the cache-aware overload verbatim so "
                        + "each delegate applies its own default-method downgrade");
    }

    /**
     * Pre-cached-series implementor: overrides the 6-arg overload only, so
     * the 7-arg call must reach it through the interface default's
     * per-delegate downgrade.
     */
    private static final class SixArgOnlyMetrics implements AiMetrics {
        final List<String> calls = new ArrayList<>();

        @Override
        public void recordStreamingTextUsage(String model, int promptStreamingTexts, int completionStreamingTexts) { }

        @Override
        public void recordLatency(String model, Duration ttft, Duration total) { }

        @Override
        public void recordCost(String model, BigDecimal cost) { }

        @Override
        public void recordToolCall(String model, String toolName, Duration duration, boolean success) { }

        @Override
        public void recordError(String model, String errorType) { }

        @Override
        public void recordTokenUsage(String provider, String requestModel, String responseModel,
                                     long input, long output, long total) {
            calls.add("6-arg:" + provider + ":" + requestModel + ":" + responseModel
                    + ":" + input + ":" + output + ":" + total);
        }
    }

    @Test
    void delegateWithoutCacheAwareOverrideStillReceivesProviderData() {
        // A delegate that only implements the 6-arg overload must still see
        // provider + response model when the composite receives the 7-arg
        // call — the interface default downgrades per-delegate, after fan-out.
        var sixArgOnly = new SixArgOnlyMetrics();
        AiMetrics composite = new CompositeAiMetrics(sixArgOnly);

        composite.recordTokenUsage("anthropic", "claude-4", null, 50L, 25L, 10L, 75L);

        assertEquals(List.of("6-arg:anthropic:claude-4:null:50:25:75"), sixArgOnly.calls,
                "per-delegate default must preserve provider data");
    }
}
