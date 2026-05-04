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

import com.embabel.agent.api.common.PromptRunner
import org.atmosphere.ai.AgentExecutionContext

/**
 * Per-request bridge for customizing the Embabel [PromptRunner] used by
 * [EmbabelAgentRuntime]'s Atmosphere-native dispatch path. The customizer
 * receives the runtime's pre-configured runner (system prompt, history,
 * tools, images already wired) and returns a transformed runner —
 * letting callers stack Embabel-native modifiers like `withTemperature`,
 * `withModel`, `withGuardrails`, etc. without growing the unified
 * `AgentRuntime` SPI with framework-specific knobs.
 *
 * <h2>Why a metadata sidecar</h2>
 *
 * The customizer rides on [AgentExecutionContext.metadata] under
 * [METADATA_KEY], mirroring the [org.atmosphere.ai.llm.CacheHint] convention
 * and the [org.atmosphere.ai.spring.SpringAiAdvisors],
 * [org.atmosphere.ai.langchain4j.LangChain4jAiServices], [KoogStrategy], and
 * [org.atmosphere.ai.adk.AdkRootAgent] bridges. Keeps `modules/ai` free of any
 * Embabel dependency — the [PromptRunner] type is only resolved inside
 * `modules/embabel` where it's already provided.
 *
 * <h2>Scope</h2>
 *
 * Only fires on the **Atmosphere-native** dispatch path
 * (`Ai.withDefaultLlm()`). The deployed-`@Agent` path
 * (`AgentPlatform.runAgentFrom(...)`) bypasses the PromptRunner entirely —
 * the deployed agent owns its own configuration. Callers that need to
 * customize a deployed agent should configure the `@Agent` class itself.
 *
 * <h2>Usage</h2>
 *
 * ```kotlin
 * val ctx = EmbabelPromptRunner.attach(baseContext) { runner ->
 *     runner.withTemperature(0.2)
 *           .withModel("gpt-4o")
 * }
 * runtime.execute(ctx, session)
 * ```
 *
 * Or via interceptor (Java):
 *
 * ```java
 * @Component
 * class TempInterceptor implements AiInterceptor {
 *     @Override
 *     public AiRequest preProcess(AiRequest request, AtmosphereResource r) {
 *         java.util.function.UnaryOperator<PromptRunner> customizer =
 *             runner -> runner.withTemperature(0.2);
 *         return request.withMetadata(Map.of(
 *                 EmbabelPromptRunner.METADATA_KEY, customizer));
 *     }
 * }
 * ```
 */
object EmbabelPromptRunner {

    /**
     * Canonical metadata slot. The runtime reads from this key only.
     */
    const val METADATA_KEY: String = "embabel.promptRunner"

    /**
     * Read the customizer from `context.metadata()`. Returns `null` when no
     * slot is present (the runtime then dispatches with the default
     * pre-configured runner). Type errors throw — silent drops would mask
     * the customizer never firing.
     */
    @JvmStatic
    fun from(context: AgentExecutionContext?): java.util.function.UnaryOperator<PromptRunner>? {
        if (context == null || context.metadata() == null) return null
        val slot = context.metadata()[METADATA_KEY] ?: return null
        if (slot !is java.util.function.UnaryOperator<*>) {
            throw IllegalArgumentException(
                "$METADATA_KEY must be a UnaryOperator<PromptRunner>, got " +
                    slot::class.java.name
            )
        }
        @Suppress("UNCHECKED_CAST")
        return slot as java.util.function.UnaryOperator<PromptRunner>
    }

    /**
     * Return a new context with [customizer] attached under [METADATA_KEY].
     * Replaces any previously attached customizer — the bridge is exclusive
     * (one customizer per request, since composition order is the caller's
     * responsibility — wrap two customizers into one if needed).
     */
    @JvmStatic
    fun attach(
        context: AgentExecutionContext,
        customizer: java.util.function.UnaryOperator<PromptRunner>
    ): AgentExecutionContext {
        val nextMetadata = HashMap<String, Any>(context.metadata())
        nextMetadata[METADATA_KEY] = customizer
        return context.withMetadata(java.util.Map.copyOf(nextMetadata))
    }
}
