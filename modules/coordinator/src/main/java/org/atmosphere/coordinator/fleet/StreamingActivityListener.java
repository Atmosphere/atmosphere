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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;

import java.util.Map;

/**
 * Bridges {@link AgentActivity} transitions into {@link AiEvent.AgentStep}
 * events on a {@link StreamingSession}. This is what makes agent activity
 * observable to clients in real time over WebSocket/SSE/WebTransport.
 *
 * <p>Maps each activity variant to an existing {@code AiEvent} type rather
 * than adding new variants — one event stream, one listener chain. The
 * existing AiEvent consumer pipeline (interceptors, filters, AG-UI bridge)
 * processes activity events for free.</p>
 */
public final class StreamingActivityListener implements AgentActivityListener {

    private final StreamingSession session;

    public StreamingActivityListener(StreamingSession session) {
        this.session = session;
    }

    @Override
    public void onActivity(AgentActivity activity) {
        if (session.isClosed()) {
            return;
        }
        var event = toAiEvent(activity);
        if (event != null) {
            session.emit(event);
        }
    }

    private static AiEvent toAiEvent(AgentActivity activity) {
        return switch (activity) {
            case AgentActivity.Thinking a -> new AiEvent.AgentStep(
                    "thinking",
                    "Agent '" + a.agentName() + "' is thinking...",
                    Map.of("agent", a.agentName(), "skill", a.skill()));

            case AgentActivity.Executing a -> new AiEvent.AgentStep(
                    "executing",
                    a.detail(),
                    Map.of("agent", a.agentName(), "skill", a.skill()));

            case AgentActivity.Retrying a -> new AiEvent.AgentStep(
                    "retrying",
                    "Agent '" + a.agentName() + "' retrying, attempt "
                            + a.attempt() + "/" + a.maxAttempts(),
                    Map.of("agent", a.agentName(), "skill", a.skill(),
                            "attempt", a.attempt(), "maxAttempts", a.maxAttempts()));

            case AgentActivity.CircuitOpen a -> new AiEvent.AgentStep(
                    "circuit-open",
                    "Agent '" + a.agentName() + "' circuit open: " + a.reason(),
                    Map.of("agent", a.agentName(), "reason", a.reason()));

            case AgentActivity.Completed a -> new AiEvent.AgentStep(
                    "completed",
                    "Agent '" + a.agentName() + "' completed in "
                            + a.elapsed().toMillis() + "ms",
                    Map.of("agent", a.agentName(), "skill", a.skill(),
                            "durationMs", a.elapsed().toMillis()));

            case AgentActivity.Failed a -> new AiEvent.AgentStep(
                    "failed",
                    "Agent '" + a.agentName() + "' failed: " + a.error(),
                    Map.of("agent", a.agentName(), "skill", a.skill(),
                            "error", a.error()));

            case AgentActivity.WaitingForInput a -> new AiEvent.AgentStep(
                    "waiting-for-input",
                    "Agent '" + a.agentName() + "' waiting: " + a.reason(),
                    Map.of("agent", a.agentName(), "reason", a.reason()));

            case AgentActivity.Evaluated a -> new AiEvent.AgentStep(
                    "eval",
                    String.format("Agent '%s' scored %.1f by %s: %s",
                            a.agentName(), a.score(), a.evaluatorName(),
                            a.reason() != null ? a.reason() : ""),
                    Map.of("agent", a.agentName(), "evaluator", a.evaluatorName(),
                            "score", a.score(), "passed", a.passed(),
                            "reason", a.reason() != null ? a.reason() : ""));

            case AgentActivity.Idle ignored -> null;
        };
    }
}
