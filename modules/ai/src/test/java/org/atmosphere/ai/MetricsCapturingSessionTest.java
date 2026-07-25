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

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsCapturingSessionTest {

    private StreamingSession mockDelegate() {
        return Mockito.mock(StreamingSession.class);
    }

    private AiMetrics mockMetrics() {
        return Mockito.mock(AiMetrics.class);
    }

    @Test
    void sessionIdDelegatesToWrapped() {
        var delegate = mockDelegate();
        when(delegate.sessionId()).thenReturn("sess-1");
        var session = new MetricsCapturingSession(delegate, mockMetrics(), "gpt-4");
        assert session.sessionId().equals("sess-1");
    }

    @Test
    void sendForwardsToDelegateAndTracksFirstToken() {
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, mockMetrics(), "gpt-4");
        session.send("hello");
        verify(delegate).send("hello");
    }

    @Test
    void completeRecordsLatencyMetrics() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.send("text");
        session.complete();
        verify(metrics).recordLatency(eq("gpt-4"), any(), any());
        verify(delegate).complete();
    }

    @Test
    void completeWithSummaryRecordsLatencyMetrics() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "claude");
        session.send("data");
        session.complete("done");
        verify(metrics).recordLatency(eq("claude"), any(), any());
        verify(delegate).complete("done");
    }

    @Test
    void errorRecordsErrorMetrics() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        var ex = new RuntimeException("timeout error");
        session.error(ex);
        verify(metrics).recordError("gpt-4", "timeout");
        verify(delegate).error(ex);
    }

    @Test
    void errorClassifiesRateLimit() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException("429 rate limit exceeded"));
        verify(metrics).recordError("gpt-4", "rate_limit");
    }

    @Test
    void errorClassifiesServerError() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException("HTTP 500 internal server error"));
        verify(metrics).recordError("gpt-4", "server_error");
    }

    @Test
    void errorClassifiesUnavailable() {
        // 502/503 report the retry vocabulary's "unavailable" (previously
        // lumped into server_error) so the metric label matches what the
        // HTTP retry loops classify for the same status.
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException("HTTP 503 service unavailable"));
        verify(metrics).recordError("gpt-4", "unavailable");
    }

    @Test
    void errorReportsTypedProviderExceptionErrorType() {
        // A typed AiProviderException short-circuits the message heuristics:
        // metrics report exactly the classification retry/routing consumed.
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new AiProviderException.ContextLengthExceeded(
                "API returned 400: maximum context length exceeded", 400, null));
        verify(metrics).recordError("gpt-4", "context_length");
    }

    @Test
    void errorClassifiesUnknown() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException("something weird"));
        verify(metrics).recordError("gpt-4", "unknown");
    }

    @Test
    void errorWithNullMessageClassifiesUnknown() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException());
        verify(metrics).recordError("gpt-4", "unknown");
    }

    @Test
    void nullModelUsesUnknown() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, null);
        session.send("data");
        session.complete();
        verify(metrics).recordLatency(eq("unknown"), any(), any());
    }

    @Test
    void sendMetadataRecordsTokenUsage() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.sendMetadata("usage.promptStreamingTexts", 100);
        session.sendMetadata("usage.completionStreamingTexts", 50);
        session.complete();
        verify(metrics).recordStreamingTextUsage("gpt-4", 100, 50);
    }

    @Test
    void usageRecordsTokenUsageAndForwardsToDelegate() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        var usage = new TokenUsage(120, 80, 0, 200, "gpt-4");
        session.usage(usage);
        verify(metrics).recordTokenUsage("gpt-4", 120L, 80L, 200L);
        verify(delegate).usage(usage);
    }

    @Test
    void usageWithNoCountsSkipsMetricButStillForwards() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        var empty = new TokenUsage(0, 0, 0, 0, null);
        session.usage(empty);
        verify(metrics, never()).recordTokenUsage(any(), anyLong(), anyLong(), anyLong());
        verify(delegate).usage(empty);
    }

    @Test
    void usageWithProviderUsesProviderAwareOverloadWithResponseModel() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        // Construct with a resolved runtime name → provider-aware path.
        var session = new MetricsCapturingSession(delegate, metrics, "gemini-2.0", "google-adk");
        var usage = new TokenUsage(120, 80, 0, 200, "gemini-2.0-flash-001");
        session.usage(usage);

        // (a) legacy delegate.usage still flows unchanged
        verify(delegate).usage(usage);
        // (b) recordTokenUsage called once with provider + response model (the 6-arg overload)
        verify(metrics).recordTokenUsage(
                eq("google-adk"), eq("gemini-2.0"), eq("gemini-2.0-flash-001"),
                eq(120L), eq(80L), eq(200L));
        // (c) the 4-arg legacy overload must NOT be invoked on the provider path
        verify(metrics, never()).recordTokenUsage(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void usageWithProviderInvokesGenAiTracerWithoutError() {
        // GenAiTracer.record is a static no-op when no OTel span is current —
        // assert the decorator wires it on the usage path without throwing and
        // still forwards usage + records the provider-aware metric.
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4", "built-in");
        var usage = new TokenUsage(10, 5, 0, 15, "gpt-4");
        session.usage(usage);

        verify(delegate).usage(usage);
        verify(metrics).recordTokenUsage(
                eq("built-in"), eq("gpt-4"), eq("gpt-4"), eq(10L), eq(5L), eq(15L));
    }

    @Test
    void usageWithoutProviderKeepsLegacyFourArgCall() {
        // 3-arg ctor (no resolved provider) keeps the exact legacy 4-arg call —
        // byte-identical behaviour for the pre-existing capture path.
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        var usage = new TokenUsage(120, 80, 0, 200, "gpt-4");
        session.usage(usage);

        verify(metrics).recordTokenUsage("gpt-4", 120L, 80L, 200L);
        verify(delegate).usage(usage);
        // The provider-aware overload must NOT be called on the legacy path.
        verify(metrics, never()).recordTokenUsage(
                any(), any(), any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void progressForwardsToDelegateDirectly() {
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, mockMetrics(), "gpt-4");
        session.progress("loading...");
        verify(delegate).progress("loading...");
    }

    @Test
    void isClosedDelegatesToWrapped() {
        var delegate = mockDelegate();
        when(delegate.isClosed()).thenReturn(false);
        var session = new MetricsCapturingSession(delegate, mockMetrics(), "gpt-4");
        assertFalse(session.isClosed());
    }

    // Cost + tool meters (the Tier-1 "cost is not observable" P1) ----------

    @Test
    void usageFeedsCostMeterWhenPricingInstalled() {
        // Regression: recordCost had ZERO production callers, so the
        // documented atmosphere.ai.cost meter was permanently empty. With a
        // TokenPricing installed, the shared metrics seam now feeds it.
        org.atmosphere.ai.cost.TokenPricingHolder.install(
                org.atmosphere.ai.cost.TokenPricing.flat(3.0, 15.0));
        try {
            var metrics = mockMetrics();
            var session = new MetricsCapturingSession(mockDelegate(), metrics, "gpt-4o");
            session.usage(new TokenUsage(1_000_000, 1_000_000, 0, 2_000_000, "gpt-4o"));

            var captor = org.mockito.ArgumentCaptor.forClass(java.math.BigDecimal.class);
            verify(metrics).recordCost(eq("gpt-4o"), captor.capture());
            assert Math.abs(captor.getValue().doubleValue() - 18.0) < 0.0001
                    : "1M in @ $3 + 1M out @ $15 = $18, got " + captor.getValue();
        } finally {
            org.atmosphere.ai.cost.TokenPricingHolder.reset();
        }
    }

    @Test
    void usageWithoutPricingNeverFabricatesACost() {
        // Runtime truth (Inv #5): no rate sheet installed -> the cost meter
        // stays empty; a fabricated $0 sample would read as "spend tracked".
        var metrics = mockMetrics();
        var session = new MetricsCapturingSession(mockDelegate(), metrics, "gpt-4o");
        session.usage(new TokenUsage(1000, 500, 0, 1500, "gpt-4o"));
        verify(metrics, never()).recordCost(any(), any());
    }

    @Test
    void toolLifecycleFeedsToolCallMeter() {
        // Regression: recordToolCall had zero production callers. The tool
        // lifecycle frames every bridge emits now feed the timer on both
        // dispatch paths (this decorator is the shared seam).
        var metrics = mockMetrics();
        var session = new MetricsCapturingSession(mockDelegate(), metrics, "gpt-4");
        session.emit(new AiEvent.ToolStart("search", java.util.Map.of("q", "x")));
        session.emit(new AiEvent.ToolResult("search", "found"));
        verify(metrics).recordToolCall(eq("gpt-4"), eq("search"), any(), eq(true));

        session.emit(new AiEvent.ToolStart("fetch", java.util.Map.of()));
        session.emit(new AiEvent.ToolError("fetch", "boom"));
        verify(metrics).recordToolCall(eq("gpt-4"), eq("fetch"), any(), eq(false));
    }
}
