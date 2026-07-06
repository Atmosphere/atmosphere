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
package org.atmosphere.ai.koog

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.feature.AIAgentPlannerFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.handler.planner.PlanCompletionEvaluationCompletedContext
import ai.koog.agents.core.feature.handler.planner.PlanCreationCompletedContext
import ai.koog.agents.core.feature.handler.planner.StepExecutionCompletedContext
import ai.koog.agents.core.feature.handler.planner.StepExecutionStartingContext
import ai.koog.agents.core.feature.pipeline.AIAgentPlannerPipeline
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.plan.AgentPlan
import org.atmosphere.ai.plan.FileSystemAgentPlanStore
import org.atmosphere.ai.plan.PlanStatus
import org.atmosphere.ai.tool.ToolScopes
import org.slf4j.LoggerFactory

/**
 * Read-only observation bridge from Koog's planner lifecycle to Atmosphere's
 * plan surface: an [AIAgentPlannerFeature] installed per dispatch on the
 * planner-agent path ([KoogAgentRuntime.executeWithPlannerAgent] passes it to
 * the [ai.koog.agents.core.planner.PlannerAIAgent]'s feature context) that
 * mirrors the plan the caller-supplied
 * [ai.koog.agents.core.planner.AIAgentPlanner] maintains into
 * [AiEvent.PlanUpdate] frames so consoles render the live plan.
 *
 * **Semantics (Correctness Invariant #5 — Runtime Truth):** Koog's planner
 * loop is *plan → execute one step → evaluate → replan* ([buildPlan] runs
 * every iteration with the previous plan as input), so the authoritative plan
 * content arrives on every [PlanCreationCompletedContext] and this bridge is
 * strictly read-only. The plan type is a caller-defined generic in Koog 1.0
 * GA (the concrete planners are beta-only, outside {@code agents-core}), so
 * the bridge decomposes it at the framework boundary: an `Iterable`/array
 * plan becomes one step per element, anything else a single step from
 * `toString()` (Correctness Invariant #4 — labels are trimmed, bounded to
 * [MAX_STEP_LABEL] chars and [FileSystemAgentPlanStore.MAX_STEPS] steps).
 *
 * Mapping:
 *  - [PlanCreationCompletedContext] — the fresh plan's decomposition becomes
 *    the [PlanStatus.PENDING] remainder; previously executed steps stay
 *    [PlanStatus.COMPLETED] unless the fresh plan re-lists them (framework
 *    truth wins — a re-planned step is open again);
 *  - [StepExecutionStartingContext] — the head of the remainder is marked
 *    [PlanStatus.IN_PROGRESS]. *Which* element a planner executes is
 *    planner-internal; head-of-remaining-plan is the loop's own convention
 *    (build plan → execute a step → repeat) and the next
 *    [PlanCreationCompletedContext] re-syncs from authoritative content;
 *  - [StepExecutionCompletedContext] — the in-flight head moves to
 *    [PlanStatus.COMPLETED];
 *  - [PlanCompletionEvaluationCompletedContext] with `isCompleted` — a final
 *    frame; any never-executed remainder is [PlanStatus.ABANDONED] (the
 *    planner declared the plan done without running those steps — marking
 *    them completed would claim work that never happened).
 *
 * A failure inside the bridge must never abort the agent run — plan
 * observability is best-effort, so mapping errors are logged at WARN and
 * dropped. The bridge is dispatch-scoped: one per request, installed on a
 * per-request agent, dying with the request's [StreamingSession] — no
 * registration outlives the dispatch (Correctness Invariant #1).
 *
 * [conversationId] / [agentId] ride every emitted [AiEvent.PlanUpdate] so
 * consoles correlate the live plan with the workspace browser. The runtime
 * derives them with the same [ToolScopes] resolution the built-in
 * `write_todos` floor keys its store with (Mode Parity) — the bridge itself
 * never re-derives identity.
 */
internal class KoogPlanBridge(
    private val session: StreamingSession,
    private val goal: String,
    private val conversationId: String,
    private val agentId: String,
    private val planStore: org.atmosphere.ai.plan.AgentPlanStore? = null
) {

    /** Executed step labels, in execution order (rendered COMPLETED). */
    private val completed = mutableListOf<String>()

    /** Decomposition of the latest plan (rendered PENDING / IN_PROGRESS). */
    private val remaining = mutableListOf<String>()

    /** Whether the head of [remaining] is currently executing. */
    private var executing = false

    /** Set on the final completion evaluation — remainder renders ABANDONED. */
    private var terminal = false

    /**
     * Per-request feature configuration. [session] is mandatory — the
     * feature cannot observe a plan without a live sink; [goal] /
     * [conversationId] / [agentId] carry the request's plan-scope labels.
     */
    internal class Config : FeatureConfig() {
        var session: StreamingSession? = null
        var goal: String = KOOG_MARKER
        var conversationId: String = ToolScopes.DEFAULT_SCOPE
        var agentId: String = ToolScopes.DEFAULT_SCOPE
        var planStore: org.atmosphere.ai.plan.AgentPlanStore? = null
    }

    /**
     * The Koog feature object: creates the dispatch-scoped bridge and
     * registers the four plan-lifecycle interceptors on the per-agent
     * [AIAgentPlannerPipeline]. The pipeline lives and dies with the
     * per-request [ai.koog.agents.core.planner.PlannerAIAgent], so no
     * uninstall step exists or is needed.
     */
    companion object Feature : AIAgentPlannerFeature<Config, KoogPlanBridge> {

        private val logger = LoggerFactory.getLogger(KoogPlanBridge::class.java)

        /** Marker on the plan goal so consoles show the plan is planner-maintained. */
        const val KOOG_MARKER = "Koog planner"

        /** Upper bound for a single decomposed step label (Invariant #3). */
        const val MAX_STEP_LABEL = 300

        override val key: AIAgentStorageKey<KoogPlanBridge> =
            createStorageKey("atmosphere-koog-plan-bridge")

        override fun createInitialConfig(agentConfig: AIAgentConfig): Config = Config()

        override fun install(config: Config, pipeline: AIAgentPlannerPipeline): KoogPlanBridge {
            val session = requireNotNull(config.session) {
                "KoogPlanBridge requires a StreamingSession on its Config"
            }
            val bridge = KoogPlanBridge(
                session, config.goal, config.conversationId, config.agentId, config.planStore)
            pipeline.interceptPlanCreationCompleted(this) { ctx -> bridge.onPlanCreated(ctx) }
            pipeline.interceptStepExecutionStarting(this) { ctx -> bridge.onStepStarting(ctx) }
            pipeline.interceptStepExecutionCompleted(this) { ctx -> bridge.onStepCompleted(ctx) }
            pipeline.interceptPlanCompletionEvaluationCompleted(this) { ctx ->
                bridge.onCompletionEvaluated(ctx)
            }
            return bridge
        }
    }

    internal fun onPlanCreated(ctx: PlanCreationCompletedContext) {
        guarded("plan-creation-completed") {
            val fresh = decompose(ctx.updatedPlan)
            // Framework truth wins: a step the fresh plan re-lists is open
            // again, so it leaves the executed history rather than appearing
            // twice (once COMPLETED, once PENDING).
            completed.removeAll { it in fresh }
            remaining.clear()
            remaining.addAll(fresh)
            executing = false
            terminal = false
            emitPlan()
        }
    }

    internal fun onStepStarting(ctx: StepExecutionStartingContext) {
        guarded("step-execution-starting") {
            if (remaining.isNotEmpty()) {
                executing = true
                emitPlan()
            } else {
                logger.debug("Koog planner step {} started with an empty plan decomposition — "
                    + "nothing to mark in progress", ctx.stepIndex)
            }
        }
    }

    internal fun onStepCompleted(ctx: StepExecutionCompletedContext) {
        guarded("step-execution-completed") {
            if (executing && remaining.isNotEmpty()) {
                completed.add(remaining.removeAt(0))
                executing = false
                emitPlan()
            } else {
                logger.debug("Koog planner step {} completed without a tracked in-flight step",
                    ctx.stepIndex)
            }
        }
    }

    internal fun onCompletionEvaluated(ctx: PlanCompletionEvaluationCompletedContext) {
        guarded("plan-completion-evaluated") {
            if (ctx.isCompleted) {
                executing = false
                terminal = true
                emitPlan()
            }
            // Not completed: the loop replans immediately and the follow-up
            // PlanCreationCompleted frame carries the fresh remainder — an
            // intermediate no-change frame would only be noise.
        }
    }

    /**
     * Session-closed and failure guard shared by every handler: plan
     * observability is best-effort, so a mapping error is logged at WARN and
     * dropped — it must never unwind into the planner loop and abort the
     * agent run.
     */
    private inline fun guarded(event: String, body: () -> Unit) {
        if (session.isClosed) {
            return
        }
        try {
            body()
        } catch (e: RuntimeException) {
            logger.warn("Failed to mirror Koog planner event {} to session {}: {}",
                event, session.sessionId(), e.message, e)
        }
    }

    /**
     * Decompose the caller-defined plan object at the framework boundary
     * (Correctness Invariant #4): iterables/arrays contribute one label per
     * element, anything else a single `toString()` label. Labels are
     * trimmed, blank entries dropped, each bounded to [MAX_STEP_LABEL] chars
     * and the list to [FileSystemAgentPlanStore.MAX_STEPS] entries — the
     * same step bound the portable plan store enforces (Invariant #3).
     */
    private fun decompose(plan: Any?): List<String> {
        val labels = when (plan) {
            null -> emptyList()
            is Iterable<*> -> plan.map { it?.toString().orEmpty() }
            is Array<*> -> plan.map { it?.toString().orEmpty() }
            else -> listOf(plan.toString())
        }
        return labels.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.length > MAX_STEP_LABEL) it.take(MAX_STEP_LABEL - 3) + "..." else it }
            .take(FileSystemAgentPlanStore.MAX_STEPS)
            .toList()
    }

    private fun emitPlan() {
        val steps = buildList {
            completed.forEach { add(AgentPlan.Step(it, PlanStatus.COMPLETED, null)) }
            remaining.forEachIndexed { index, label ->
                val status = when {
                    terminal -> PlanStatus.ABANDONED
                    index == 0 && executing -> PlanStatus.IN_PROGRESS
                    else -> PlanStatus.PENDING
                }
                add(AgentPlan.Step(label, status, null))
            }
        }
        val plan = AgentPlan(goal, steps)
        session.emit(AiEvent.PlanUpdate(plan.toWireSteps(), plan.goal(), conversationId, agentId))
        persist(plan)
        logger.debug("Mirrored Koog planner plan to session {}: goal='{}', {} step(s)",
            session.sessionId(), goal, steps.size)
    }

    private fun persist(plan: AgentPlan) {
        val store = planStore ?: return
        try {
            store.put(agentId, conversationId, plan)
        } catch (e: RuntimeException) {
            logger.warn("Koog plan persistence skipped for {}/{}: {}",
                agentId, conversationId, e.message)
        }
    }
}
