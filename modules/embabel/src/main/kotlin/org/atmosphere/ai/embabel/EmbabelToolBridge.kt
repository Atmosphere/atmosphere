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

import com.embabel.agent.api.common.AgentImage
import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.databind.ObjectMapper
import org.atmosphere.ai.Content
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.approval.ApprovalStrategy
import org.atmosphere.ai.approval.ToolApprovalPolicy
import org.atmosphere.ai.tool.ToolDefinition
import org.atmosphere.ai.tool.ToolExecutionHelper
import org.atmosphere.ai.tool.ToolParameter

/**
 * Bridges Atmosphere's [ToolDefinition] list to Embabel's
 * [com.embabel.agent.api.tool.Tool] list, and Atmosphere [Content.Image]
 * parts to Embabel [AgentImage] instances, so the
 * [EmbabelAgentRuntime.executeAtmosphereNative] dispatch path can reach
 * `PromptRunner.withTools(...)` and `PromptRunner.withImages(...)` — the
 * surface that unlocks honest `TOOL_CALLING`, `TOOL_APPROVAL`, `VISION`,
 * and `MULTI_MODAL` capability declarations on the Embabel runtime.
 *
 * <p>Every tool invocation routes through
 * [ToolExecutionHelper.executeWithApproval] so `@RequiresApproval` gates
 * fire uniformly with the other runtime bridges (Correctness Invariant #7,
 * Mode Parity).</p>
 *
 * <p><b>Why this file exists:</b> the original Embabel bridge went through
 * `AgentPlatform.runAgentFrom(agent, ..., "userMessage" → message)`, which
 * silently dropped every tool, image, system prompt, and history entry on
 * the Atmosphere invocation payload. The runtime declared `SYSTEM_PROMPT`
 * dishonestly. Phase 1 of the honesty pass added the native `PromptRunner`
 * fallback for system-prompt / conversation-memory; this file closes the
 * remaining gap by wiring tools + images through the same fallback.</p>
 */
internal object EmbabelToolBridge {

    private val mapper: ObjectMapper = ObjectMapper()

    /**
     * Translate a list of Atmosphere [ToolDefinition]s into a list of
     * Embabel [Tool]s. Each Embabel Tool wraps a [Tool.Handler] whose
     * `handle(json)` implementation deserializes the LLM-produced JSON
     * arg string into a `Map<String, Object>` and routes through
     * [ToolExecutionHelper.executeWithApproval].
     */
    fun toEmbabelTools(
        tools: List<ToolDefinition>,
        session: StreamingSession,
        approvalStrategy: ApprovalStrategy?,
        approvalPolicy: ToolApprovalPolicy?
    ): List<Tool> {
        if (tools.isEmpty()) return emptyList()
        return tools.map { toEmbabelTool(it, session, approvalStrategy, approvalPolicy) }
    }

    /**
     * Build a single Embabel [Tool] from an Atmosphere [ToolDefinition].
     * Exposed package-private for direct unit testing of the handler
     * routing contract.
     */
    internal fun toEmbabelTool(
        tool: ToolDefinition,
        session: StreamingSession,
        approvalStrategy: ApprovalStrategy?,
        approvalPolicy: ToolApprovalPolicy?
    ): Tool {
        val schemaParams = tool.parameters().map { toEmbabelParameter(it) }.toTypedArray()
        val schema: Tool.InputSchema = Tool.InputSchema.of(*schemaParams)

        val effectivePolicy = approvalPolicy ?: ToolApprovalPolicy.annotated()

        val handler = Tool.Handler { json ->
            val args: Map<String, Any?> = parseArgs(json)
            try {
                val result = ToolExecutionHelper.executeWithApproval(
                    tool.name(), tool, args, session, approvalStrategy, effectivePolicy
                )
                Tool.Result.text(result ?: "")
            } catch (t: Throwable) {
                Tool.Result.error(
                    "Tool '${tool.name()}' failed: ${t.message ?: t.javaClass.simpleName}",
                    t
                )
            }
        }

        return Tool.create(tool.name(), tool.description(), schema, handler)
    }

    /**
     * Map an Atmosphere [ToolParameter] to an Embabel [Tool.Parameter].
     * Atmosphere's JSON-Schema type strings align 1:1 with Embabel's
     * [Tool.ParameterType] enum values.
     */
    private fun toEmbabelParameter(p: ToolParameter): Tool.Parameter {
        val type = when (p.type().lowercase()) {
            "string"  -> Tool.ParameterType.STRING
            "integer" -> Tool.ParameterType.INTEGER
            "number"  -> Tool.ParameterType.NUMBER
            "boolean" -> Tool.ParameterType.BOOLEAN
            "array"   -> Tool.ParameterType.ARRAY
            "object"  -> Tool.ParameterType.OBJECT
            else      -> Tool.ParameterType.STRING
        }
        return Tool.Parameter(
            p.name(),
            type,
            p.description(),
            p.required(),
            emptyList<String>(),
            emptyList<Tool.Parameter>(),
            null
        )
    }

    /**
     * Deserialize the LLM-produced args JSON string into a
     * `Map<String, Any?>`. Returns an empty map on malformed input so a
     * flaky LLM response degrades to a no-op call instead of throwing
     * mid-stream.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseArgs(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            mapper.readValue(json, Map::class.java) as Map<String, Any?>
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    /**
     * Translate Atmosphere [Content.Image] parts into Embabel [AgentImage]
     * instances. Returns an empty list when no images are present so the
     * `PromptRunner.withImages` call can be skipped on the text-only fast
     * path. Non-image content is ignored here (audio / file / text parts
     * are handled elsewhere in [EmbabelAgentRuntime.executeAtmosphereNative]).
     */
    fun toEmbabelImages(parts: List<Content>): List<AgentImage> {
        if (parts.isEmpty()) return emptyList()
        val images = mutableListOf<AgentImage>()
        for (p in parts) {
            if (p is Content.Image) {
                images.add(AgentImage(p.mimeType(), p.data()))
            }
        }
        return images
    }
}
