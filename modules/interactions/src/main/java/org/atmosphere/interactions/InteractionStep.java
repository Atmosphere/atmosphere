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
package org.atmosphere.interactions;

import org.atmosphere.ai.TokenUsage;

import java.time.Instant;
import java.util.Map;

/**
 * A single durable entry in an {@link Interaction}'s event log — the persisted,
 * retrievable projection of the live {@code org.atmosphere.ai.AiEvent} stream.
 *
 * <p>This is the {@code steps[]} primitive: where the live stream carries
 * fine-grained {@code AiEvent}s (one per token), the durable step log carries
 * the coarser, replayable record that survives completion and a JVM restart so
 * a background interaction can be retrieved long after the client disconnected.
 * {@link InteractionStepMapper} performs the {@code AiEvent → InteractionStep}
 * mapping (text deltas are coalesced; transport-only events are dropped).</p>
 *
 * @param seq       monotonic sequence within the owning interaction, starting at 0
 * @param type      step kind: {@code "text"}, {@code "tool-call"},
 *                  {@code "tool-result"}, {@code "tool-error"}, {@code "agent-step"},
 *                  {@code "approval"}, {@code "usage"}, {@code "completion"},
 *                  {@code "error"}
 * @param text      text/summary payload for {@code text}/{@code completion}/
 *                  {@code error} steps; {@code null} otherwise
 * @param toolName  tool identifier for {@code tool-*} steps; {@code null} otherwise
 * @param data      structured payload (tool arguments, serialized tool result,
 *                  agent-step data); never {@code null} (defensively copied)
 * @param usage     typed token counts for {@code usage} steps; {@code null} otherwise
 * @param createdAt when the step was captured
 */
public record InteractionStep(
        long seq,
        String type,
        String text,
        String toolName,
        Map<String, Object> data,
        TokenUsage usage,
        Instant createdAt) {

    public InteractionStep {
        data = data != null ? Map.copyOf(data) : Map.of();
    }
}
