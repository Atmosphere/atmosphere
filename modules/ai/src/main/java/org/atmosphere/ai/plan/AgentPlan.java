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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plan an agent maintains and exposes: an optional goal plus an ordered
 * list of steps, each with free-text content and a {@link PlanStatus}.
 * Immutable — every update through the {@code write_todos} tool replaces the
 * full list (deepagents parity), so a plan value never mutates in place.
 *
 * <p>JSON-serializable through the same Jackson mapper the wire uses
 * ({@code tools.jackson.databind.ObjectMapper} — records serialize via their
 * components), which is how {@link FileSystemAgentPlanStore} persists it and
 * how {@link org.atmosphere.ai.AiEvent.PlanUpdate} carries it to browsers.</p>
 *
 * @param goal  optional free-text goal the steps work toward (may be {@code null})
 * @param steps the ordered steps — never {@code null}, defensively copied
 */
public record AgentPlan(String goal, List<Step> steps) {

    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * One entry of the plan.
     *
     * @param content    what this step is — imperative form, e.g. "Run tests"
     * @param status     the step's lifecycle state — never {@code null},
     *                   defaults to {@link PlanStatus#PENDING}
     * @param activeForm optional present-continuous label rendered while the
     *                   step is {@link PlanStatus#IN_PROGRESS}, e.g.
     *                   "Running tests" (may be {@code null})
     */
    public record Step(String content, PlanStatus status, String activeForm) {

        public Step {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("step content must not be null or blank");
            }
            if (status == null) {
                status = PlanStatus.PENDING;
            }
        }
    }

    /** An empty plan (no goal, no steps). */
    public static AgentPlan empty() {
        return new AgentPlan(null, List.of());
    }

    /**
     * Wire-friendly view of the steps: one ordered
     * {@code {content, status, activeForm}} map per step, statuses
     * lower-cased ({@code pending} / {@code in_progress} / ...). This is the
     * exact shape {@link org.atmosphere.ai.AiEvent.PlanUpdate} carries so
     * consoles render the plan without knowing this record type.
     *
     * @return an unmodifiable list of per-step maps
     */
    public List<Map<String, Object>> toWireSteps() {
        var wire = new ArrayList<Map<String, Object>>(steps.size());
        for (var step : steps) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("content", step.content());
            entry.put("status", step.status().name().toLowerCase(java.util.Locale.ROOT));
            if (step.activeForm() != null && !step.activeForm().isBlank()) {
                entry.put("activeForm", step.activeForm());
            }
            wire.add(Map.copyOf(entry));
        }
        return List.copyOf(wire);
    }

    /**
     * Render the plan as a Markdown checklist — the string the
     * {@code write_todos} tool returns to the model so it sees the state it
     * just wrote. {@code [ ]} pending, {@code [~]} in progress (with the
     * active form when present), {@code [x]} completed, {@code [-]} abandoned.
     *
     * @return the Markdown checklist, never {@code null}
     */
    public String toMarkdown() {
        var sb = new StringBuilder();
        if (goal != null && !goal.isBlank()) {
            sb.append("Goal: ").append(goal).append('\n');
        }
        if (steps.isEmpty()) {
            sb.append("(no steps)");
            return sb.toString();
        }
        for (var step : steps) {
            var marker = switch (step.status()) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[~]";
                case COMPLETED -> "[x]";
                case ABANDONED -> "[-]";
            };
            sb.append("- ").append(marker).append(' ');
            if (step.status() == PlanStatus.IN_PROGRESS
                    && step.activeForm() != null && !step.activeForm().isBlank()) {
                sb.append(step.activeForm());
            } else {
                sb.append(step.content());
            }
            sb.append('\n');
        }
        // Drop the trailing newline for a tidy tool-result string.
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
