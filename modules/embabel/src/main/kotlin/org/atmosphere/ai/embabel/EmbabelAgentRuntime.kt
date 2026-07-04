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
import com.embabel.agent.api.common.streaming.StreamingPromptRunner
import com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder
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
import org.atmosphere.ai.ExecutionHandle
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.TokenUsage
import org.atmosphere.ai.tool.ToolScopes
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

        /** Cap on entries in one `ai.embabel.file_changes` metadata frame. */
        private const val MAX_AUDITED_CHANGES = 32

        /**
         * Resolve the conversation-scoped [org.atmosphere.ai.fs.AgentFileSystem]
         * for this dispatch, mirroring the built-in file tools' resolution
         * (and `AdkAgentRuntime.resolveAgentFileSystem`): the dispatch seam
         * publishes the scoped store under the interface key, and
         * resource-free pipeline paths carry the registration-time
         * [org.atmosphere.ai.fs.AgentFileSystemProvider] instead. Returns
         * `null` when the harness FILESYSTEM primitive did not resolve for
         * this session (no store in scope) or the operator forced
         * [org.atmosphere.ai.fs.FilesystemMode.BUILTIN] — in that mode the
         * portable tool floor owns the surface and attaching the native
         * bridge too would duplicate the file tools.
         */
        @JvmStatic
        internal fun resolveAgentFileSystem(
            session: StreamingSession?
        ): org.atmosphere.ai.fs.AgentFileSystem? {
            if (AiConfig.resolveFilesystemMode() == org.atmosphere.ai.fs.FilesystemMode.BUILTIN) {
                return null
            }
            val injectables = session?.injectables() ?: emptyMap<Class<*>, Any>()
            val direct = injectables[org.atmosphere.ai.fs.AgentFileSystem::class.java]
            if (direct is org.atmosphere.ai.fs.AgentFileSystem) {
                return direct
            }
            val provider = injectables[org.atmosphere.ai.fs.AgentFileSystemProvider::class.java]
            if (provider is org.atmosphere.ai.fs.AgentFileSystemProvider) {
                return provider.forConversation(ToolScopes.conversationId(injectables))
            }
            return null
        }

        /**
         * Mirror the per-run [com.embabel.agent.tools.file.FileChangeLog]
         * audit accumulated by [AtmosphereFileTools] onto the wire as one
         * `ai.embabel.file_changes` metadata frame (`TYPE:path` entries,
         * capped at [MAX_AUDITED_CHANGES]) so consoles can show what the
         * model touched. The log holds one NET entry per path — Embabel's
         * `DefaultFileChangeLog` contract replaces an earlier entry when the
         * same path changes again. Best-effort observability: no frame when
         * nothing changed or the session already closed.
         */
        @JvmStatic
        internal fun emitFileChangeAudit(fileTools: AtmosphereFileTools?, session: StreamingSession) {
            if (fileTools == null || session.isClosed) {
                return
            }
            val changes = fileTools.getChanges()
            if (changes.isEmpty()) {
                return
            }
            val summary = buildString {
                changes.take(MAX_AUDITED_CHANGES).forEachIndexed { i, change ->
                    if (i > 0) append(", ")
                    append(change.type).append(':').append(change.path)
                }
                if (changes.size > MAX_AUDITED_CHANGES) {
                    append(" (+").append(changes.size - MAX_AUDITED_CHANGES).append(" more)")
                }
            }
            session.sendMetadata("ai.embabel.file_changes", summary)
            logger.debug("Mirrored {} Embabel file change(s) to session {}",
                changes.size, session.sessionId())
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
        // Admit through the process-wide AiGateway before issuing the native
        // Embabel dispatch — uniform per-user rate limiting and credential
        // resolution across all twelve contract-tested runtimes (Correctness Invariant #3).
        org.atmosphere.ai.AbstractAgentRuntime.admitThroughGateway(name(), context)
        // Install the cross-runtime ToolLoopGuard before reading listeners so
        // the guard sees every onModelStart this dispatch fires. No-op when
        // no ToolLoopPolicy is attached. Same install pattern as the Java
        // base class (AbstractAgentRuntime.execute) — keeps the cap behaviour
        // uniform across all runtimes regardless of base class.
        val effective = org.atmosphere.ai.llm.ToolLoopGuard.installIfPresent(name(), context, session)
        val platform = agentPlatform
            ?: throw IllegalStateException(
                "EmbabelAgentRuntime: AgentPlatform not configured. " +
                    "Call EmbabelAgentRuntime.setAgentPlatform() or use Spring auto-configuration."
            )

        session.progress("Connecting to embabel...")

        val targetAgent = effective.agentId() ?: agentName
        val deployedAgent = platform.agents().firstOrNull { it.name == targetAgent }

        // Model-lifecycle hooks: fire onModelStart before dispatch,
        // onModelEnd / onModelError on terminal paths. messageCount = history
        // turns + (system prompt? 1 : 0) + 1 user prompt; toolCount =
        // context.tools(). Same posture as Spring AI / LC4j / ADK / Koog.
        // Token usage is captured inside executeDeployedAgent via
        // process.usage() and stashed in lastUsage so onModelEnd reports the
        // final aggregate.
        val messageCount = effective.history().size +
            (if (effective.systemPrompt()?.isNotEmpty() == true) 1 else 0) + 1
        val toolCount = effective.tools().size
        val lastUsage = java.util.concurrent.atomic.AtomicReference<TokenUsage>()
        val modelScope = org.atmosphere.ai.ModelCallScope.open(
            effective.listeners(), effective.model() ?: name(), messageCount, toolCount
        )

        try {
            executeWithOuterRetry(effective, session) {
                if (deployedAgent != null) {
                    warnIfDeployedAgentDropsRequestFeatures(deployedAgent, effective, session)
                    executeDeployedAgent(platform, deployedAgent, effective, session, lastUsage)
                } else {
                    executeAtmosphereNative(platform, effective, session, lastUsage)
                }
            }
            modelScope.complete(lastUsage.get())
        } catch (t: Throwable) {
            modelScope.fail(t)
            throw t
        }
    }

    /**
     * Cooperative cancellation entry point. Embabel implements [AgentRuntime]
     * directly (it does not extend `AbstractAgentRuntime`), so — like the Koog
     * runtime — it overrides [executeWithHandle] rather than a `doExecute*`
     * template method. The blocking [execute] dispatch runs on a dedicated
     * virtual thread so this method returns promptly with a live handle that a
     * client disconnect can [ExecutionHandle.cancel] (Correctness Invariant #2).
     *
     * **Cancel semantics differ by dispatch path:**
     *  - *Atmosphere-native streaming* ([executeAtmosphereNative]) blocks on a
     *    Reactor `blockLast()`. Interrupting the worker disposes that
     *    subscription, which propagates an upstream cancel to the LLM stream —
     *    a real abort.
     *  - *Deployed-agent* ([executeDeployedAgent] → [AgentPlatform.runAgentFrom])
     *    and the *blocking `generateText` fallback* have no native cancel
     *    primitive. There the interrupt is best-effort: [cancel] frees the
     *    client immediately (`session.complete`) and settles [whenDone], while
     *    the upstream call may run to completion in the background. This is the
     *    "cooperative" guarantee [AiCapability.CANCELLATION] documents — not
     *    hard preemption.
     *
     * [whenDone] is resolved immediately on [cancel] as a backstop so a worker
     * stuck in non-interruptible native code cannot hang the disconnect path;
     * `CompletableFuture` semantics drop the later real completion harmlessly.
     */
    override fun executeWithHandle(
        context: AgentExecutionContext,
        session: StreamingSession
    ): ExecutionHandle {
        val cancelled = AtomicBoolean()
        val done = CompletableFuture<Void>()
        val activeThread = AtomicReference<Thread?>()
        Thread.startVirtualThread {
            activeThread.set(Thread.currentThread())
            try {
                execute(context, session)
                done.complete(null)
            } catch (ie: InterruptedException) {
                // Cooperative cancel: a blocking dispatch (Reactor blockLast /
                // Thread.sleep in the runAgentFrom planner) unwinds via
                // interruption. Treat as clean termination, not an error.
                if (!session.isClosed) session.complete()
                done.complete(null)
            } catch (t: Throwable) {
                // execute() already fired fireModelError + (on the streaming
                // path) session.error before propagating; settle whenDone with
                // the cause so callers observe the failure.
                done.completeExceptionally(t)
            } finally {
                activeThread.set(null)
            }
        }
        return object : ExecutionHandle {
            override fun cancel() {
                if (!cancelled.compareAndSet(false, true)) return
                activeThread.get()?.interrupt()
                if (!session.isClosed) session.complete()
                done.complete(null)
            }

            override fun isDone(): Boolean = done.isDone

            override fun whenDone(): CompletableFuture<Void> = done
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
     * Embabel's deployed-agent dispatch reads `userMessage` from the input map
     * but ignores Atmosphere's per-request `tools()` and image `parts()` — the
     * deployed `@Agent` owns its own configuration. When the caller sends a
     * context that carries those features, log a WARN and emit a metadata event
     * so the drop is visible to operators rather than silent. The runtime's
     * `capabilities()` set advertises TOOL_CALLING / TOOL_APPROVAL / VISION /
     * MULTI_MODAL because the Atmosphere-native fallback path honors them; this
     * warning surfaces the drop on the deployed-agent path so partial-matrix
     * behavior is loud (Correctness Invariant #5 — Runtime Truth, applied to
     * dispatch-mode semantics).
     */
    private fun warnIfDeployedAgentDropsRequestFeatures(
        agent: com.embabel.agent.core.Agent,
        context: AgentExecutionContext,
        session: StreamingSession
    ) {
        val droppedTools = context.tools().isNotEmpty()
        val droppedImages = context.parts().any { it is org.atmosphere.ai.Content.Image }
        // The native file surface (AiCapability.VIRTUAL_FILESYSTEM) also only
        // exists on the Atmosphere-native path: ProcessOptions has no tool
        // surface, so a deployed dispatch cannot receive AtmosphereFileTools —
        // and when the runtime's declared capability suppressed the built-in
        // floor (FilesystemMode.AUTO), the deployed run has no file tools at
        // all. Surface that loudly instead of silently.
        val droppedFileStore = hasFileStoreInScope(session)
        if (!droppedTools && !droppedImages && !droppedFileStore) {
            return
        }
        val dropped = buildList {
            if (droppedTools) add("${context.tools().size} request-level tool(s)")
            if (droppedImages) {
                val n = context.parts().count { it is org.atmosphere.ai.Content.Image }
                add("$n image part(s)")
            }
            if (droppedFileStore) add("the conversation file store")
        }.joinToString(", ")
        logger.warn(
            "Embabel deployed-agent dispatch [{}] ignores {} — deployed @Agent classes " +
                "own their own tool/image/file configuration. Invoke without agentId() " +
                "(Atmosphere-native path) to honor request-level features.",
            agent.name, dropped
        )
        session.sendMetadata("ai.embabel.dropped_features", dropped)
    }

    /**
     * Whether the harness FILESYSTEM primitive scoped a store onto this
     * session that the native surface could expose — a presence check only
     * (no provider materialization, so no directory side effects). `false`
     * under [org.atmosphere.ai.fs.FilesystemMode.BUILTIN], where the portable
     * tool floor owns the surface and already flows through `context.tools()`.
     */
    private fun hasFileStoreInScope(session: StreamingSession): Boolean {
        if (AiConfig.resolveFilesystemMode() == org.atmosphere.ai.fs.FilesystemMode.BUILTIN) {
            return false
        }
        val injectables = session.injectables()
        return injectables.containsKey(org.atmosphere.ai.fs.AgentFileSystem::class.java)
            || injectables.containsKey(org.atmosphere.ai.fs.AgentFileSystemProvider::class.java)
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
        session: StreamingSession,
        lastUsage: java.util.concurrent.atomic.AtomicReference<TokenUsage>
    ) {
        val channel = AtmosphereOutputChannel(session)
        val process = try {
            var options = ProcessOptions.DEFAULT.withOutputChannel(channel)
            // Harness PLANNING (native, read-only): observe the GOAP plan the
            // A* planner formulates for this process and mirror it into
            // AiEvent.PlanUpdate frames (EmbabelGoapPlanBridge). Skipped under
            // PlanningMode.BUILTIN — the operator asked for the portable
            // write_todos floor only, and two plan sources on one console
            // surface would conflict (no duplicate plan surfaces). The bridge
            // carries the ToolScopes-derived conversation/agent scope so its
            // PlanUpdate frames correlate exactly like the write_todos floor's.
            if (AiConfig.resolvePlanningMode() != org.atmosphere.ai.plan.PlanningMode.BUILTIN) {
                val injectables = session.injectables()
                options = options.withListener(EmbabelGoapPlanBridge(
                    session,
                    ToolScopes.conversationId(injectables),
                    ToolScopes.agentId(injectables, context.agentId())))
            }
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
        // during this process into a single Usage record. The usage is also
        // stashed in lastUsage so the caller's onModelEnd lifecycle hook
        // reports the final aggregate.
        try {
            val usage = process.usage()
            val input = usage.promptTokens?.toLong() ?: 0L
            val output = usage.completionTokens?.toLong() ?: 0L
            val total = usage.totalTokens?.toLong() ?: (input + output)
            val tokenUsage = TokenUsage(input, output, 0L, total, null)
            if (tokenUsage.hasCounts()) {
                session.usage(tokenUsage)
                lastUsage.set(tokenUsage)
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
     * **Streaming**: When Embabel's underlying LLM service implements the
     * [com.embabel.agent.spi.streaming.StreamingLlmOperations] contract
     * (true for the OpenAI / Gemini chat-client backends shipped with
     * embabel-agent-spring-boot-starter), this path uses
     * [StreamingPromptRunnerBuilder] to acquire a Reactor `Flux<String>`
     * of token-level chunks and forwards each chunk to [StreamingSession.send]
     * — making `TEXT_STREAMING` an honest capability declaration on the
     * Atmosphere-native path. When streaming is unavailable on the
     * configured runner, the path falls back to the blocking
     * [com.embabel.agent.api.common.PromptRunnerOperations.generateText]
     * call so the dispatch still completes (no silent capability drop).
     *
     * Token usage reporting is not available on this path — Embabel's
     * PromptRunner surface does not expose an aggregated usage record the
     * way `AgentProcess.usage()` does on the runAgentFrom path. Callers who
     * require `ai.tokens.*` telemetry should either (a) use a deployed
     * `@Agent` class (which routes through runAgentFrom) or (b) select a
     * non-Embabel runtime.
     *
     * **Virtual filesystem**: when the harness FILESYSTEM primitive scoped an
     * [org.atmosphere.ai.fs.AgentFileSystem] with a disk root onto the
     * session, this path attaches [AtmosphereFileTools] — Embabel's native
     * `FileTools` tool surface rooted at the same conversation directory,
     * with every mutation routed back through the store so
     * `AgentFileSystem.Limits` hold — the wiring behind
     * [AiCapability.VIRTUAL_FILESYSTEM]. See `resolveNativeFileTools`.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun executeAtmosphereNative(
        platform: AgentPlatform,
        context: AgentExecutionContext,
        session: StreamingSession,
        lastUsage: java.util.concurrent.atomic.AtomicReference<TokenUsage>
    ) {
        // lastUsage is intentionally unused on this path: the Atmosphere-native
        // path drives Ai.withDefaultLlm directly without an AgentProcess, so
        // no LlmInvocationHistory.usage() aggregate is available — the per-
        // streamed-call usage holder stays null. This is documented in the
        // function-level KDoc above.
        val ai: Ai = try {
            InfrastructureInjectionConfiguration().aiFactory(platform)
        } catch (t: Throwable) {
            logger.error("Failed to obtain Embabel Ai factory from AgentPlatform", t)
            session.error(t)
            return
        }

        // Embabel's PromptRunner contract: generateText(text) carries the
        // current user prompt; withMessages(...) carries prior history only.
        // Calling generateText("") makes Embabel reject the request with
        // "Text content cannot be empty" before it consults withMessages,
        // so the current message MUST go to generateText, not into messages.
        val historyMessages = buildList<Message> {
            context.history().forEach { msg ->
                when (msg.role()) {
                    "system" -> add(SystemMessage(msg.content()))
                    "assistant" -> add(AssistantMessage(msg.content()))
                    else -> add(UserMessage(msg.content()))
                }
            }
        }
        val currentMessage = context.message().orEmpty().ifBlank { " " }

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

        // Harness FILESYSTEM (native surface, AiCapability.VIRTUAL_FILESYSTEM):
        // when the dispatch scoped an AgentFileSystem with a disk root, expose
        // it through Embabel's own FileTools tool surface — AtmosphereFileTools
        // self-publishes the @LlmTool file methods rooted at the SAME
        // conversation directory, with every mutation routed back through the
        // store so AgentFileSystem.Limits stay enforced (Invariant #3).
        val nativeFileTools = resolveNativeFileTools(session)

        // Build the configured PromptRunner once — both the streaming and
        // blocking dispatch paths reuse the same withSystemPrompt /
        // withMessages / withTools / withImages wiring, so the only branch
        // is the terminal call (generateStream() vs generateText()).
        // Per-request customizer (EmbabelPromptRunner.attach) applies AFTER
        // the runtime's default wiring so callers can override temperature /
        // model / etc. without losing the system prompt + history we just set.
        val runner = try {
            var r = ai.withDefaultLlm()
            if (!context.systemPrompt().isNullOrBlank()) {
                r = r.withSystemPrompt(context.systemPrompt())
            }
            if (historyMessages.isNotEmpty()) {
                r = r.withMessages(historyMessages)
            }
            if (embabelTools.isNotEmpty()) {
                r = r.withTools(embabelTools)
            }
            if (embabelImages.isNotEmpty()) {
                r = r.withImages(embabelImages)
            }
            if (nativeFileTools != null) {
                // User tools win on a name collision, same posture as the
                // built-in floor and the ADK native file-tool pair.
                val userToolNames = embabelTools.map { it.definition.name }.toSet()
                val published = nativeFileTools.tools.filter { tool ->
                    val claimed = tool.definition.name in userToolNames
                    if (claimed) {
                        logger.warn("Embabel native file tool '{}' skipped: a user tool " +
                            "already claims the name", tool.definition.name)
                    }
                    !claimed
                }
                if (published.isNotEmpty()) {
                    r = r.withTools(published)
                    logger.debug("Attached {} Embabel native file tool(s) rooted at the " +
                        "conversation store", published.size)
                }
            }
            // Native ToolLoopPolicy enforcement is NOT yet wired against
            // Embabel 0.3.5 (the pinned version): PromptRunner does not
            // expose withToolLoopInspectors / ToolLoopInspector on the
            // 0.3.x line. The cross-runtime ToolLoopGuard installed in
            // execute() provides strict() cap semantics at the wire layer.
            // Native upstream enforcement awaits the Embabel release that
            // adds the inspector API (tracked in modules/embabel/README.md).
            val customizer = EmbabelPromptRunner.from(context)
            if (customizer != null) {
                r = customizer.apply(r)
                logger.debug("Applied per-request EmbabelPromptRunner customizer")
            }
            r
        } catch (t: Throwable) {
            logger.error("Failed to configure Embabel PromptRunner", t)
            session.error(t)
            return
        }

        // Streaming dispatch path: when Embabel's underlying LLM service
        // implements the StreamingLlmOperations contract,
        // StreamingPromptRunnerBuilder exposes a Flux<String> of token-level
        // chunks. Each chunk lands on session.send() so browser clients see
        // tokens as they arrive instead of a single end-of-call burst.
        // Falls back to the blocking generateText() path when streaming is
        // not supported by the configured model — preserves the
        // pre-streaming behavior for environments where Embabel's
        // StreamingChatClientOperations cannot be wired (e.g. some
        // tool-only or non-OpenAI-compatible providers).
        val streamingRunner = try {
            StreamingPromptRunnerBuilder(runner).streaming() as StreamingPromptRunner.Streaming
        } catch (t: Throwable) {
            logger.debug(
                "Embabel StreamingPromptRunnerBuilder unavailable for this PromptRunner; " +
                    "falling back to blocking generateText() path", t
            )
            null
        }

        if (streamingRunner != null) {
            try {
                streamingRunner
                    .withPrompt(currentMessage)
                    .generateStream()
                    .doOnNext { chunk ->
                        if (!session.isClosed && chunk.isNotEmpty()) {
                            session.send(chunk)
                        }
                    }
                    .doOnError { t ->
                        logger.error("Embabel streaming dispatch failed", t)
                        if (!session.isClosed) {
                            session.error(t)
                        }
                    }
                    .doOnComplete {
                        if (!session.isClosed) {
                            emitFileChangeAudit(nativeFileTools, session)
                            session.complete()
                        }
                    }
                    .blockLast()
                return
            } catch (t: Throwable) {
                // Surface the streaming failure rather than silently retrying
                // through the blocking path — a mid-stream failure has
                // already emitted partial content, and re-dispatching as a
                // fresh blocking call would duplicate output. The doOnError
                // above already wrote session.error; this catch covers
                // pre-subscribe construction errors.
                if (!session.hasErrored() && !session.isClosed) {
                    session.error(t)
                }
                return
            }
        }

        // Blocking fallback — used only when StreamingPromptRunnerBuilder
        // could not build a Streaming instance for this runner (e.g. the
        // model service does not implement StreamingLlmOperations).
        val result: String = try {
            runner.generateText(currentMessage)
        } catch (t: Throwable) {
            logger.error("Embabel Ai dispatch failed", t)
            session.error(t)
            return
        }

        if (!session.isClosed) {
            if (result.isNotBlank()) {
                session.send(result)
            }
            emitFileChangeAudit(nativeFileTools, session)
            session.complete()
        }
    }

    /**
     * Build the dispatch-scoped [AtmosphereFileTools] when the harness
     * FILESYSTEM primitive resolved an [org.atmosphere.ai.fs.AgentFileSystem]
     * with a disk root for this session. A scoped store *without* a disk root
     * (a custom [org.atmosphere.ai.fs.AgentFileSystem] implementation) cannot
     * be bridged — Embabel's `FileTools` contract is real-disk only — so the
     * skip is logged loudly rather than faking a surface (Invariant #5).
     */
    private fun resolveNativeFileTools(session: StreamingSession): AtmosphereFileTools? {
        val fs = resolveAgentFileSystem(session) ?: return null
        val fileTools = AtmosphereFileTools.forStore(fs)
        if (fileTools == null) {
            logger.warn(
                "Embabel native file surface skipped: the scoped AgentFileSystem ({}) " +
                    "exposes no disk root — Embabel FileTools is real-disk only. Force " +
                    "atmosphere.ai.filesystem=builtin to restore a file surface for " +
                    "custom stores.",
                fs.javaClass.name
            )
        }
        return fileTools
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
        AiCapability.MULTI_MODAL,
        // BUDGET_ENFORCEMENT: framework-level circuit breaker via the
        // AiPipeline BudgetCapturingSession decorator — honest because
        // the deployed-agent dispatch path emits typed TokenUsage through
        // session.usage(), the signal BudgetCapturingSession taps for
        // token / step abort. The Atmosphere-native dispatch path does
        // not surface usage (Embabel limitation), so token / step limits
        // are effective only on the deployed-agent path; wall-clock
        // limits trip on either path.
        AiCapability.BUDGET_ENFORCEMENT,
        // CONFIDENCE_SCORES: framework-level — AiPipeline's
        // ConfidenceCapturingSession parses the model-reported
        // confidence field on stream completion. Honest on both
        // dispatch paths (deployed-agent and Atmosphere-native) because
        // both honor SYSTEM_PROMPT and stream response text.
        AiCapability.CONFIDENCE_SCORES,
        // PASSIVATION: AgentPassivation snapshots context.history() into
        // a CheckpointStore. Honest because the Atmosphere-native dispatch
        // path threads history into PromptRunner.withMessages — a resumed
        // call observes the same conversation the paused call saw.
        AiCapability.PASSIVATION,
        // CANCELLATION: executeWithHandle runs the blocking dispatch on a
        // virtual thread and returns a live ExecutionHandle. cancel()
        // interrupts the worker — a real upstream abort on the
        // Atmosphere-native Reactor streaming path (interrupt disposes
        // blockLast), best-effort on the deployed-agent / blocking-generateText
        // paths (no native cancel primitive; the client is freed immediately
        // and whenDone settles, but the upstream call may finish in the
        // background). "Cooperative" cancellation per the capability's
        // contract. Pinned by EmbabelAgentRuntimeCancelTest.
        AiCapability.CANCELLATION,
        // PLANNING: honest on the deployed-agent path — executeDeployedAgent
        // registers EmbabelGoapPlanBridge (an AgenticEventListener) on the
        // ProcessOptions, mirroring every AgentProcessPlanFormulatedEvent /
        // ReplanRequestedEvent into AiEvent.PlanUpdate frames. The plan is
        // read-only and framework-computed (A* GOAP from @Action pre/post-
        // conditions, re-planned per action — the model never authors it);
        // the emitted goal carries a "GOAP" marker so consoles show that.
        // The Atmosphere-native fallback path (executeAtmosphereNative)
        // drives a direct PromptRunner with no planner, so no plan surface
        // exists there — same path asymmetry as TOKEN_USAGE above. Pinned
        // by EmbabelGoapPlanBridgeTest.
        AiCapability.PLANNING,
        // VIRTUAL_FILESYSTEM: honest on the Atmosphere-native path —
        // executeAtmosphereNative attaches AtmosphereFileTools (Embabel's
        // FileTools tool surface: createFile/writeFile/editFile/appendFile/
        // delete/createDirectory/readFile plus the native read helpers)
        // rooted at the SAME conversation-scoped directory the harness
        // AgentFileSystem manages. Every mutation routes back through the
        // WorkspaceAgentFileSystem so Limits (per-file/count/total bytes)
        // and traversal guards hold on the native surface too (Invariant
        // #3/#4). The deployed-agent path cannot receive per-process tools
        // (ProcessOptions has no tool surface) — the drop is warned loudly
        // in warnIfDeployedAgentDropsRequestFeatures, same path asymmetry
        // as TOOL_CALLING / VISION above. Pinned by
        // EmbabelAgentRuntimeVfsTest.
        AiCapability.VIRTUAL_FILESYSTEM
    )
}
