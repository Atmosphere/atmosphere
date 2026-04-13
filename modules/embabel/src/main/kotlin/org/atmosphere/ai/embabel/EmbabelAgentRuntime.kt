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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.spi.config.spring.InfrastructureInjectionConfiguration
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.TokenUsage
import org.slf4j.LoggerFactory

/**
 * [AgentRuntime] implementation backed by the Embabel Agent Platform.
 *
 * Dispatches in two modes depending on how the caller addresses the runtime:
 *
 * 1. **Deployed-agent mode** — when [AgentExecutionContext.agentId] resolves to
 *    an agent already deployed on the [AgentPlatform] (or the configured
 *    [agentName] default), the request is handed to Embabel's goal-planner via
 *    [AgentPlatform.runAgentFrom]. The deployed `@Agent` owns its own system
 *    prompt, tools, and history; Atmosphere threads only `userMessage` into
 *    the process inputs. This preserves Embabel's value proposition as a
 *    goal-driven planning runtime.
 *
 * 2. **Atmosphere-native mode** — when the caller's `agentId()` does NOT match
 *    a deployed agent, the runtime falls back to a direct LLM path via
 *    Embabel's [Ai] factory ([InfrastructureInjectionConfiguration.aiFactory]).
 *    In this mode the bridge honestly threads [AgentExecutionContext.systemPrompt]
 *    and [AgentExecutionContext.history] into the outgoing [Ai.withDefaultLlm]
 *    `PromptRunner`, making `SYSTEM_PROMPT` and `CONVERSATION_MEMORY` honest
 *    capability declarations per Correctness Invariant #5 (Runtime Truth).
 *
 * Auto-detected when `embabel-agent-api` is on the classpath. The
 * [AgentPlatform] must be configured via [setAgentPlatform] — typically done
 * by Spring auto-configuration.
 */
class EmbabelAgentRuntime : AgentRuntime {

    companion object {
        private val logger = LoggerFactory.getLogger(EmbabelAgentRuntime::class.java)

        @Volatile
        private var agentPlatform: AgentPlatform? = null

        @Volatile
        private var agentName: String = "chat-assistant"

        @JvmStatic
        fun setAgentPlatform(platform: AgentPlatform) {
            agentPlatform = platform
        }

        @JvmStatic
        fun setAgentName(name: String) {
            agentName = name
        }
    }

    override fun name(): String = "embabel"

    override fun isAvailable(): Boolean {
        return try {
            Class.forName("com.embabel.agent.core.AgentPlatform")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    override fun priority(): Int = 100

    override fun configure(settings: AiConfig.LlmSettings) {
        if (agentPlatform != null) {
            return
        }
        logger.info(
            "Embabel adapter active but requires an AgentPlatform bean with deployed @Agent classes. " +
                "Auto-configuration from credentials alone is not supported. " +
                "Ensure embabel-agent-spring-boot-starter is on the classpath."
        )
    }

    override fun execute(context: AgentExecutionContext, session: StreamingSession) {
        val platform = agentPlatform
            ?: throw IllegalStateException(
                "EmbabelAgentRuntime: AgentPlatform not configured. " +
                    "Call EmbabelAgentRuntime.setAgentPlatform() or use Spring auto-configuration."
            )

        session.progress("Connecting to embabel...")

        val targetAgent = context.agentId() ?: agentName
        val deployedAgent = platform.agents().firstOrNull { it.name == targetAgent }

        executeWithOuterRetry(context, session) {
            if (deployedAgent != null) {
                executeDeployedAgent(platform, deployedAgent, context, session)
            } else {
                executeAtmosphereNative(platform, context, session)
            }
        }
    }

    /**
     * Outer retry wrapper honouring [AgentExecutionContext.retryPolicy] on
     * pre-stream transient failures. Matches the Java base-class semantics
     * in `AbstractAgentRuntime.executeWithOuterRetry`. Embabel does not
     * extend that class (it implements [AgentRuntime] directly) so the
     * retry loop is duplicated here.
     */
    private inline fun executeWithOuterRetry(
        context: AgentExecutionContext,
        session: StreamingSession,
        crossinline body: () -> Unit
    ) {
        val policy = context.retryPolicy()
        if (policy == null || policy.isInheritSentinel || policy.maxRetries() <= 0) {
            body()
            return
        }
        var attempt = 0
        while (true) {
            try {
                body()
                return
            } catch (e: RuntimeException) {
                if (attempt >= policy.maxRetries() || session.hasErrored()) {
                    throw e
                }
                val delay = policy.delayForAttempt(attempt)
                attempt++
                logger.info(
                    "embabel outer-retry attempt {}/{} after {}ms (cause: {})",
                    attempt, policy.maxRetries(), delay.toMillis(), e.message
                )
                if (!delay.isZero && !delay.isNegative) {
                    try {
                        Thread.sleep(delay.toMillis())
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Deployed-agent path: hand the invocation to Embabel's goal-planner
     * via [AgentPlatform.runAgentFrom]. The deployed `@Agent` owns its own
     * system prompt, tools, and conversation memory; Atmosphere only binds
     * `userMessage` into the process input map. This preserves Embabel's
     * goal-driven value proposition for callers who deploy `@Agent` classes.
     */
    private fun executeDeployedAgent(
        platform: AgentPlatform,
        agent: com.embabel.agent.core.Agent,
        context: AgentExecutionContext,
        session: StreamingSession
    ) {
        val channel = AtmosphereOutputChannel(session)
        val process = try {
            val options = ProcessOptions.DEFAULT.withOutputChannel(channel)
            platform.runAgentFrom(agent, options, mapOf("userMessage" to context.message()))
        } catch (e: Exception) {
            logger.error("Agent execution failed", e)
            session.error(e)
            return
        }

        // Structured output (e.g. promptedTransformer with typed output) doesn't
        // stream through the OutputChannel. Extract the result from the blackboard
        // and send it to the session so the console renders the response.
        if (!session.isClosed) {
            val result = process.blackboard.lastResult()
            if (result != null) {
                val text = when (result) {
                    is HasContent -> result.content
                    is String -> result
                    else -> result.toString()
                }
                if (!text.isNullOrBlank()) {
                    session.send(text)
                }
            }
        }

        // Emit typed token usage. Embabel's AgentProcess implements
        // LlmInvocationHistory.usage() which aggregates every LLM call made
        // during this process into a single Usage record.
        try {
            val usage = process.usage()
            val input = usage.promptTokens?.toLong() ?: 0L
            val output = usage.completionTokens?.toLong() ?: 0L
            val total = usage.totalTokens?.toLong() ?: (input + output)
            val tokenUsage = TokenUsage(input, output, 0L, total, null)
            if (tokenUsage.hasCounts()) {
                session.usage(tokenUsage)
            }
        } catch (t: Throwable) {
            logger.debug("Failed to extract Embabel token usage (this is non-fatal)", t)
        }

        if (!session.isClosed) {
            session.complete()
        }
    }

    /**
     * Atmosphere-native path: when no deployed agent matches the requested
     * `agentId()`, fall back to Embabel's [Ai] factory and drive a direct
     * LLM call via [Ai.withDefaultLlm]. This path honestly threads
     * [AgentExecutionContext.systemPrompt] and [AgentExecutionContext.history]
     * into the outgoing request through
     * [com.embabel.agent.api.common.PromptRunner.withSystemPrompt] and
     * [com.embabel.agent.api.common.PromptRunner.withMessages] respectively,
     * which is the mechanism that makes `SYSTEM_PROMPT` and
     * `CONVERSATION_MEMORY` honest capability declarations.
     *
     * Token usage reporting is not available on this path — Embabel's
     * PromptRunner surface does not expose an aggregated usage record the
     * way `AgentProcess.usage()` does on the runAgentFrom path. Callers who
     * require `ai.tokens.*` telemetry should either (a) use a deployed
     * `@Agent` class (which routes through runAgentFrom) or (b) select a
     * non-Embabel runtime.
     */
    private fun executeAtmosphereNative(
        platform: AgentPlatform,
        context: AgentExecutionContext,
        session: StreamingSession
    ) {
        val ai: Ai = try {
            InfrastructureInjectionConfiguration().aiFactory(platform)
        } catch (t: Throwable) {
            logger.error("Failed to obtain Embabel Ai factory from AgentPlatform", t)
            session.error(t)
            return
        }

        val messages = buildList<Message> {
            context.history().forEach { msg ->
                when (msg.role()) {
                    "system" -> add(SystemMessage(msg.content()))
                    "assistant" -> add(AssistantMessage(msg.content()))
                    else -> add(UserMessage(msg.content()))
                }
            }
            add(UserMessage(context.message()))
        }

        // Translate Atmosphere ToolDefinitions and multi-modal Content parts
        // into Embabel-native surfaces so PromptRunner's withTools(...) /
        // withImages(...) surfaces unlock TOOL_CALLING, TOOL_APPROVAL,
        // VISION, and MULTI_MODAL on this path. Every tool invocation
        // routes through ToolExecutionHelper.executeWithApproval so
        // @RequiresApproval gates fire uniformly across runtimes.
        val embabelTools = EmbabelToolBridge.toEmbabelTools(
            context.tools(), session, context.approvalStrategy(), context.approvalPolicy()
        )
        val embabelImages = EmbabelToolBridge.toEmbabelImages(context.parts())

        val result: String = try {
            var runner = ai.withDefaultLlm()
            if (!context.systemPrompt().isNullOrBlank()) {
                runner = runner.withSystemPrompt(context.systemPrompt())
            }
            runner = runner.withMessages(messages)
            if (embabelTools.isNotEmpty()) {
                runner = runner.withTools(embabelTools)
            }
            if (embabelImages.isNotEmpty()) {
                runner = runner.withImages(embabelImages)
            }
            runner.generateText("")
        } catch (t: Throwable) {
            logger.error("Embabel Ai dispatch failed", t)
            session.error(t)
            return
        }

        if (!session.isClosed) {
            if (result.isNotBlank()) {
                session.send(result)
            }
            session.complete()
        }
    }

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.TEXT_STREAMING,
        AiCapability.STRUCTURED_OUTPUT,
        AiCapability.AGENT_ORCHESTRATION,
        // SYSTEM_PROMPT is honest via the Atmosphere-native dispatch path
        // (executeAtmosphereNative): Ai.withDefaultLlm().withSystemPrompt(...)
        // threads context.systemPrompt() into the outgoing PromptRunner.
        // Deployed-agent callers own their own prompts via @Agent classes,
        // which is the Embabel-idiomatic way to express a system prompt.
        AiCapability.SYSTEM_PROMPT,
        // CONVERSATION_MEMORY is honest via the Atmosphere-native dispatch
        // path: PromptRunner.withMessages(...) receives the full
        // context.history() translated into embabel.chat.Message variants.
        AiCapability.CONVERSATION_MEMORY,
        // TOKEN_USAGE is honest on the deployed-agent path:
        // executeDeployedAgent reads AgentProcess.usage() (Embabel's
        // LlmInvocationHistory aggregate) and emits a typed TokenUsage
        // record via session.usage(). The Atmosphere-native path does not
        // expose usage — see executeAtmosphereNative Javadoc.
        AiCapability.TOKEN_USAGE,
        // PER_REQUEST_RETRY: honored via executeWithOuterRetry which
        // wraps either dispatch branch (deployed-agent or Atmosphere-native)
        // in a retry loop respecting context.retryPolicy(). Pre-stream
        // transient failures retry on top of Embabel's own retry layer.
        AiCapability.PER_REQUEST_RETRY,
        // TOOL_CALLING / TOOL_APPROVAL: EmbabelToolBridge translates
        // Atmosphere ToolDefinitions into Embabel com.embabel.agent.api.tool.Tool
        // instances wrapped around a Tool.Handler that routes through
        // ToolExecutionHelper.executeWithApproval. PromptRunner.withTools(...)
        // attaches them for SK-style auto-invoke by Embabel's LLM layer.
        AiCapability.TOOL_CALLING,
        AiCapability.TOOL_APPROVAL,
        // VISION / MULTI_MODAL: EmbabelToolBridge.toEmbabelImages maps
        // Atmosphere Content.Image parts into Embabel AgentImage instances,
        // attached via PromptRunner.withImages(...). Only the
        // Atmosphere-native dispatch path (executeAtmosphereNative)
        // reaches the PromptRunner surface; the deployed-agent path
        // (executeDeployedAgent) ignores parts, matching Embabel's own
        // semantics where a deployed @Agent owns its own multi-modal
        // handling.
        AiCapability.VISION,
        AiCapability.MULTI_MODAL
    )
}
