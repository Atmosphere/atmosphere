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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import io.agentscope.core.plan.storage.PlanStorage;
import org.atmosphere.ai.plan.AgentPlan;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.plan.PlanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AgentScope {@link PlanStorage} implementation backed by Atmosphere's
 * {@link AgentPlanStore}, scoped to one {@code agentId × conversationId} pair
 * — the same key the built-in {@code write_todos} floor uses, so switching
 * {@code atmosphere.ai.planning} between {@code BUILTIN} and native keeps
 * reading the same persisted plan.
 *
 * <p>The Atmosphere store holds exactly one {@link AgentPlan} per scope
 * (full-replace semantics), so this storage exposes a single-slot view of
 * AgentScope's plan history: {@link #getPlans()} returns at most one plan and
 * {@link #getPlan(String)} answers only for {@link #currentPlanId()}, a
 * deterministic id derived from the scope key. That is exactly what the
 * {@code recover_historical_plan} / {@code view_historical_plans} tools need
 * to operate against Atmosphere-owned state.</p>
 *
 * <h2>State mapping</h2>
 *
 * <p>{@code SubTaskState} round-trips 1:1 to {@link PlanStatus}:
 * {@code TODO → PENDING}, {@code IN_PROGRESS → IN_PROGRESS},
 * {@code DONE → COMPLETED}, {@code ABANDONED → ABANDONED}. When the plan
 * itself is terminal ({@code finish_plan}), open subtask states are coerced
 * to the plan's terminal state ({@code DONE → COMPLETED},
 * {@code ABANDONED → ABANDONED}) so the persisted snapshot never shows
 * pending work on a closed plan. AgentScope-only detail (descriptions,
 * expected outcomes, timestamps) does not survive the round trip — the
 * {@link AgentPlan} contract is content + status.</p>
 *
 * <p>Writes that exceed the store's hard bounds (Correctness Invariant #3 —
 * {@code FileSystemAgentPlanStore} rejects over-limit plans with
 * {@link IllegalArgumentException}) are logged at WARN and skipped rather
 * than failing the AgentScope tool call: the in-notebook plan remains the
 * live copy for the rest of the turn, and the step-count bound is already
 * enforced at the model boundary by the notebook's {@code maxSubtasks}
 * (see {@link AgentScopePlanBridge}).</p>
 */
public class AtmospherePlanStorage implements PlanStorage {

    private static final Logger logger = LoggerFactory.getLogger(AtmospherePlanStorage.class);

    /** Fallback plan name when a persisted plan carries no goal. */
    private static final String DEFAULT_PLAN_NAME = "Current plan";

    private final AgentPlanStore store;
    private final String agentId;
    private final String conversationId;
    private final String currentPlanId;

    /**
     * @param store          the Atmosphere plan store that owns persistence
     * @param agentId        the owning agent scope (never {@code null} or blank)
     * @param conversationId the conversation scope (never {@code null} or blank)
     */
    public AtmospherePlanStorage(AgentPlanStore store, String agentId, String conversationId) {
        if (store == null) {
            throw new IllegalArgumentException("store is required");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        this.store = store;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.currentPlanId = UUID.nameUUIDFromBytes(
                ("atmosphere-agentscope-plan:" + agentId + '\n' + conversationId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The deterministic AgentScope plan id this storage exposes for its
     * scope's single plan slot. Stable across requests and JVM restarts so
     * {@code recover_historical_plan} can re-hydrate the persisted plan.
     *
     * @return the scope-derived plan id, never {@code null}
     */
    public String currentPlanId() {
        return currentPlanId;
    }

    @Override
    public Mono<Void> addPlan(Plan plan) {
        if (plan == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> mirror(toAgentPlan(plan)));
    }

    @Override
    public Mono<Plan> getPlan(String planId) {
        return Mono.defer(() -> currentPlanId.equals(planId)
                ? Mono.justOrEmpty(storedPlan().map(this::toPlan))
                : Mono.empty());
    }

    @Override
    public Mono<List<Plan>> getPlans() {
        return Mono.fromSupplier(() -> storedPlan()
                .map(plan -> List.of(toPlan(plan)))
                .orElseGet(List::of));
    }

    /**
     * Persist an {@link AgentPlan} snapshot into the Atmosphere store,
     * logging (not throwing) on a bounds rejection so a mirror miss never
     * fails the AgentScope plan tool that triggered it.
     *
     * @param plan the snapshot to persist (never {@code null})
     */
    void mirror(AgentPlan plan) {
        try {
            store.put(agentId, conversationId, plan);
        } catch (IllegalArgumentException e) {
            logger.warn("AgentScope plan mirror skipped for agent '{}' conversation '{}': {}",
                    agentId, conversationId, e.getMessage());
        }
    }

    /**
     * Read the persisted plan for this storage's scope.
     *
     * @return the stored plan, or empty when none was written yet
     */
    Optional<AgentPlan> storedPlan() {
        return store.get(agentId, conversationId);
    }

    /**
     * The plan id to re-hydrate when the persisted plan still has open work
     * (at least one {@code PENDING} / {@code IN_PROGRESS} step). Terminal
     * snapshots are never resurrected — a finished plan stays finished
     * across turns.
     *
     * @return the id for {@code recover_historical_plan}, or empty
     */
    Optional<String> livePlanId() {
        return storedPlan()
                .filter(plan -> plan.steps().stream().anyMatch(step ->
                        step.status() == PlanStatus.PENDING
                                || step.status() == PlanStatus.IN_PROGRESS))
                .map(plan -> currentPlanId);
    }

    /**
     * Map an AgentScope {@link Plan} to Atmosphere's {@link AgentPlan}:
     * plan name → goal, subtask name → step content, states per the class
     * contract (including terminal-plan coercion of open steps).
     *
     * @param plan the AgentScope plan (never {@code null})
     * @return the mapped plan, never {@code null}
     */
    static AgentPlan toAgentPlan(Plan plan) {
        var subtasks = plan.getSubtasks() != null ? plan.getSubtasks() : List.<SubTask>of();
        var steps = new ArrayList<AgentPlan.Step>(subtasks.size());
        for (var subtask : subtasks) {
            var name = subtask.getName();
            steps.add(new AgentPlan.Step(
                    name == null || name.isBlank() ? "Unnamed step" : name,
                    toStatus(subtask.getState(), plan.getState()),
                    null));
        }
        return new AgentPlan(plan.getName(), steps);
    }

    private static PlanStatus toStatus(SubTaskState state, PlanState planState) {
        var mapped = switch (state == null ? SubTaskState.TODO : state) {
            case TODO -> PlanStatus.PENDING;
            case IN_PROGRESS -> PlanStatus.IN_PROGRESS;
            case DONE -> PlanStatus.COMPLETED;
            case ABANDONED -> PlanStatus.ABANDONED;
        };
        // finish_plan closes the plan without touching open subtasks; coerce
        // them so the persisted snapshot never shows pending work on a
        // terminal plan.
        if (mapped == PlanStatus.PENDING || mapped == PlanStatus.IN_PROGRESS) {
            if (planState == PlanState.DONE) {
                return PlanStatus.COMPLETED;
            }
            if (planState == PlanState.ABANDONED) {
                return PlanStatus.ABANDONED;
            }
        }
        return mapped;
    }

    /**
     * Reconstruct an AgentScope {@link Plan} from the persisted
     * {@link AgentPlan}, carrying {@link #currentPlanId()} so recovery and
     * history messages reference the stable scope id. The plan-level state
     * is derived from the steps: any {@code IN_PROGRESS} step marks the plan
     * {@code IN_PROGRESS}; an all-terminal, non-empty plan is {@code DONE}
     * (or {@code ABANDONED} when no step completed); otherwise {@code TODO}.
     */
    private Plan toPlan(AgentPlan agentPlan) {
        var subtasks = new ArrayList<SubTask>(agentPlan.steps().size());
        for (var step : agentPlan.steps()) {
            var subtask = new SubTask(step.content(), "", "");
            subtask.setState(toSubTaskState(step.status()));
            subtasks.add(subtask);
        }
        var goal = agentPlan.goal();
        var plan = new Plan(goal == null || goal.isBlank() ? DEFAULT_PLAN_NAME : goal,
                "", "", subtasks);
        plan.setId(currentPlanId);
        plan.setState(derivePlanState(subtasks));
        return plan;
    }

    private static PlanState derivePlanState(List<SubTask> subtasks) {
        var anyCompleted = false;
        var allTerminal = !subtasks.isEmpty();
        for (var subtask : subtasks) {
            switch (subtask.getState()) {
                case IN_PROGRESS -> {
                    return PlanState.IN_PROGRESS;
                }
                case DONE -> anyCompleted = true;
                case TODO -> allTerminal = false;
                case ABANDONED -> {
                    // terminal; keeps allTerminal true
                }
            }
        }
        if (allTerminal) {
            return anyCompleted ? PlanState.DONE : PlanState.ABANDONED;
        }
        return PlanState.TODO;
    }

    private static SubTaskState toSubTaskState(PlanStatus status) {
        return switch (status) {
            case PENDING -> SubTaskState.TODO;
            case IN_PROGRESS -> SubTaskState.IN_PROGRESS;
            case COMPLETED -> SubTaskState.DONE;
            case ABANDONED -> SubTaskState.ABANDONED;
        };
    }
}
