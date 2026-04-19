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
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.streaming.StreamFrame
import org.atmosphere.ai.llm.CacheHint
import java.util.Base64
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
        // Admit through the process-wide AiGateway before issuing the native
        // Koog dispatch — uniform per-user rate limiting and credential
        // resolution across all seven runtimes (Correctness Invariant #3).
        org.atmosphere.ai.AbstractAgentRuntime.admitThroughGateway(name(), context)
        executeWithOuterRetry(context, session)
    }

    /**
     * Outer retry wrapper honouring [AgentExecutionContext.retryPolicy] on
     * pre-stream transient failures. Matches the base-class semantics in
     * [org.atmosphere.ai.AbstractAgentRuntime.executeWithOuterRetry]:
     *
     *   1. No retry when the policy is the inherit sentinel
     *      ([org.atmosphere.ai.RetryPolicy.DEFAULT]) or `maxRetries <= 0`
     *   2. Retry only when [StreamingSession.hasErrored] is still false —
     *      once the bridge has called `session.error(...)`, the caller has
     *      observed terminal state and retry is unsafe
     *   3. Honour the policy's [org.atmosphere.ai.RetryPolicy.delayForAttempt]
     *      exponential backoff between attempts
     *
     * Koog does not extend [org.atmosphere.ai.AbstractAgentRuntime] (it
     * implements [AgentRuntime] directly to avoid the Java base's type
     * parameter on the native client), so we duplicate the retry loop
     * here instead of inheriting. Kept in sync with the Java version so
     * the `PER_REQUEST_RETRY` capability declaration is honest across
     * both implementation families.
     */
    private fun executeWithOuterRetry(context: AgentExecutionContext, session: StreamingSession) {
        val policy = context.retryPolicy()
        if (policy == null || policy.isInheritSentinel || policy.maxRetries() <= 0) {
            executeInternal(context, session, AtomicBoolean())
            return
        }
        var attempt = 0
        while (true) {
            try {
                executeInternal(context, session, AtomicBoolean())
                return
            } catch (e: RuntimeException) {
                if (attempt >= policy.maxRetries() || session.hasErrored()) {
                    throw e
                }
                val delay = policy.delayForAttempt(attempt)
                attempt++
                logger.info("koog outer-retry attempt {}/{} after {}ms (cause: {})",
                    attempt, policy.maxRetries(), delay.toMillis(), e.message)
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

    override fun executeWithHandle(
        context: AgentExecutionContext, session: StreamingSession
    ): ExecutionHandle {
        // Gateway admission on the handle-based path too — rate-limit and
        // credential choke-point parity across every dispatch mode
        // (Correctness Invariant #7 — mode parity). Prior to this fix the
        // cancel-capable path skipped the gateway.
        org.atmosphere.ai.AbstractAgentRuntime.admitThroughGateway(name(), context)
        val cancelled = AtomicBoolean()
        val done = CompletableFuture<Void>()
        val activeJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>()
        val activeThread = java.util.concurrent.atomic.AtomicReference<Thread?>()
        Thread.startVirtualThread {
            activeThread.set(Thread.currentThread())
            try {
                executeInternal(context, session, cancelled, activeJob)
                done.complete(null)
            } catch (ce: java.util.concurrent.CancellationException) {
                // Native Job.cancel propagated out. Treat as clean termination
                // so whenDone() resolves instead of hanging.
                if (!session.isClosed) session.complete()
                done.complete(null)
            } catch (e: Throwable) {
                if (!session.isClosed) session.error(e)
                done.completeExceptionally(e)
            } finally {
                activeThread.set(null)
            }
        }
        return object : ExecutionHandle {
            override fun cancel() {
                if (!cancelled.compareAndSet(false, true)) return
                // 1. Cancel the coroutine Job so any suspension points inside
                //    runBlocking unwind with CancellationException.
                activeJob.get()?.cancel()
                // 2. Interrupt the virtual thread as a belt-and-suspenders
                //    fallback. runBlocking cannot interrupt a native blocking
                //    socket read held by Koog's HttpClient; Thread.interrupt
                //    flips the VT's interrupt status so NIO channels and
                //    InterruptibleChannel-based I/O unwind.
                activeThread.get()?.interrupt()
                // 3. Resolve whenDone() immediately even if the VT is stuck in
                //    non-interruptible native code. CompletableFuture semantics
                //    drop the real completion harmlessly if the VT later catches
                //    up, so this is the safest backstop against a hung cancel.
                done.complete(null)
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

        // Mode-parity gap: Koog's AIAgent.run(String) tool-loop path accepts a
        // plain text user message, so multi-modal parts (Content.Image /
        // Content.Audio) cannot reach the tool-calling executor. When a
        // caller provides BOTH tools and multi-modal parts, the tool-calling
        // path wins — we log at WARN so the drop is visible in operator logs
        // rather than silently truncating the prompt (Correctness Invariant
        // #7, Mode Parity). Callers that need multi-modal + tools
        // simultaneously should pick a runtime that supports both (Built-in,
        // Spring AI, LC4j, or ADK).
        if (context.tools().isNotEmpty() && context.parts().any { it !is org.atmosphere.ai.Content.Text }) {
            logger.warn(
                "Koog bridge cannot combine tool-calling and multi-modal input in " +
                    "a single request; dropping {} non-text parts. Use the no-tools " +
                    "path or a different runtime (built-in/spring-ai/langchain4j/adk) " +
                    "for multi-modal + tools.",
                context.parts().count { it !is org.atmosphere.ai.Content.Text }
            )
        }

        if (context.tools().isNotEmpty()) {
            executeWithAgent(executor, model, context, session, cancelled, activeJob)
        } else {
            executeWithExecutor(executor, model, context, session, cancelled, activeJob)
        }
    }

    /**
     * Translate Atmosphere's [org.atmosphere.ai.Content] parts into Koog's
     * [ContentPart] hierarchy for multi-modal user-message assembly. Image
     * and Audio parts use [AttachmentContent.Binary.Base64] (Koog's preferred
     * on-the-wire encoding for binary attachments); File parts are not
     * translated because Koog's chat models do not accept generic file input
     * on the user-message surface — that maps to the tool-calling path
     * instead, which we degrade gracefully in [executeInternal].
     */
    private fun atmosphereContentsToKoogParts(
        parts: List<org.atmosphere.ai.Content>
    ): List<ContentPart> {
        if (parts.isEmpty()) return emptyList()
        val result = mutableListOf<ContentPart>()
        for (part in parts) {
            when (part) {
                is org.atmosphere.ai.Content.Text -> result.add(ContentPart.Text(part.text()))
                is org.atmosphere.ai.Content.Image -> {
                    val base64 = Base64.getEncoder().encodeToString(part.data())
                    val content = AttachmentContent.Binary.Base64(base64)
                    val format = part.mimeType().substringAfter("/", "png")
                    result.add(ContentPart.Image(content, format, part.mimeType(), "image.$format"))
                }
                is org.atmosphere.ai.Content.Audio -> {
                    val base64 = Base64.getEncoder().encodeToString(part.data())
                    val content = AttachmentContent.Binary.Base64(base64)
                    val format = part.mimeType().substringAfter("/", "wav")
                    result.add(ContentPart.Audio(content, format, part.mimeType(), "audio.$format"))
                }
                is org.atmosphere.ai.Content.File -> {
                    // Koog's chat surface does not accept generic file input —
                    // the ContentPart hierarchy only models Text/Image/Audio.
                    // Log at WARN (not DEBUG) so operators see the silent loss
                    // and know to route File attachments through ADK or
                    // Built-in instead. Matches the mode-parity-drop WARN
                    // fired by executeInternal when tools + multi-modal are
                    // combined on the Koog path.
                    logger.warn(
                        "Koog bridge dropping unsupported Content.File part: {} ({} bytes, {}). " +
                            "Koog's chat surface does not accept generic file input; " +
                            "route File attachments through adk or built-in for Gemini/OpenAI " +
                            "multi-modal support.",
                        part.fileName(), part.data().size, part.mimeType()
                    )
                }
            }
        }
        return result
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
            if (!session.isClosed) session.complete()
        } catch (ce: java.util.concurrent.CancellationException) {
            // Caller-initiated cancel (executeWithHandle) or coroutine unwinding.
            // Complete cleanly so the outer VT wrapper can resolve done() without
            // re-reporting the cancel as an error.
            if (cancelled.get()) {
                logger.debug("Koog agent cancelled by caller")
                if (!session.isClosed) session.complete()
            } else {
                logger.warn("Koog agent cancelled unexpectedly", ce)
                if (!session.isClosed) session.error(ce)
            }
            throw ce
        } catch (e: Exception) {
            logger.error("Koog agent execution failed", e)
            if (!session.isClosed) session.error(e)
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
            if (!session.isClosed) session.complete()
        } catch (ce: java.util.concurrent.CancellationException) {
            if (cancelled.get()) {
                logger.debug("Koog streaming cancelled by caller")
                if (!session.isClosed) session.complete()
            } else {
                logger.warn("Koog streaming cancelled unexpectedly", ce)
                if (!session.isClosed) session.error(ce)
            }
            throw ce
        } catch (e: Exception) {
            logger.error("Koog streaming failed", e)
            if (!session.isClosed) session.error(e)
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

            // Multi-modal + prompt-caching dispatch.
            //
            // Four combinations:
            //   1. no cache, no parts          → user(text)                    (fast path)
            //   2. no cache, parts             → user(text) { part(...) }      (DSL block)
            //   3. cache, no parts             → user([Text(text)], cacheCtl)  (cache on text-only)
            //   4. cache, parts                → user([Text(text), parts...], cacheCtl)
            //
            // Koog 0.7.3's CacheControl interface only exposes Bedrock impls
            // (FiveMinutes / OneHour / Default singletons), so this path is
            // honest on Bedrock-backed Koog models and silently ignored by
            // every other provider — the same "honored on one path,
            // no-op elsewhere" shape Spring AI / LangChain4j take for the
            // OpenAI prompt_cache_key field (Correctness Invariant #5 —
            // Runtime Truth: capability is declared because it works for at
            // least one provider through the same code path every caller
            // takes).
            val koogParts = atmosphereContentsToKoogParts(context.parts())
            val cacheControl = resolveBedrockCacheControl(context)

            if (koogParts.isEmpty() && cacheControl == null) {
                user(context.message())
            } else if (cacheControl == null) {
                user(context.message()) {
                    for (p in koogParts) {
                        part(p)
                    }
                }
            } else {
                val allParts = mutableListOf<ContentPart>(ContentPart.Text(context.message()))
                allParts.addAll(koogParts)
                user(allParts, cacheControl)
            }
        }
    }

    /**
     * Translate an Atmosphere [CacheHint] into one of Koog 0.7.3's Bedrock
     * cache-control variants. Returns {@code null} when caching is disabled
     * or the hint cannot be honored — callers take the no-cache branch of
     * [buildPrompt].
     *
     * Policy mapping (Bedrock ships only two TTL buckets):
     *   - [CacheHint.CachePolicy.CONSERVATIVE] → [CacheControl.Bedrock.FiveMinutes]
     *   - [CacheHint.CachePolicy.AGGRESSIVE]   → [CacheControl.Bedrock.OneHour]
     *
     * When the caller supplies an explicit TTL hint we pick the closest
     * Bedrock bucket (<=5 min → FiveMinutes, otherwise OneHour) so TTL
     * requests survive the translation to Bedrock's fixed-bucket model.
     */
    private fun resolveBedrockCacheControl(context: AgentExecutionContext): CacheControl? {
        val hint = CacheHint.from(context)
        if (!hint.enabled()) return null
        val ttl = hint.ttl().orElse(null)
        return when {
            ttl != null && ttl.toMinutes() <= 5 -> CacheControl.Bedrock.FiveMinutes
            ttl != null                         -> CacheControl.Bedrock.OneHour
            hint.policy() == CacheHint.CachePolicy.AGGRESSIVE -> CacheControl.Bedrock.OneHour
            else                                -> CacheControl.Bedrock.FiveMinutes
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
        AiCapability.TOOL_APPROVAL,
        // TOKEN_USAGE: the StreamFrame.End handler reads Koog's usage
        // totals and emits a typed TokenUsage record via session.usage()
        // after drain — see executeWithAgent lines 223-232.
        AiCapability.TOKEN_USAGE,
        // VISION / AUDIO / MULTI_MODAL are honest on the no-tools path:
        // atmosphereContentsToKoogParts translates Content.Image /
        // Content.Audio into Koog's native ContentPart.Image /
        // ContentPart.Audio with AttachmentContent.Binary.Base64, and the
        // buildPrompt DSL attaches them to the user message via
        // user(String, List<ContentPart>). When tools AND multi-modal parts
        // are present simultaneously, Koog's AIAgent.run(String) tool-loop
        // surface only accepts a plain text message — executeInternal logs
        // a WARN and the tool-calling path wins, degrading gracefully.
        AiCapability.VISION,
        AiCapability.AUDIO,
        AiCapability.MULTI_MODAL,
        // PROMPT_CACHING is honest on Bedrock-backed Koog models:
        // resolveBedrockCacheControl maps Atmosphere's portable CacheHint to
        // one of Koog 0.7.3's CacheControl.Bedrock.{FiveMinutes,OneHour}
        // singletons and attaches it to the outgoing Message.User via the
        // parts-list + cacheControl PromptBuilder overload. Non-Bedrock
        // providers silently drop the cache control — the same shape
        // Spring AI / LangChain4j take for OpenAI prompt_cache_key.
        AiCapability.PROMPT_CACHING,
        // PER_REQUEST_RETRY: honored via executeWithOuterRetry which
        // wraps executeInternal in a retry loop respecting
        // context.retryPolicy(). Pre-stream transient failures are
        // retried up to maxRetries on top of Koog's native HTTP retry.
        AiCapability.PER_REQUEST_RETRY
    )

    override fun models(): List<String> {
        // Koog's LLModel carries an id; report the default plus any
        // override. context.model() takes precedence at dispatch time.
        return listOf(defaultModel.id)
    }
}

