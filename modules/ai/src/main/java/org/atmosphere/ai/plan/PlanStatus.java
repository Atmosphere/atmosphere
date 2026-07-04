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
package org.atmosphere.ai.plan;

import java.util.Locale;

/**
 * Lifecycle state of a single {@link AgentPlan.Step}. Mirrors the
 * deepagents / Claude-Code todo shape ({@code pending} / {@code in_progress}
 * / {@code completed}) plus {@code abandoned} for steps the agent explicitly
 * gave up on — the same four states AgentScope's {@code SubTaskState}
 * ({@code TODO, IN_PROGRESS, DONE, ABANDONED}) round-trips to, so native
 * plan bridges map 1:1.
 */
public enum PlanStatus {

    /** Not started yet. */
    PENDING,

    /** Currently being worked on. */
    IN_PROGRESS,

    /** Finished successfully. */
    COMPLETED,

    /** Explicitly given up — will not be worked on. */
    ABANDONED;

    /**
     * Lenient parse of a step status. {@code null}, blank, or unrecognized
     * input resolves to {@link #PENDING} rather than throwing (Correctness
     * Invariant #4: catch parse errors at the boundary — the value arrives
     * from model-generated tool arguments). Matching is case-insensitive,
     * trims whitespace, and accepts both {@code in_progress} and
     * {@code in-progress} spellings plus the AgentScope aliases
     * ({@code todo}, {@code done}).
     *
     * @param raw the raw status value (may be {@code null})
     * @return the resolved status, never {@code null}
     */
    public static PlanStatus parse(String raw) {
        if (raw == null) {
            return PENDING;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "in_progress", "in-progress", "inprogress", "active", "doing" -> IN_PROGRESS;
            case "completed", "complete", "done", "finished" -> COMPLETED;
            case "abandoned", "cancelled", "canceled", "skipped" -> ABANDONED;
            default -> PENDING;
        };
    }
}
