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

import ai.koog.agents.core.planner.AIAgentPlanner
import org.atmosphere.ai.AgentExecutionContext

/**
 * Helper for attaching a Koog [AIAgentPlanner] to an [AgentExecutionContext]
 * so [KoogAgentRuntime] dispatches the request through a
 * [ai.koog.agents.core.planner.PlannerAIAgent] instead of the default
 * `chatAgentStrategy()` graph.
 *
 * Koog 1.0 GA ({@code agents-core}) ships the *abstract* planner surface —
 * [AIAgentPlanner] / [ai.koog.agents.core.planner.JavaAIAgentPlanner] with
 * the plan lifecycle events — while the concrete planners
 * (`SimpleLLMPlanner`, `GOAPPlanner`) are beta-only in the separate
 * {@code agents-planner} artifact, which is not a dependency of this
 * adapter. The planner is therefore always *caller-supplied*: subclass
 * [AIAgentPlanner] (or `JavaAIAgentPlanner` from Java), attach it here, and
 * the runtime builds the per-request planner agent around it. When
 * plan observation applies (see [KoogAgentRuntime.shouldObservePlan]) the
 * dispatch also installs [KoogPlanBridge], mirroring every plan the planner
 * maintains into [org.atmosphere.ai.AiEvent.PlanUpdate] frames.
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * The planner rides on [AgentExecutionContext.metadata] under
 * [METADATA_KEY], mirroring the [KoogStrategy] convention (and the
 * [org.atmosphere.ai.llm.CacheHint] shape). Keeps `modules/ai` free of any
 * Koog dependency — the [AIAgentPlanner] type is only resolved inside
 * `modules/koog` where it's already provided.
 *
 * <h2>Usage</h2>
 *
 * ```kotlin
 * class ResearchPlanner : AIAgentPlanner<String, String, MyState, List<String>>() {
 *     override fun initializeState(input: String) = MyState(input)
 *     override fun provideOutput(state: MyState) = state.render()
 *     override suspend fun buildPlan(context, state, plan) = state.remainingSteps()
 *     override suspend fun executeStep(context, state, plan) = state.execute(plan.first())
 *     override suspend fun isPlanCompleted(context, state, plan) = state.done()
 * }
 * val ctx = KoogPlanner.attach(baseContext, ResearchPlanner())
 * ```
 *
 * <h2>Type bound</h2>
 *
 * The bridge is typed to `AIAgentPlanner<String, String, *, *>` because the
 * runtime dispatches `agent.run(context.message())` — the input is the
 * user's prompt string and the output is the assistant's text reply. The
 * planner's `State` / `Plan` types stay caller-defined (star-projected);
 * they never cross the seam.
 */
object KoogPlanner {

    /**
     * Canonical metadata slot. The runtime reads from this key only.
     */
    const val METADATA_KEY: String = "koog.planner"

    /**
     * Read the attached planner from `context.metadata()`. Returns `null`
     * when no slot is present (the runtime then takes the default
     * chat-strategy / streaming-executor paths). Type errors throw — silent
     * drops would mask the planner never firing.
     */
    @JvmStatic
    fun from(context: AgentExecutionContext?): AIAgentPlanner<String, String, *, *>? {
        if (context == null || context.metadata() == null) return null
        val slot = context.metadata()[METADATA_KEY] ?: return null
        if (slot !is AIAgentPlanner<*, *, *, *>) {
            throw IllegalArgumentException(
                "$METADATA_KEY must be an AIAgentPlanner<String, String, *, *>, got " +
                    slot::class.java.name
            )
        }
        // Input/Output erase at runtime — the cast is checked by the
        // dispatch contract (the runtime always runs the planner with the
        // user's message String and emits its String output), same shape as
        // KoogStrategy.from.
        @Suppress("UNCHECKED_CAST")
        return slot as AIAgentPlanner<String, String, *, *>
    }

    /**
     * Return a new context with [planner] attached under [METADATA_KEY].
     * Replaces any previously attached planner — the bridge is exclusive
     * (one planner per request).
     */
    @JvmStatic
    fun attach(
        context: AgentExecutionContext,
        planner: AIAgentPlanner<String, String, *, *>
    ): AgentExecutionContext {
        val nextMetadata = HashMap<String, Any>(context.metadata())
        nextMetadata[METADATA_KEY] = planner
        return context.withMetadata(java.util.Map.copyOf(nextMetadata))
    }
}
