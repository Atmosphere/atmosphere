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

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.TypeToken
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.approval.ApprovalStrategy
import org.atmosphere.ai.tool.ToolDefinition
import org.atmosphere.ai.tool.ToolExecutionHelper
import org.slf4j.LoggerFactory

/**
 * Bridges Atmosphere [ToolDefinition] instances to Koog [Tool] instances
 * that can be registered in a [ToolRegistry] for use with [ai.koog.agents.core.agent.AIAgent].
 *
 * Tool invocation goes through [ToolExecutionHelper.executeWithApproval] so that
 * Atmosphere's session-scoped HITL gate honours `@RequiresApproval` uniformly across
 * every runtime — the suspend body parks on the underlying virtual thread when the
 * strategy is waiting for the client to approve or deny.
 */
object AtmosphereToolBridge {

    private val logger = LoggerFactory.getLogger(AtmosphereToolBridge::class.java)

    /**
     * Build a [ToolRegistry] from Atmosphere tool definitions bound to the current
     * streaming session and HITL strategy. Callers should rebuild the registry per
     * request so each tool invocation sees the session that requested it.
     */
    fun buildRegistry(
        tools: List<ToolDefinition>,
        session: StreamingSession,
        strategy: ApprovalStrategy?,
        listeners: List<org.atmosphere.ai.AgentLifecycleListener>,
        policy: org.atmosphere.ai.approval.ToolApprovalPolicy? = null
    ): ToolRegistry {
        val registry = ToolRegistry.builder().build()
        for (tool in tools) {
            registry.add(wrap(tool, session, strategy, listeners, policy))
        }
        return registry
    }

    /**
     * Wrap a single Atmosphere [ToolDefinition] as a Koog [Tool].
     */
    private fun wrap(
        tool: ToolDefinition,
        session: StreamingSession,
        strategy: ApprovalStrategy?,
        listeners: List<org.atmosphere.ai.AgentLifecycleListener>,
        policy: org.atmosphere.ai.approval.ToolApprovalPolicy?
    ): Tool<JSONObject, String> {
        val descriptor = toDescriptor(tool)

        return object : Tool<JSONObject, String>(
            TypeToken.of(JSONObject::class.java),
            TypeToken.of(String::class.java),
            descriptor
        ) {
            override suspend fun execute(args: JSONObject): String {
                val argMap = jsonObjectToMap(args)
                org.atmosphere.ai.AgentLifecycleListener.fireToolCall(listeners, tool.name(), argMap)
                return try {
                    val result = ToolExecutionHelper.executeWithApproval(
                        tool.name(), tool, argMap, session, strategy, policy
                    )
                    org.atmosphere.ai.AgentLifecycleListener.fireToolResult(listeners, tool.name(), result)
                    result
                } catch (e: Exception) {
                    logger.warn("Tool {} execution failed: {}", tool.name(), e.message)
                    val errorResult = """{"error":"${e.message ?: "Tool execution failed"}"}"""
                    org.atmosphere.ai.AgentLifecycleListener.fireToolResult(listeners, tool.name(), errorResult)
                    errorResult
                }
            }

            override fun decodeArgs(jsonObject: JSONObject, serializer: JSONSerializer): JSONObject {
                return jsonObject
            }

            override fun encodeResult(result: String, serializer: JSONSerializer): JSONElement {
                return JSONPrimitive(result)
            }

            override fun encodeResultToString(result: String, serializer: JSONSerializer): String {
                return result
            }
        }
    }

    private fun toDescriptor(tool: ToolDefinition): ToolDescriptor {
        val required = mutableListOf<ToolParameterDescriptor>()
        val optional = mutableListOf<ToolParameterDescriptor>()

        for (param in tool.parameters()) {
            val koogType = when (param.type().lowercase()) {
                "string" -> ToolParameterType.String
                "integer", "int", "long" -> ToolParameterType.Integer
                "number", "float", "double" -> ToolParameterType.Float
                "boolean", "bool" -> ToolParameterType.Boolean
                else -> ToolParameterType.String
            }
            val descriptor = ToolParameterDescriptor(param.name(), param.description(), koogType)
            if (param.required()) required.add(descriptor) else optional.add(descriptor)
        }

        return ToolDescriptor(tool.name(), tool.description(), required, optional, null)
    }

    /**
     * Convert Koog [JSONObject] to a plain [Map] for Atmosphere's [ToolDefinition.executor].
     */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((key, value) in obj.entries) {
            result[key] = when (value) {
                is JSONPrimitive -> value.content
                is JSONObject -> jsonObjectToMap(value)
                else -> value.toString()
            }
        }
        return result
    }
}
