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
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>{@code atmosphere.ai.input.tokens} &mdash; per-stage approximate input tokens, tagged by {@code stage}
 *       (system / tool_schema / structured_output_schema / confidence_cue / scrollback / user_message)</li>
 *   <li>{@code atmosphere.ai.input.chars} &mdash; per-stage exact character count, same tagging</li>
 *   <li>{@code atmosphere.ai.tokens} &mdash; authoritative provider token usage, tagged by
 *       {@code type} (input / output)</li>
 * </ul>
 *
 * <p>All Atmosphere-namespaced metrics are tagged with {@code model} and
 * {@code provider}. Because Micrometer is an optional dependency, this class is
 * only usable when {@code micrometer-core} is on the classpath.</p>
 *
 * <p>In addition to the {@code atmosphere.ai.*} series above, this class
 * <em>dual-emits</em> the
 * <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-metrics/">OpenTelemetry
 * GenAI semantic-convention</a> instruments so the same data lands in Langfuse /
 * LangSmith / Grafana GenAI dashboards without per-metric remapping:</p>
 * <ul>
 *   <li>{@code gen_ai.client.token.usage} &mdash; token counts split by
 *       {@code gen_ai.token.type} (input / output)</li>
 *   <li>{@code gen_ai.client.operation.duration} &mdash; full operation wall-clock time</li>
 * </ul>
 * <p>The convention instruments carry the {@code gen_ai.operation.name},
 * {@code gen_ai.provider.name}, and {@code gen_ai.request.model} attributes. The
 * existing {@code atmosphere.ai.*} series is retained unchanged so dashboards
 * built on it keep working.</p>
 */
public final class MicrometerAiMetrics implements AiMetrics {

    private static final Logger logger = LoggerFactory.getLogger(MicrometerAiMetrics.class);

    private final MeterRegistry registry;
    private final String provider;
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    /**
     * Set once if a co-resident instrumentation (e.g. quarkus-langchain4j)
     * already owns an OpenTelemetry GenAI metric name with a different tag-key
     * set — Micrometer rejects the second registration. Guards {@link
     * #otelDualEmit(Runnable)} so observability never breaks the request path.
     */
    private volatile boolean otelDualEmitDisabled = false;

    /**
     * Creates an instance using the Micrometer global registry with provider "atmosphere".
     * This constructor is used by {@link java.util.ServiceLoader} for auto-discovery.
     * Spring Boot auto-populates the global registry, so metrics appear in Actuator.
     */
    public MicrometerAiMetrics() {
        this(Metrics.globalRegistry, "atmosphere");
    }

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
        // OTel GenAI convention: gen_ai.client.operation.duration. Micrometer
        // exporters emit Timer durations in seconds, matching the convention's
        // unit, so this surfaces directly in GenAI dashboards.
        otelDualEmit(() ->
                timer("gen_ai.client.operation.duration", genAiTags(model)).record(totalDuration));
    }

    @Override
    public void recordTokenUsage(String model, long inputTokens, long outputTokens, long totalTokens) {
        // Source-compatible 4-arg form: default the GenAI provider to this
        // instance's provider and carry no response model. Existing callers
        // (and the legacy decorator path) keep their exact behaviour.
        recordTokenUsage(provider, model, null, inputTokens, outputTokens, totalTokens);
    }

    /**
     * Provider- and response-model-aware token usage recording. Threads the
     * <em>resolved</em> {@code AgentRuntime.name()} into the GenAI convention's
     * {@code gen_ai.provider.name} attribute (Runtime Truth — the no-arg
     * constructor's {@code "atmosphere"} default is never authoritative for the
     * GenAI series) and tags {@code gen_ai.response.model} when the runtime
     * reported a model.
     *
     * <p>The {@code atmosphere.ai.tokens} series is unchanged: it stays tagged
     * with the instance's {@code model}/{@code provider} so existing dashboards
     * keep working byte-for-byte.</p>
     *
     * @param genAiProvider  the resolved runtime name for {@code gen_ai.provider.name}
     * @param requestModel   the request model ({@code gen_ai.request.model})
     * @param responseModel  the provider-reported response model
     *                       ({@code gen_ai.response.model}); omitted when blank
     * @param inputTokens    prompt tokens consumed (0 when unknown)
     * @param outputTokens   completion tokens produced (0 when unknown)
     * @param totalTokens    total tokens for the completion (0 when unknown)
     */
    @Override
    public void recordTokenUsage(String genAiProvider, String requestModel, String responseModel,
                                 long inputTokens, long outputTokens, long totalTokens) {
        // Atmosphere-namespaced counter, tagged by token type, consistent with
        // the rest of the atmosphere.ai.* series. Uses the instance provider —
        // byte-identical to the prior behaviour regardless of genAiProvider.
        var tags = tags(requestModel);
        if (inputTokens > 0) {
            counter("atmosphere.ai.tokens", tags.and("type", "input")).increment(inputTokens);
        }
        if (outputTokens > 0) {
            counter("atmosphere.ai.tokens", tags.and("type", "output")).increment(outputTokens);
        }
        // OTel GenAI convention: gen_ai.client.token.usage, split by
        // gen_ai.token.type. The convention defines input/output token types
        // only — total is derivable and not a distinct series. The provider
        // tag is the resolved runtime name (Runtime Truth), and the response
        // model is added when the runtime reported one.
        var genAiTags = genAiTags(genAiProvider, requestModel, responseModel);
        otelDualEmit(() -> {
            if (inputTokens > 0) {
                registry.summary("gen_ai.client.token.usage", genAiTags.and("gen_ai.token.type", "input"))
                        .record(inputTokens);
            }
            if (outputTokens > 0) {
                registry.summary("gen_ai.client.token.usage", genAiTags.and("gen_ai.token.type", "output"))
                        .record(outputTokens);
            }
        });
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
    public void recordInputAssembly(String model, String stage,
                                    int approximateTokens, int approximateChars) {
        var tags = tags(model).and("stage", stage != null ? stage : "unknown");
        counter("atmosphere.ai.input.tokens", tags).increment(approximateTokens);
        counter("atmosphere.ai.input.chars", tags).increment(approximateChars);
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

    /**
     * Tags for the OpenTelemetry GenAI convention instruments. Uses the
     * convention attribute keys ({@code gen_ai.operation.name},
     * {@code gen_ai.provider.name}, {@code gen_ai.request.model}) rather than
     * the Atmosphere {@code model}/{@code provider} keys so the emitted series
     * matches what GenAI-aware backends expect.
     */
    private Tags genAiTags(String model) {
        return Tags.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.provider.name", provider,
                "gen_ai.request.model", model != null ? model : "unknown");
    }

    /**
     * GenAI convention tags carrying the resolved runtime provider and, when
     * the runtime reported one, the response model. Used by the
     * provider-aware {@link #recordTokenUsage(String, String, String, long, long, long)}
     * overload so {@code gen_ai.provider.name} reflects the actual runtime
     * (Runtime Truth) rather than the instance default. The
     * {@code gen_ai.response.model} tag is omitted when {@code responseModel}
     * is blank — no placeholder.
     */
    private Tags genAiTags(String genAiProvider, String requestModel, String responseModel) {
        var tags = Tags.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.provider.name", genAiProvider != null && !genAiProvider.isBlank()
                        ? genAiProvider : provider,
                "gen_ai.request.model", requestModel != null ? requestModel : "unknown");
        if (responseModel != null && !responseModel.isBlank()) {
            tags = tags.and("gen_ai.response.model", responseModel);
        }
        return tags;
    }

    private Counter counter(String name, Tags tags) {
        return registry.counter(name, tags);
    }

    private Timer timer(String name, Tags tags) {
        return registry.timer(name, tags);
    }

    /**
     * Record an OpenTelemetry GenAI-convention instrument, backing off
     * permanently the first time Micrometer rejects the registration because a
     * co-resident instrumentation (e.g. quarkus-langchain4j) already owns the
     * metric name with a different tag-key set. Observability must never break
     * the request path (Correctness Invariant #2), so on conflict we disable
     * Atmosphere's dual-emit and keep recording the {@code atmosphere.ai.*}
     * series, which uses a private namespace and cannot collide.
     */
    private void otelDualEmit(Runnable emit) {
        if (otelDualEmitDisabled) {
            return;
        }
        try {
            emit.run();
        } catch (RuntimeException e) {
            otelDualEmitDisabled = true;
            logger.debug("Disabling Atmosphere's OpenTelemetry GenAI dual-emit; a co-resident "
                    + "instrumentation already registered the metric with different tag keys: {}",
                    e.getMessage());
        }
    }
}
