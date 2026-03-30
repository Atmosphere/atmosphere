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

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.dsl.prompt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.StreamingSession
import org.slf4j.LoggerFactory

/**
 * [AgentRuntime] implementation backed by JetBrains Koog.
 *
 * Auto-detected when `koog-agents` is on the classpath. The [PromptExecutor]
 * must be configured via [setPromptExecutor] — typically done by Spring
 * auto-configuration or application code.
 */
class KoogAgentRuntime : AgentRuntime {

    companion object {
        private val logger = LoggerFactory.getLogger(KoogAgentRuntime::class.java)

        @Volatile
        private var promptExecutor: PromptExecutor? = null

        @Volatile
        private var defaultModel: LLModel = LLModel(LLMProvider.OpenAI, "gpt-4o")

        @JvmStatic
        fun setPromptExecutor(executor: PromptExecutor) {
            promptExecutor = executor
        }

        @JvmStatic
        fun setDefaultModel(model: LLModel) {
            defaultModel = model
        }
    }

    override fun name(): String = "koog"

    override fun isAvailable(): Boolean {
        return try {
            Class.forName("ai.koog.agents.core.agent.AIAgent")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    override fun priority(): Int = 100

    override fun configure(settings: AiConfig.LlmSettings) {
        if (promptExecutor != null) {
            return
        }
        logger.info(
            "Koog adapter active but requires a PromptExecutor bean. " +
                "Ensure koog-spring-boot-starter is on the classpath or " +
                "call KoogAgentRuntime.setPromptExecutor() manually."
        )
    }

    override fun execute(context: AgentExecutionContext, session: StreamingSession) {
        val executor = promptExecutor
            ?: throw IllegalStateException(
                "KoogAgentRuntime: PromptExecutor not configured. " +
                    "Call KoogAgentRuntime.setPromptExecutor() or use Spring auto-configuration."
            )

        // Use the default model (which has proper capabilities and context length)
        // unless the context specifies a different model name.
        val model = if (context.model() != null && context.model() != defaultModel.id) {
            // For an overridden model, copy capabilities from defaultModel
            LLModel(defaultModel.provider, context.model(),
                defaultModel.capabilities, defaultModel.contextLength, defaultModel.maxOutputTokens)
        } else {
            defaultModel
        }

        val koogPrompt = prompt("atmosphere") {
            if (context.systemPrompt() != null) {
                system(context.systemPrompt())
            }
            // Include conversation history
            for (msg in context.history()) {
                when (msg.role()) {
                    "user" -> user(msg.content())
                    "assistant" -> assistant(msg.content())
                    "system" -> system(msg.content())
                    else -> user(msg.content())
                }
            }
            user(context.message())
        }

        session.emit(AiEvent.Progress("Streaming with Koog...", null))

        try {
            runBlocking {
                executor.executeStreaming(koogPrompt, model).collect { frame ->
                    if (session.isClosed) return@collect

                    when (frame) {
                        is StreamFrame.TextDelta -> {
                            session.emit(AiEvent.TextDelta(frame.text))
                        }
                        is StreamFrame.TextComplete -> {
                            session.emit(AiEvent.TextComplete(frame.text))
                        }
                        is StreamFrame.ToolCallDelta -> {
                            // Accumulated during streaming; handled on ToolCallComplete
                        }
                        is StreamFrame.ToolCallComplete -> {
                            session.emit(
                                AiEvent.AgentStep(
                                    frame.name,
                                    "Tool call: ${frame.name}",
                                    emptyMap()
                                )
                            )
                        }
                        is StreamFrame.ReasoningDelta -> {
                            val text = frame.text
                            if (text != null) {
                                session.emit(AiEvent.Progress(text, null))
                            }
                        }
                        is StreamFrame.ReasoningComplete -> {
                            // Final reasoning summary; already streamed via deltas
                        }
                        is StreamFrame.End -> {
                            // Stream complete; session.complete() called below
                        }
                    }
                }
            }
            session.complete()
        } catch (e: Exception) {
            logger.error("Koog streaming failed", e)
            session.error(e)
        }
    }

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.TEXT_STREAMING,
        AiCapability.TOOL_CALLING,
        AiCapability.STRUCTURED_OUTPUT,
        AiCapability.AGENT_ORCHESTRATION,
        AiCapability.CONVERSATION_MEMORY,
        AiCapability.SYSTEM_PROMPT
    )
}
