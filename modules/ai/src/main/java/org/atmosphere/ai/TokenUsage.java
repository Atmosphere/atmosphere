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

/**
 * Typed token counts reported by an {@link AgentRuntime} at the end of a
 * chat completion.
 *
 * <p>Phase 1 of the unified {@code @Agent} API promotes the ad-hoc
 * {@code ai.tokens.input/output/total} metadata keys that every runtime was
 * emitting as loose {@code sendMetadata} calls into a single typed event
 * ({@link StreamingSession#usage(TokenUsage)}). The default sink on
 * {@link StreamingSession} re-emits the legacy metadata keys so existing
 * consumers ({@code MetricsCapturingSession}, {@code MicrometerAiMetrics},
 * budget enforcement, cost dashboards) keep working without changes.</p>
 *
 * @param input        prompt / input tokens (0 if the provider did not report)
 * @param output       completion / output tokens (0 if not reported)
 * @param cachedInput  prompt tokens served from prompt cache, if the provider
 *                     exposes it; otherwise 0
 * @param total        total tokens ({@code input + output} when not reported
 *                     directly)
 * @param model        model identifier the counts apply to; may be {@code null}
 *                     when the runtime does not expose it
 */
public record TokenUsage(
        long input,
        long output,
        long cachedInput,
        long total,
        String model
) {

    /** Create a usage record with no cached-token count and no model. */
    public static TokenUsage of(long input, long output) {
        return new TokenUsage(input, output, 0L, input + output, null);
    }

    /** Create a usage record with an explicit total and no cached-token count. */
    public static TokenUsage of(long input, long output, long total) {
        return new TokenUsage(input, output, 0L, total, null);
    }

    /** Create a usage record with a model identifier. */
    public static TokenUsage of(long input, long output, long total, String model) {
        return new TokenUsage(input, output, 0L, total, model);
    }

    /**
     * Non-zero check: returns {@code true} when at least one field carries a
     * meaningful count. Runtimes call this before emitting via the session sink
     * so they don't fire usage events for providers that never populated the
     * metadata.
     */
    public boolean hasCounts() {
        return input > 0 || output > 0 || total > 0 || cachedInput > 0;
    }
}
