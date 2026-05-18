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

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@link AiMetrics} implementation that emits JDK Flight Recorder events.
 * Zero-cost when no recording is active because each event's
 * {@code shouldCommit()} short-circuits before any I/O.
 *
 * <p>Always-on by design: composed into the pipeline by
 * {@link CompositeAiMetrics#withJfr} so it runs alongside any user-supplied
 * {@link AiMetrics} (Micrometer, OTel, etc.) without conflicting.</p>
 */
public final class JfrAiMetrics implements AiMetrics {

    @Override
    public void recordStreamingTextUsage(String model, int promptStreamingTexts, int completionStreamingTexts) {
        // Streaming chunk counts are reported as Micrometer counters elsewhere; no JFR event today.
        // Future: a per-chunk event would be too high-volume; a per-turn rollup belongs on AgentTurnEvent.
    }

    @Override
    public void recordLatency(String model, Duration timeToFirstStreamingText, Duration totalDuration) {
        var event = new AiCallEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.model = nullSafe(model);
        event.timeToFirstTokenNanos = timeToFirstStreamingText != null ? timeToFirstStreamingText.toNanos() : 0L;
        event.totalDurationNanos = totalDuration != null ? totalDuration.toNanos() : 0L;
        event.commit();
    }

    @Override
    public void recordCost(String model, BigDecimal cost) {
        // Cost is a derived value already reported via Micrometer; no dedicated JFR event yet.
    }

    @Override
    public void recordToolCall(String model, String toolName, Duration duration, boolean success) {
        var event = new ToolInvocationEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.model = nullSafe(model);
        event.tool = nullSafe(toolName);
        event.outcome = success ? ToolInvocationEvent.OUTCOME_SUCCESS : ToolInvocationEvent.OUTCOME_FAILURE;
        event.durationNanos = duration != null ? duration.toNanos() : 0L;
        event.commit();
    }

    @Override
    public void recordError(String model, String errorType) {
        var event = new AiErrorEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.model = nullSafe(model);
        event.errorType = nullSafe(errorType);
        event.commit();
    }

    @Override
    public void sessionStarted(String model) {
        emitLifecycle(model, SessionLifecycleEvent.TRANSITION_STARTED);
    }

    @Override
    public void sessionEnded(String model) {
        emitLifecycle(model, SessionLifecycleEvent.TRANSITION_ENDED);
    }

    private static void emitLifecycle(String model, String transition) {
        var event = new SessionLifecycleEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.model = nullSafe(model);
        event.transition = transition;
        event.commit();
    }

    private static String nullSafe(String value) {
        return value != null ? value : "unknown";
    }
}
