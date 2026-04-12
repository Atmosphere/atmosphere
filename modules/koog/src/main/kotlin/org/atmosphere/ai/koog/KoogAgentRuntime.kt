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

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.ext.agent.chatAgentStrategy
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.ExecutionHandle
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.TokenUsage
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AgentRuntime] implementation backed by JetBrains Koog.
 *
 * Supports tool calling (with automatic tool loop), RAG context injection,
 * structured output via JSON schema, and conversation memory.
 */
class KoogAgentRuntime : AgentRuntime {

    companion object {
        private val logger = LoggerFactory.getLogger(KoogAgentRuntime::class.java)
        private const val MAX_TOOL_ROUNDS = 5

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
        executeInternal(context, session, AtomicBoolean())
    }

    override fun executeWithHandle(
        context: AgentExecutionContext, session: StreamingSession
    ): ExecutionHandle {
        val cancelled = AtomicBoolean()
        val done = CompletableFuture<Void>()
        val activeJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>()
        Thread.startVirtualThread {
            try {
                executeInternal(context, session, cancelled, activeJob)
                done.complete(null)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        return object : ExecutionHandle {
            override fun cancel() {
                cancelled.set(true)
                activeJob.get()?.cancel()
            }
            override fun isDone(): Boolean = done.isDone
            override fun whenDone(): CompletableFuture<Void> = done
        }
    }

    private fun executeInternal(
        context: AgentExecutionContext,
        session: StreamingSession,
        cancelled: AtomicBoolean,
        activeJob: java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?> =
            java.util.concurrent.atomic.AtomicReference()
    ) {
        val executor = promptExecutor
            ?: throw IllegalStateException(
                "KoogAgentRuntime: PromptExecutor not configured. " +
                    "Call KoogAgentRuntime.setPromptExecutor() or use Spring auto-configuration."
            )

        session.progress("Connecting to koog...")

        val model = if (context.model() != null && context.model() != defaultModel.id) {
            LLModel(defaultModel.provider, context.model(),
                defaultModel.capabilities, defaultModel.contextLength, defaultModel.maxOutputTokens)
        } else {
            defaultModel
        }

        if (context.tools().isNotEmpty()) {
            executeWithAgent(executor, model, context, session, cancelled, activeJob)
        } else {
            executeWithExecutor(executor, model, context, session, cancelled, activeJob)
        }
    }

    /**
     * Uses Koog's [AIAgent] with [chatAgentStrategy] for full agent orchestration.
     * The agent handles the tool calling loop automatically; we bridge events
     * to the [StreamingSession] via event handlers.
     */
    private fun executeWithAgent(
        executor: PromptExecutor, model: LLModel,
        context: AgentExecutionContext, session: StreamingSession,
        cancelled: AtomicBoolean,
        activeJob: java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?> =
            java.util.concurrent.atomic.AtomicReference()
    ) {
        val toolRegistry = AtmosphereToolBridge.buildRegistry(
            context.tools(), session, context.approvalStrategy(), context.listeners(),
            context.approvalPolicy()
        )
        val systemPrompt = buildSystemPrompt(context)

        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            systemPrompt = systemPrompt,
            toolRegistry = toolRegistry,
            maxIterations = MAX_TOOL_ROUNDS * 2
        ) {
            handleEvents {
                onLLMStreamingFrameReceived { ctx ->
                    if (cancelled.get() || session.isClosed) return@onLLMStreamingFrameReceived
                    when (val frame = ctx.streamFrame) {
                        is StreamFrame.TextDelta -> session.emit(AiEvent.TextDelta(frame.text))
                        is StreamFrame.TextComplete -> session.emit(AiEvent.TextComplete(frame.text))
                        is StreamFrame.ReasoningDelta -> {
                            val text = frame.text
                            if (text != null) session.emit(AiEvent.Progress(text, null))
                        }
                        else -> {}
                    }
                }
                onToolCallStarting { ctx ->
                    session.emit(AiEvent.ToolStart(ctx.toolName, emptyMap()))
                }
                onToolCallCompleted { ctx ->
                    session.emit(AiEvent.ToolResult(ctx.toolName, ctx.toolResult?.toString()))
                }
                onToolCallFailed { ctx ->
                    session.emit(AiEvent.ToolError(ctx.toolName, ctx.error?.message ?: "Tool failed"))
                }
                onLLMCallCompleted { ctx ->
                    // Phase 1: report typed token usage once per LLM call. The default
                    // StreamingSession.usage() sink re-emits legacy ai.tokens.* metadata keys
                    // so existing Micrometer / budget consumers keep working.
                    val responses = ctx.responses
                    if (responses.isNotEmpty()) {
                        val meta = responses.last().metaInfo
                        val input = meta.inputTokensCount?.toLong() ?: 0L
                        val output = meta.outputTokensCount?.toLong() ?: 0L
                        val total = meta.totalTokensCount?.toLong() ?: (input + output)
                        val usage = TokenUsage(input, output, 0L, total, null)
                        if (usage.hasCounts()) session.usage(usage)
                    }
                }
            }
        }

        try {
            runBlocking {
                activeJob.set(coroutineContext[kotlinx.coroutines.Job])
                val result = agent.run(context.message())
                if (result != null && result.isNotBlank()) {
                    logger.debug("Agent completed with result length: {}", result.length)
                }
                agent.close()
            }
            session.complete()
        } catch (e: Exception) {
            logger.error("Koog agent execution failed", e)
            session.error(e)
        }
    }

    /**
     * Direct executor path for simple prompts without tools.
     * Streams frames in real-time via [PromptExecutor.executeStreaming].
     */
    private fun executeWithExecutor(
        executor: PromptExecutor, model: LLModel,
        context: AgentExecutionContext, session: StreamingSession,
        cancelled: AtomicBoolean,
        activeJob: java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?> =
            java.util.concurrent.atomic.AtomicReference()
    ) {
        val koogPrompt = buildPrompt(context)

        try {
            runBlocking {
                activeJob.set(coroutineContext[kotlinx.coroutines.Job])
                executor.executeStreaming(koogPrompt, model).collect { frame ->
                    if (cancelled.get() || session.isClosed) return@collect
                    when (frame) {
                        is StreamFrame.TextDelta -> session.emit(AiEvent.TextDelta(frame.text))
                        is StreamFrame.TextComplete -> session.emit(AiEvent.TextComplete(frame.text))
                        is StreamFrame.ReasoningDelta -> {
                            val text = frame.text
                            if (text != null) session.emit(AiEvent.Progress(text, null))
                        }
                        is StreamFrame.ReasoningComplete -> {}
                        is StreamFrame.ToolCallDelta -> {}
                        is StreamFrame.ToolCallComplete -> {}
                        is StreamFrame.End -> {}
                    }
                }
            }
            session.complete()
        } catch (e: Exception) {
            logger.error("Koog streaming failed", e)
            session.error(e)
        }
    }

    /**
     * Builds the Koog prompt from the execution context, including:
     * - System prompt (with RAG context appended if available)
     * - Structured output JSON schema instructions
     * - Conversation history
     * - Current user message
     */
    private fun buildPrompt(context: AgentExecutionContext): Prompt {
        return prompt("atmosphere") {
            // System prompt with optional RAG context
            val systemParts = mutableListOf<String>()
            if (context.systemPrompt() != null) {
                systemParts.add(context.systemPrompt())
            }

            // RAG: retrieve relevant documents and inject into system prompt
            if (context.contextProviders().isNotEmpty()) {
                val ragContext = StringBuilder()
                for (provider in context.contextProviders()) {
                    if (!provider.isAvailable) continue
                    val query = provider.transformQuery(context.message())
                    val docs = provider.retrieve(query, 5)
                    val reranked = provider.rerank(query, docs)
                    for (doc in reranked) {
                        ragContext.append("\n---\n").append(doc.content())
                        if (doc.source() != null) {
                            ragContext.append("\n[Source: ").append(doc.source()).append("]")
                        }
                    }
                }
                if (ragContext.isNotEmpty()) {
                    systemParts.add(
                        "Use the following retrieved context to inform your answer:" +
                            ragContext.toString()
                    )
                }
            }

            // Structured output: append JSON schema instructions
            if (context.responseType() != null) {
                systemParts.add(
                    "Respond with valid JSON conforming to the schema for: ${context.responseType().simpleName}. " +
                        "Do not include any text outside the JSON object."
                )
            }

            if (systemParts.isNotEmpty()) {
                system(systemParts.joinToString("\n\n"))
            }

            // Conversation history
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
    }

    /**
     * Build the system prompt string from context (for the AIAgent path).
     */
    private fun buildSystemPrompt(context: AgentExecutionContext): String {
        val parts = mutableListOf<String>()
        if (context.systemPrompt() != null) parts.add(context.systemPrompt())

        if (context.contextProviders().isNotEmpty()) {
            val ragContext = StringBuilder()
            for (provider in context.contextProviders()) {
                if (!provider.isAvailable) continue
                val query = provider.transformQuery(context.message())
                val docs = provider.retrieve(query, 5)
                val reranked = provider.rerank(query, docs)
                for (doc in reranked) {
                    ragContext.append("\n---\n").append(doc.content())
                    if (doc.source() != null) ragContext.append("\n[Source: ").append(doc.source()).append("]")
                }
            }
            if (ragContext.isNotEmpty()) {
                parts.add("Use the following retrieved context to inform your answer:$ragContext")
            }
        }

        if (context.responseType() != null) {
            parts.add("Respond with valid JSON conforming to the schema for: ${context.responseType().simpleName}.")
        }

        return parts.joinToString("\n\n")
    }

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.TEXT_STREAMING,
        AiCapability.TOOL_CALLING,
        AiCapability.STRUCTURED_OUTPUT,
        AiCapability.AGENT_ORCHESTRATION,
        AiCapability.CONVERSATION_MEMORY,
        AiCapability.SYSTEM_PROMPT,
        // TOOL_APPROVAL is honest: AtmosphereToolBridge wraps every
        // Atmosphere ToolDefinition in a Koog-native tool whose suspend body
        // captures the session + strategy and calls
        // ToolExecutionHelper.executeWithApproval, so @RequiresApproval
        // gates fire uniformly with the other runtimes.
        AiCapability.TOOL_APPROVAL
    )

    override fun models(): List<String> {
        // Koog's LLModel carries an id; report the default plus any
        // override. context.model() takes precedence at dispatch time.
        return listOf(defaultModel.id)
    }
}

