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

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import org.atmosphere.ai.AgentExecutionContext

/**
 * Helper for attaching a custom Koog [AIAgentGraphStrategy] to an
 * [AgentExecutionContext] so [KoogAgentRuntime] uses it in place of the
 * default `chatAgentStrategy()`.
 *
 * Koog's distinguishing feature is its **strategy DSL** —
 * `strategy { node(...) edge(...) }` lets users compose multi-step LLM
 * workflows with branching, parallel fan-out, structured output gates, and
 * tool retry loops. Without this bridge an Atmosphere caller using the Koog
 * runtime was locked into `chatAgentStrategy()` (the simple
 * "user→llm→tools→llm→response" graph). The bridge keeps the same default
 * but lets advanced callers swap in any [AIAgentGraphStrategy] that
 * Koog's DSL produces — for example a `reActStrategy(maxIterations = 8)`
 * planner, a `structuredOutputWithToolsStrategy(...)` typed pipeline, or a
 * fully custom graph from `strategy("my-graph") { ... }`.
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * The strategy rides on [AgentExecutionContext.metadata] under
 * [METADATA_KEY], mirroring the [org.atmosphere.ai.llm.CacheHint] convention
 * and the [org.atmosphere.ai.spring.SpringAiAdvisors] /
 * [org.atmosphere.ai.langchain4j.LangChain4jAiServices] bridges. Keeps
 * `modules/ai` free of any Koog dependency — the [AIAgentGraphStrategy] type
 * is only resolved inside `modules/koog` where it's already provided.
 *
 * <h2>Usage</h2>
 *
 * ```kotlin
 * // Built-in alternative strategy (Koog ships several):
 * val ctx = KoogStrategy.attach(baseContext, reActStrategy(maxIterations = 8))
 *
 * // Custom strategy via the DSL:
 * val critic = strategy("critic-then-rewrite") {
 *     val draft by node<String, String> { /* ... */ }
 *     val critique by node<String, String> { /* ... */ }
 *     val rewrite by node<String, String> { /* ... */ }
 *     edge(nodeStart forwardTo draft)
 *     edge(draft forwardTo critique)
 *     edge(critique forwardTo rewrite)
 *     edge(rewrite forwardTo nodeFinish)
 * }
 * val ctx = KoogStrategy.attach(baseContext, critic)
 *
 * // Or from Java via interceptor:
 * @Component
 * class MyKoogStrategyInterceptor : AiInterceptor {
 *     override fun preProcess(request: AiRequest, resource: AtmosphereResource): AiRequest =
 *         request.withMetadata(mapOf(KoogStrategy.METADATA_KEY to myCustomStrategy))
 * }
 * ```
 *
 * <h2>Type bound</h2>
 *
 * The bridge is typed to [AIAgentGraphStrategy] of `String → String` because
 * that's what `KoogAgentRuntime.executeWithAgent(...)` calls
 * `agent.run(context.message())` with — the input is the user's prompt
 * string and the output is the assistant's text reply, which the strategy's
 * graph eventually produces. Strategies that need typed inputs/outputs (e.g.
 * `structuredOutputWithToolsStrategy<MyType>(...)`) require a different
 * dispatch path that the runtime doesn't expose today; those should live
 * inside the strategy itself (parse the message, format the typed output as
 * text on the way out).
 */
object KoogStrategy {

    /**
     * Canonical metadata slot. The runtime reads from this key only.
     */
    const val METADATA_KEY: String = "koog.strategy"

    /**
     * Read the bridge strategy from `context.metadata()`. Returns `null`
     * when no slot is present (the runtime then takes the default
     * `chatAgentStrategy()` path). Type errors throw — silent drops would
     * mask the custom strategy never firing.
     */
    @JvmStatic
    fun from(context: AgentExecutionContext?): AIAgentGraphStrategy<String, String>? {
        if (context == null || context.metadata() == null) return null
        val slot = context.metadata()[METADATA_KEY] ?: return null
        if (slot !is AIAgentGraphStrategy<*, *>) {
            throw IllegalArgumentException(
                "$METADATA_KEY must be an AIAgentGraphStrategy<String, String>, got " +
                    slot::class.java.name
            )
        }
        @Suppress("UNCHECKED_CAST")
        return slot as AIAgentGraphStrategy<String, String>
    }

    /**
     * Return a new context with [strategy] attached under [METADATA_KEY].
     * Replaces any previously attached strategy — the bridge is exclusive
     * (one strategy per request).
     */
    @JvmStatic
    fun attach(
        context: AgentExecutionContext,
        strategy: AIAgentGraphStrategy<String, String>
    ): AgentExecutionContext {
        val nextMetadata = HashMap<String, Any>(context.metadata())
        nextMetadata[METADATA_KEY] = strategy
        return context.withMetadata(java.util.Map.copyOf(nextMetadata))
    }
}
