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
package org.atmosphere.kotlin.ai

import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.AgentRuntimeResolver
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiConversationMemory
import org.atmosphere.ai.AiMetrics
import org.atmosphere.ai.AiPipeline
import org.atmosphere.ai.CompactionConfig
import org.atmosphere.ai.InMemoryConversationMemory
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.annotation.Prompt
import org.atmosphere.ai.governance.GovernancePolicies
import org.atmosphere.ai.governance.PolicyAsGuardrail
import org.atmosphere.ai.processor.AiEndpointHandler
import org.atmosphere.ai.tool.DefaultToolRegistry
import org.atmosphere.annotation.AnnotationUtil
import org.atmosphere.config.managed.AnnotatedLifecycle
import org.atmosphere.cpr.AtmosphereFramework
import org.atmosphere.cpr.AtmosphereInterceptor
import org.slf4j.LoggerFactory
import java.util.LinkedList
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

private val logger = LoggerFactory.getLogger("org.atmosphere.kotlin.ai.AgentRegistration")

/**
 * Wire a declared [AgentSpec] into this framework and return the live agent.
 *
 * The DSL is a front-end, not a second AI stack. Registration builds the same
 * objects the annotation processors build, in the same order:
 *
 * 1. the runtime comes from [AgentRuntimeResolver] (highest-priority available
 *    runtime, every backend `configure`d first — a backend whose eager
 *    `configure()` throws is logged and skipped, never fatal);
 * 2. tools land in a [DefaultToolRegistry], the registry `@AiTool` scanning fills;
 * 3. memory is an [InMemoryConversationMemory] honoring the framework's
 *    `org.atmosphere.ai.compaction` setting;
 * 4. every installed [org.atmosphere.ai.governance.GovernancePolicy] is wrapped
 *    as a [PolicyAsGuardrail] for the streaming path and passed as a policy to
 *    the pipeline — so a DSL agent admits against the same chain as an
 *    annotated one (Mode Parity);
 * 5. the [AiEndpointHandler] is registered at [AgentSpec.path] with the
 *    framework's default managed-service interceptors, exactly as
 *    `AgentProcessor` does for `@Agent`.
 */
fun AtmosphereFramework.registerAgent(spec: AgentSpec): KotlinAgent {
    val settings = AiConfig.get() ?: AiConfig.fromEnvironment()
    val runtime = spec.runtime ?: resolveRuntime(settings)

    val toolRegistry = DefaultToolRegistry()
    spec.tools.forEach { toolRegistry.register(it) }

    val memory: AiConversationMemory? = if (spec.memoryEnabled) {
        InMemoryConversationMemory(spec.maxHistory, CompactionConfig.resolve(atmosphereConfig))
    } else {
        null
    }

    val metrics = resolveMetrics()
    val policies = GovernancePolicies.installed(this)
    val guardrails = policies.map { PolicyAsGuardrail(it) }

    val promptTarget = DslPromptHandler()
    val promptMethod = DslPromptHandler::class.java.getDeclaredMethod(
        "onPrompt", String::class.java, StreamingSession::class.java
    )

    val handler = AiEndpointHandler(
        promptTarget,
        promptMethod,
        spec.suspendTimeoutMillis,
        spec.systemPrompt,
        spec.path,
        runtime,
        emptyList(),
        memory,
        AnnotatedLifecycle.scan(DslPromptHandler::class.java),
        toolRegistry,
        guardrails,
        emptyList(),
        metrics,
        emptyList(),
        spec.model
    )

    val interceptors: MutableList<AtmosphereInterceptor> = LinkedList()
    AnnotationUtil.defaultManagedServiceInterceptors(this, interceptors)
    addAtmosphereHandler(spec.path, handler, interceptors)

    val pipeline = AiPipeline(
        runtime,
        spec.systemPrompt,
        spec.model ?: settings.model(),
        memory,
        toolRegistry,
        emptyList(),
        policies,
        emptyList(),
        metrics,
        null
    )

    logger.info(
        "Kotlin DSL agent '{}' registered at {} (runtime: {}, tools: {}, memory: {})",
        spec.name, spec.path, runtime.name(),
        toolRegistry.allTools().map { it.name() },
        if (memory != null) "on(max=${spec.maxHistory})" else "off"
    )

    return KotlinAgent(spec.name, spec.path, runtime, pipeline, handler, toolRegistry, memory)
}

/**
 * Declare and register in one step.
 *
 * ```kotlin
 * val assistant = framework.registerAgent("support") {
 *     systemPrompt = "You are a concise support assistant."
 *     tool("order_status", "Look up an order") {
 *         param("orderId", "The order identifier")
 *         execute { args -> lookup(args["orderId"] as String) }
 *     }
 * }
 * val answer = assistant.ask("user-42", "where is order 7?")
 * ```
 */
fun AtmosphereFramework.registerAgent(name: String, init: AgentBuilder.() -> Unit): KotlinAgent =
    registerAgent(agent(name, init))

/**
 * Configure every discovered backend and return the highest-priority one —
 * the identical resolution (and the identical eager-`configure()` tolerance)
 * the `@Agent` and `@AiEndpoint` processors perform.
 */
private fun resolveRuntime(settings: AiConfig.LlmSettings): AgentRuntime {
    val backends = AgentRuntimeResolver.resolveAll()
    backends.forEach { backend ->
        try {
            backend.configure(settings)
        } catch (e: RuntimeException) {
            logger.warn(
                "Backend {} failed eager configure() — registration continues and the " +
                    "runtime is re-resolved at request time. Reason: {}",
                backend.name(), e.toString()
            )
        }
    }
    return backends.firstOrNull() ?: throw IllegalStateException(
        "No AgentRuntime available. Add an AI provider (e.g. atmosphere-langchain4j) to the classpath."
    )
}

/**
 * Same tolerance as `AgentProcessor.resolveMetrics`: a metrics provider on the
 * classpath whose optional dependency is missing (e.g. `MicrometerAiMetrics`
 * without Micrometer) raises a [ServiceConfigurationError] from the loader —
 * that must degrade to [AiMetrics.NOOP], never abort agent registration.
 */
private fun resolveMetrics(): AiMetrics =
    try {
        ServiceLoader.load(AiMetrics::class.java).findFirst().orElse(AiMetrics.NOOP)
    } catch (e: Exception) {
        logger.debug("AiMetrics provider not available: {}", e.message)
        AiMetrics.NOOP
    } catch (e: ServiceConfigurationError) {
        logger.debug("AiMetrics provider not instantiable: {}", e.message)
        AiMetrics.NOOP
    } catch (e: NoClassDefFoundError) {
        logger.debug("AiMetrics provider not loadable: {}", e.message)
        AiMetrics.NOOP
    }

/**
 * The `@Prompt` target for DSL-declared agents: streams the user's message
 * through the endpoint's runtime. Mirrors `AgentProcessor.SyntheticPrompt`,
 * which is what an `@Agent` without an explicit `@Prompt` method gets.
 */
internal class DslPromptHandler {

    @Prompt
    fun onPrompt(message: String, session: StreamingSession) {
        session.stream(message)
    }
}
