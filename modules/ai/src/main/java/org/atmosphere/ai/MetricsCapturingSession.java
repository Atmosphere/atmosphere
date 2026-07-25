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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link StreamingSession} decorator that captures timing and streaming text usage
 * metadata and reports them to an {@link AiMetrics} implementation.
 *
 * <p>Follows the same wrapping pattern as {@link MemoryCapturingSession}.
 * This is the ONE decorator present on both dispatch entry modes (see
 * {@code DispatchDecorators}), so it is also where the cost and tool-call
 * meters are fed — putting them anywhere else would recreate the
 * one-mode-only observability drift (Mode Parity, Invariant #7).</p>
 */
class MetricsCapturingSession extends DelegatingStreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCapturingSession.class);

    /**
     * Bound on concurrently-tracked tool starts within one turn. The session
     * is per-dispatch (short-lived), so this only guards a pathological turn
     * whose tool terminals never arrive (Invariant #3 — bounded structures).
     */
    private static final int MAX_TRACKED_TOOL_STARTS = 64;

    private final AiMetrics metrics;
    private final String model;
    private final String providerName;
    private final Instant startTime;
    /**
     * ToolStart timestamps by tool name, eldest-evicted at the cap and removed
     * on each terminal frame. Duration attribution by name is best-effort
     * under parallel same-named tool calls (the events carry no call id).
     */
    private final Map<String, Instant> toolStarts =
            new LinkedHashMap<>(8, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                    return size() > MAX_TRACKED_TOOL_STARTS;
                }
            };
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
            recordCost(usage);
        }
        delegate.usage(usage);
    }

    /**
     * Feed the {@code atmosphere.ai.cost} meter from the authoritative token
     * counts — the wire that was missing when {@code recordCost} had zero
     * production callers and the documented cost meter stayed permanently
     * empty. Dollars are only recorded when a real {@code TokenPricing} is
     * installed (default {@code ZERO} keeps the meter empty — no fabricated
     * costs, Runtime Truth Invariant #5) and the computed cost is positive.
     * Exception-isolated: a pricing fault never breaks the user's stream
     * (Invariant #2).
     */
    private void recordCost(TokenUsage usage) {
        try {
            var pricing = org.atmosphere.ai.cost.TokenPricingHolder.get();
            if (pricing == org.atmosphere.ai.cost.TokenPricing.ZERO) {
                return;
            }
            var effectiveModel = usage.model() != null && !usage.model().isBlank()
                    ? usage.model() : model;
            var costUsd = pricing.costUsd(usage, effectiveModel);
            if (costUsd > 0) {
                metrics.recordCost(effectiveModel, BigDecimal.valueOf(costUsd));
            }
        } catch (RuntimeException e) {
            logger.debug("Cost metering failed for model {}: {}", model, e.toString());
        }
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
        // Feed the atmosphere.ai.tool.duration timer from the tool lifecycle
        // frames every runtime bridge emits at the shared execution seam —
        // recordToolCall previously had zero production callers, so the
        // documented tool meter was permanently empty. Exception-isolated
        // like the cost feed; frames are forwarded regardless.
        try {
            switch (event) {
                case AiEvent.ToolStart start ->
                        recordToolStart(start.toolName());
                case AiEvent.ToolResult result ->
                        recordToolTerminal(result.toolName(), true);
                case AiEvent.ToolError error ->
                        recordToolTerminal(error.toolName(), false);
                default -> {
                    // Not a tool lifecycle frame.
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Tool-call metering failed: {}", e.toString());
        }
        delegate.emit(event);
    }

    private synchronized void recordToolStart(String toolName) {
        if (toolName != null) {
            toolStarts.putIfAbsent(toolName, Instant.now());
        }
    }

    private synchronized void recordToolTerminal(String toolName, boolean success) {
        if (toolName == null) {
            return;
        }
        var started = toolStarts.remove(toolName);
        var duration = started != null
                ? Duration.between(started, Instant.now())
                : Duration.ZERO;
        metrics.recordToolCall(model, toolName, duration, success);
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
        // Delegate to the shared taxonomy classifier so the metric labels and
        // the retry/routing classification always agree: a typed
        // AiProviderException reports its errorType; raw exceptions go
        // through the same message heuristics the adapters use.
        return ProviderErrorClassifier.errorType(t);
    }
}
