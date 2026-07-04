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
package org.atmosphere.ai.embabel

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.ReplanRequestedEvent
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.plan.AgentPlan
import org.atmosphere.ai.plan.PlanStatus
import org.slf4j.LoggerFactory

/**
 * Read-only observation bridge from Embabel's GOAP planner to Atmosphere's
 * plan surface: an [AgenticEventListener] registered per dispatch on the
 * deployed-agent path ([EmbabelAgentRuntime.executeDeployedAgent] adds it to
 * the [com.embabel.agent.core.ProcessOptions]) that mirrors every plan the
 * A* GOAP planner formulates into an [AiEvent.PlanUpdate] frame so consoles
 * render the live plan.
 *
 * **Semantics (Correctness Invariant #5 — Runtime Truth):** Embabel's plan is
 * *framework-computed* — a deterministic A* GOAP plan derived from `@Action`
 * pre/post-conditions, re-planned after every action execution. The model
 * never authors or updates it, so this bridge is strictly read-only and the
 * emitted plan goal carries a `GOAP:` marker so the console shows the plan is
 * framework-computed, not a model-maintained todo list.
 *
 * Mapping on [AgentProcessPlanFormulatedEvent]:
 *  - every executed [com.embabel.agent.core.ActionInvocation] in the process
 *    history becomes a [PlanStatus.COMPLETED] step (in execution order);
 *  - every action of the freshly formulated [com.embabel.plan.Plan] becomes a
 *    [PlanStatus.PENDING] step;
 *  - the plan's [com.embabel.plan.Goal] name becomes the plan goal, prefixed
 *    with the `GOAP:` marker.
 *
 * On [ReplanRequestedEvent] the old plan's remainder is invalidated and the
 * new plan does not exist yet, so only the executed history is emitted (all
 * [PlanStatus.COMPLETED]) with the replan reason surfaced on the goal label;
 * the follow-up [AgentProcessPlanFormulatedEvent] then carries the fresh
 * pending steps.
 *
 * A failure inside the bridge must never abort the agent run — plan
 * observability is best-effort, so mapping errors are logged at WARN and
 * dropped (the process event multicast continues).
 *
 * The bridge is dispatch-scoped: [EmbabelAgentRuntime] creates one per
 * request and it dies with the request's [StreamingSession] — no
 * registration outlives the dispatch (Correctness Invariant #1).
 */
internal class EmbabelGoapPlanBridge(
    private val session: StreamingSession
) : AgenticEventListener {

    companion object {
        private val logger = LoggerFactory.getLogger(EmbabelGoapPlanBridge::class.java)

        /** Marker prefixed to the plan goal so consoles show the plan is framework-computed. */
        const val GOAP_MARKER = "GOAP"
    }

    /** Last observed goal name, reused on replan events (which carry no plan). */
    @Volatile
    private var lastGoalName: String? = null

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (session.isClosed) {
            return
        }
        try {
            when (event) {
                is AgentProcessPlanFormulatedEvent -> emitFormulatedPlan(event)
                is ReplanRequestedEvent -> emitReplan(event)
                else -> {
                    // Other process lifecycle events are out of scope for the
                    // plan surface — AtmosphereOutputChannel carries them.
                }
            }
        } catch (e: RuntimeException) {
            logger.warn("Failed to mirror Embabel GOAP plan event {} to session {}: {}",
                event.javaClass.simpleName, session.sessionId(), e.message, e)
        }
    }

    private fun emitFormulatedPlan(event: AgentProcessPlanFormulatedEvent) {
        val goalName = event.plan.goal.name.takeIf { it.isNotBlank() }
        lastGoalName = goalName
        val steps = buildList {
            completedHistorySteps(event)
            event.plan.actions.forEach { action ->
                action.name.takeIf { it.isNotBlank() }?.let {
                    add(AgentPlan.Step(it, PlanStatus.PENDING, null))
                }
            }
        }
        emitPlanUpdate(
            if (goalName != null) "$GOAP_MARKER: $goalName" else "$GOAP_MARKER plan",
            steps
        )
    }

    private fun emitReplan(event: ReplanRequestedEvent) {
        val reason = event.reason.takeIf { it.isNotBlank() } ?: "replan requested"
        val goalName = lastGoalName
        val goal = if (goalName != null) {
            "$GOAP_MARKER: $goalName (replanning: $reason)"
        } else {
            "$GOAP_MARKER plan (replanning: $reason)"
        }
        emitPlanUpdate(goal, buildList { completedHistorySteps(event) })
    }

    /**
     * Append one [PlanStatus.COMPLETED] step per executed action invocation,
     * in execution order. Blank action names are dropped rather than thrown —
     * the values arrive from a framework boundary (Correctness Invariant #4).
     */
    private fun MutableList<AgentPlan.Step>.completedHistorySteps(
        event: com.embabel.agent.api.event.AbstractAgentProcessEvent
    ) {
        event.history.forEach { invocation ->
            invocation.actionName.takeIf { it.isNotBlank() }?.let {
                add(AgentPlan.Step(it, PlanStatus.COMPLETED, null))
            }
        }
    }

    private fun emitPlanUpdate(goal: String, steps: List<AgentPlan.Step>) {
        val plan = AgentPlan(goal, steps)
        session.emit(AiEvent.PlanUpdate(plan.toWireSteps(), plan.goal()))
        logger.debug("Mirrored Embabel GOAP plan to session {}: goal='{}', {} step(s)",
            session.sessionId(), goal, steps.size)
    }
}
