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
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationCategory
import ai.koog.prompt.dsl.ModerationCategoryResult
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AiEnvironment
import org.atmosphere.ai.CollectingSession
import org.atmosphere.ai.DelegatingStreamingSession
import org.atmosphere.ai.tool.ToolDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A turn cancelled before its model call starts (an operator's cancel landing
 * while a coordinator sets up its synthesis) builds and starts no Koog agent:
 * no feature is installed, no strategy starts from `__start__`, and the model
 * is never called.
 */
class KoogCancelledTurnTest {

    private val modelCalls = AtomicInteger()
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var koogLogger: Logger
    private var previousLevel: ch.qos.logback.classic.Level? = null
    private lateinit var environment: AiEnvironment
    private lateinit var runtime: KoogAgentRuntime

    @BeforeEach
    fun setUp() {
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        koogLogger = LoggerFactory.getLogger("ai.koog") as Logger
        previousLevel = koogLogger.level
        // Koog logs the agent's start at INFO.
        koogLogger.level = ch.qos.logback.classic.Level.INFO
        koogLogger.addAppender(appender)
        environment = AiEnvironment.create { null }
        KoogAgentRuntime.setPromptExecutor(environment, CountingExecutor(modelCalls))
        runtime = KoogAgentRuntime().also { it.attach(environment) }
    }

    @AfterEach
    fun tearDown() {
        koogLogger.detachAppender(appender)
        koogLogger.level = previousLevel
        environment.close()
    }

    private fun context(withTools: Boolean): AgentExecutionContext {
        val tools = if (withTools) listOf(ToolDefinition.builder("echo", "Echo a value")
            .parameter("value", "the value", "string")
            .executor { args -> "echo:${args["value"]}" }
            .build()) else emptyList()
        return AgentExecutionContext(
            "Write the executive briefing.", "You are a CEO", null,
            null, "session-1", "user-1", "conv-1",
            tools, null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        ).withRetryPolicy(org.atmosphere.ai.RetryPolicy.NONE)
    }

    private fun cancelledTurn() = object : DelegatingStreamingSession(CollectingSession("cancelled-turn")) {
        override fun isTurnCancelled() = true
    }

    @Test
    fun `a cancelled turn builds no agent and calls no model`() {
        for (withTools in listOf(true, false)) {
            val handle = runtime.executeWithHandle(context(withTools), cancelledTurn())
            handle.whenDone().get(5, TimeUnit.SECONDS)
        }
        runtime.execute(context(true), cancelledTurn())

        assertEquals(0, modelCalls.get(), "the model was called for a cancelled turn")
        val started = appender.list.map { it.formattedMessage }
            .filter { it.contains("installing feature") || it.contains("__start__") }
        assertTrue(started.isEmpty(), "a Koog agent was built for a cancelled turn: $started")
    }

    @Test
    fun `a live turn still builds the agent and calls the model`() {
        val handle = runtime.executeWithHandle(context(true), CollectingSession("live-turn"))
        handle.whenDone().get(10, TimeUnit.SECONDS)
        assertTrue(modelCalls.get() > 0, "the live turn reached the model")
        assertTrue(appender.list.any { it.formattedMessage.contains("__start__") },
            "the probe sees a live agent start: ${appender.list.map { it.formattedMessage }}")
    }

    private class CountingExecutor(private val calls: AtomicInteger) : PromptExecutor() {
        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> {
            calls.incrementAndGet()
            return flowOf(StreamFrame.TextDelta("answer"))
        }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            calls.incrementAndGet()
            return Message.Assistant(content = "answer", metaInfo = ResponseMetaInfo.Empty)
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            ModerationResult(false, emptyMap<ModerationCategory, ModerationCategoryResult>())

        override fun close() {}
    }
}
