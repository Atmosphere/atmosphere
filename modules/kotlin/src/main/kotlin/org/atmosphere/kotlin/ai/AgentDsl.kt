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
import org.atmosphere.ai.tool.ToolDefinition
import org.atmosphere.ai.tool.ToolExecutor
import org.atmosphere.ai.tool.ToolParameter
import org.atmosphere.kotlin.AtmosphereDsl

/**
 * Declarative description of an agent built with the Kotlin [agent] DSL.
 *
 * A spec is inert: it holds what the developer declared and nothing else.
 * Wiring happens in [org.atmosphere.kotlin.ai.registerAgent], which hands the
 * declaration to the very same framework machinery the `@Agent` /
 * `@AiEndpoint` annotation path uses — one AI stack, two front-ends.
 */
class AgentSpec internal constructor(
    val name: String,
    val path: String,
    val systemPrompt: String,
    val model: String?,
    val memoryEnabled: Boolean,
    val maxHistory: Int,
    val tools: List<ToolDefinition>,
    val runtime: AgentRuntime?,
    val suspendTimeoutMillis: Long
)

/**
 * Builder behind `agent("name") { ... }`.
 *
 * ```kotlin
 * val spec = agent("support") {
 *     systemPrompt = "You are a concise support assistant."
 *     model = "gpt-4o-mini"          // optional; falls back to atmosphere.ai config
 *     maxHistory = 20                // conversation memory window
 *
 *     tool("order_status", "Look up the status of an order") {
 *         param("orderId", "The order identifier")
 *         execute { args -> lookup(args["orderId"] as String) }
 *     }
 * }
 * ```
 */
@AtmosphereDsl
class AgentBuilder internal constructor(private val name: String) {

    /** System prompt handed to the runtime on every turn. */
    var systemPrompt: String = ""

    /** Per-agent model override; `null` uses the configured `atmosphere.ai` model. */
    var model: String? = null

    /** Whether the agent keeps conversation memory (on by default, like `@Agent`). */
    var memory: Boolean = true

    /** Number of remembered messages when [memory] is on. */
    var maxHistory: Int = DEFAULT_MAX_HISTORY

    /**
     * Handler path. Defaults to the same `/atmosphere/agent/{name}` mapping the
     * `@Agent` annotation processor registers, so a DSL agent is reachable by
     * the browser client exactly like an annotated one.
     */
    var path: String = "/atmosphere/agent/$name"

    /** Suspend timeout in milliseconds for the streaming HTTP/WebSocket path. */
    var suspendTimeoutMillis: Long = DEFAULT_SUSPEND_TIMEOUT_MS

    /**
     * Explicit runtime binding. Leave `null` (the default) to let
     * [org.atmosphere.ai.AgentRuntimeResolver] pick the highest-priority
     * available runtime on the classpath — the same resolution the annotation
     * path performs.
     */
    var runtime: AgentRuntime? = null

    private val declaredTools = mutableListOf<ToolDefinition>()

    /**
     * Declare a tool as a lambda. The resulting [ToolDefinition] is identical
     * to what `@AiTool` method scanning produces, so any runtime that supports
     * tool calling sees no difference between the two declaration styles.
     */
    fun tool(name: String, description: String, init: ToolBuilder.() -> Unit) {
        declaredTools += ToolBuilder(name, description).apply(init).build()
    }

    /** Register an already-built tool definition (e.g. shared across agents). */
    fun tool(definition: ToolDefinition) {
        declaredTools += definition
    }

    internal fun build(): AgentSpec {
        require(name.isNotBlank()) { "agent name must not be blank" }
        require(maxHistory >= 2) { "maxHistory must be >= 2, got $maxHistory" }
        require(suspendTimeoutMillis > 0) {
            "suspendTimeoutMillis must be > 0, got $suspendTimeoutMillis"
        }
        val duplicates = declaredTools.groupBy { it.name() }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate tool name(s) declared: $duplicates" }
        return AgentSpec(
            name = name,
            path = path,
            systemPrompt = systemPrompt,
            model = model?.takeIf { it.isNotBlank() },
            memoryEnabled = memory,
            maxHistory = maxHistory,
            tools = declaredTools.toList(),
            runtime = runtime,
            suspendTimeoutMillis = suspendTimeoutMillis
        )
    }

    companion object {
        /** Same conversation window the `@Agent` processor defaults to. */
        const val DEFAULT_MAX_HISTORY: Int = 20

        /** Same suspend timeout the `@Agent` processor passes to the handler. */
        const val DEFAULT_SUSPEND_TIMEOUT_MS: Long = 120_000L
    }
}

/**
 * Builder behind `tool("name", "description") { ... }`.
 */
@AtmosphereDsl
class ToolBuilder internal constructor(
    private val name: String,
    private val description: String
) {

    /** JSON Schema type of the return value. */
    var returnType: String = "string"

    private val parameters = mutableListOf<ToolParameter>()
    private var body: ((Map<String, Any?>) -> Any?)? = null
    private var approvalMessage: String? = null
    private var approvalTimeoutSeconds: Long = 0

    /**
     * Declare a parameter the model may (or must) supply.
     *
     * @param type JSON Schema type — `string`, `integer`, `number`, `boolean`,
     *             `object` or `array`
     */
    fun param(
        name: String,
        description: String,
        type: String = "string",
        required: Boolean = true
    ) {
        parameters += ToolParameter(name, description, type, required)
    }

    /**
     * Require human approval before this tool runs. Routed through the
     * framework's `ApprovalRegistry`, exactly like `@RequiresApproval`.
     */
    fun requiresApproval(message: String, timeoutSeconds: Long = 0) {
        approvalMessage = message
        approvalTimeoutSeconds = timeoutSeconds
    }

    /** The tool body. Arguments arrive keyed by the names declared with [param]. */
    fun execute(body: (Map<String, Any?>) -> Any?) {
        this.body = body
    }

    internal fun build(): ToolDefinition {
        val handler = checkNotNull(body) {
            "tool '$name' declares no execute { } body"
        }
        val builder = ToolDefinition.builder(name, description)
        parameters.forEach { builder.parameter(it.name(), it.description(), it.type(), it.required()) }
        builder.returnType(returnType)
        builder.executor(ToolExecutor { args -> handler(args) })
        approvalMessage?.let { builder.requiresApproval(it, approvalTimeoutSeconds) }
        return builder.build()
    }
}

/**
 * Declare an agent. The returned [AgentSpec] is a plain value — hand it to
 * [org.atmosphere.kotlin.ai.registerAgent] to wire it into a running
 * [org.atmosphere.cpr.AtmosphereFramework].
 */
fun agent(name: String, init: AgentBuilder.() -> Unit): AgentSpec =
    AgentBuilder(name).apply(init).build()
